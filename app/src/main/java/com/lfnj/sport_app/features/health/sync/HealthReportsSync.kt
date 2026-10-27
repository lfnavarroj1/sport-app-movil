package com.lfnj.sport_app.features.health.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lfnj.sport_app.core.network.ApiResult
import com.lfnj.sport_app.core.network.apiCall
import com.lfnj.sport_app.core.session.AuthorizedResult
import com.lfnj.sport_app.core.session.RestoreOutcome
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.health.data.HealthReportResponseDto
import com.lfnj.sport_app.features.health.data.HealthReportsApi
import com.lfnj.sport_app.features.health.data.ReportHealthRequestDto
import com.lfnj.sport_app.features.health.data.local.HealthReportEntity
import com.lfnj.sport_app.features.health.data.local.HealthReportsCodec
import com.lfnj.sport_app.features.health.data.local.HealthReportsDao
import com.lfnj.sport_app.features.health.model.HealthOwner
import com.lfnj.sport_app.features.health.model.HealthSyncFailure
import com.lfnj.sport_app.features.training.session.data.TrainingSyncScheduler
import com.lfnj.sport_app.features.training.session.data.local.TransactionRunner
import com.lfnj.sport_app.features.training.session.sync.SyncRun
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Sincroniza la cola de reportes de salud de un deportista (ADR-037 HU042 §4, política de ADR-029):
 *
 * - envía cada reporte pendiente con su `report_id`, su `Idempotency-Key` y su cuerpo cifrado, así
 *   que un reintento tras una respuesta perdida no duplica el reporte (CA3, CA5);
 * - solo reintenta pérdida de red, renovación no disponible y `502`/`503`/`504`; un `4xx` deja el
 *   reporte en `SYNC_FAILED` con una causa explicable;
 * - solo un `201` que corresponde al reporte enviado lo marca `SYNCED`; el contrato devuelve `201`
 *   también al repetir el mismo `report_id` con los mismos datos. Un `409 report_id_reused` significa
 *   datos distintos y nunca se presenta como confirmado;
 * - un reporte con más de 30 días vence y queda `SYNC_FAILED`;
 * - usa la sesión vigente del deportista dueño de la partición y nunca guarda tokens.
 */
class HealthReportsSyncEngine(
    private val dao: HealthReportsDao,
    private val transactions: TransactionRunner,
    private val codec: HealthReportsCodec,
    private val api: HealthReportsApi?,
    private val sessionManager: SessionManager,
    private val json: Json,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun run(owner: String): SyncRun {
        expirePending()
        val api = api ?: return SyncRun.SUSPENDED
        when (val identity = authenticatedOwner()) {
            Identity.Unreachable -> return SyncRun.RETRY
            Identity.SignedOut -> return SyncRun.SUSPENDED
            is Identity.Owner -> if (identity.key != owner) return SyncRun.SUSPENDED
        }
        for (report in dao.pendingReports(owner)) {
            val body = codec.decodeRequest(report)
            if (body == null) {
                dao.replaceReport(report.failed(HealthSyncFailure.UNREADABLE.name))
                continue
            }
            dao.markAttempt(report.reportId, clock.millis())
            val outcome = sessionManager.authorized { auth -> apiCall(json) { api.report(auth, report.idempotencyKey, body) } }
            when (outcome) {
                AuthorizedResult.SignedOut -> return SyncRun.SUSPENDED
                AuthorizedResult.RenewalUnavailable -> return SyncRun.RETRY
                is AuthorizedResult.Completed -> when (val result = outcome.result) {
                    is ApiResult.Success -> confirm(owner, report, body, result.value)
                    ApiResult.NoConnection, ApiResult.Timeout -> return SyncRun.RETRY
                    ApiResult.NotConfigured -> return SyncRun.SUSPENDED
                    ApiResult.InvalidResponse -> dao.replaceReport(report.failed(HealthSyncFailure.INVALID_RESPONSE.name))
                    is ApiResult.HttpFailure ->
                        if (result.status in RETRYABLE_STATUSES) return SyncRun.RETRY else reject(report, result)
                }
            }
        }
        return SyncRun.COMPLETED
    }

    private sealed interface Identity {
        data class Owner(val key: String) : Identity
        data object Unreachable : Identity
        data object SignedOut : Identity
    }

    /** Deportista autenticado. Tras un reinicio del proceso recupera la sesión con el refresh token cifrado. */
    private suspend fun authenticatedOwner(): Identity {
        sessionManager.currentUser.value?.let { return Identity.Owner(HealthOwner.keyOf(it.userId)) }
        return when (val restored = sessionManager.restore()) {
            is RestoreOutcome.Restored -> Identity.Owner(HealthOwner.keyOf(restored.user.userId))
            RestoreOutcome.NoConnection, is RestoreOutcome.Unavailable -> Identity.Unreachable
            RestoreOutcome.NoSession, RestoreOutcome.Expired, RestoreOutcome.UnsupportedActor, RestoreOutcome.NotConfigured ->
                Identity.SignedOut
        }
    }

    private suspend fun confirm(owner: String, report: HealthReportEntity, body: ReportHealthRequestDto, response: HealthReportResponseDto) {
        val reportedAt = response.confirms(body)
        if (reportedAt == null) {
            dao.replaceReport(report.failed(HealthSyncFailure.INVALID_RESPONSE.name))
            return
        }
        transactions.run {
            // Solo se confirma si sigue en la partición (no se retiró ni se limpió durante el envío).
            if (dao.report(owner, report.reportId) != null) dao.replaceReport(report.synced(codec.encode(body, reportedAt)))
        }
    }

    private suspend fun reject(report: HealthReportEntity, failure: ApiResult.HttpFailure) {
        val issues = failure.fieldIssues
        val reason = when {
            failure.status == 422 && issues.any { it.issue == ISSUE_UNSUPPORTED_CONSENT || it.field == FIELD_CONSENT } ->
                HealthSyncFailure.CONSENT_VERSION
            failure.status == 422 && issues.any { it.issue == ISSUE_TOO_OLD } -> HealthSyncFailure.EXPIRED
            failure.status == 422 -> HealthSyncFailure.VALIDATION
            failure.status == 404 && failure.code == CODE_SESSION_NOT_FOUND -> HealthSyncFailure.SESSION_NOT_FOUND
            failure.status == 403 -> HealthSyncFailure.PERMISSION_REQUIRED
            failure.status == 409 -> HealthSyncFailure.CONFLICT
            else -> HealthSyncFailure.REJECTED
        }
        val detail = issues.firstOrNull()?.takeIf { reason == HealthSyncFailure.VALIDATION }
        dao.replaceReport(report.failed(reason.name, detail?.field, detail?.issue))
    }

    /** Vence los pendientes con más de 30 días en todas las particiones del dispositivo. */
    private suspend fun expirePending() {
        val threshold = clock.millis() - RETENTION.toMillis()
        dao.pendingCreatedBefore(threshold).forEach { dao.replaceReport(it.failed(HealthSyncFailure.EXPIRED.name)) }
    }

    companion object {
        /** Vencimiento de los reportes pendientes (ADR-037 HU042 §4). */
        val RETENTION: Duration = Duration.ofDays(30)
        private val RETRYABLE_STATUSES = setOf(502, 503, 504)
        private const val CODE_SESSION_NOT_FOUND = "training_session_not_found"
        private const val ISSUE_UNSUPPORTED_CONSENT = "unsupported_consent_version"
        private const val ISSUE_TOO_OLD = "too_old"
        private const val FIELD_CONSENT = "consent.version"
    }
}

/** Trabajo durable de HU042: ejecuta [HealthReportsSyncEngine] para la partición recibida. */
class HealthReportsSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val engine: HealthReportsSyncEngine,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val owner = inputData.getString(KEY_OWNER) ?: return Result.success()
        return when (engine.run(owner)) {
            SyncRun.RETRY -> Result.retry()
            // Suspendido: la partición se reanuda cuando el mismo deportista vuelva a autenticarse.
            SyncRun.COMPLETED, SyncRun.SUSPENDED -> Result.success()
        }
    }

    companion object {
        const val KEY_OWNER = "owner_key"
    }
}

/** Crea [HealthReportsSyncWorker] con las dependencias del contenedor manual; `null` para los demás trabajos. */
class HealthReportsSyncWorkerFactory(private val engine: () -> HealthReportsSyncEngine) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        if (handles(workerClassName)) HealthReportsSyncWorker(appContext, workerParameters, engine()) else null

    companion object {
        /** Solo crea el trabajo de HU042; los de HU032, HU034, HU004 y HU039 siguen en sus fábricas. */
        fun handles(workerClassName: String): Boolean = workerClassName == HealthReportsSyncWorker::class.java.name
    }
}

/**
 * Trabajo único por partición con conectividad y reintento exponencial. Los datos de entrada solo
 * contienen la huella del deportista. Tiene una etiqueta propia: [cancelAll] detiene solo HU042.
 */
class WorkManagerHealthReportsSyncScheduler(private val context: Context) : TrainingSyncScheduler {
    override fun schedule(owner: String) {
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(owner), ExistingWorkPolicy.APPEND_OR_REPLACE, request(owner))
    }

    override fun cancelAll() {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
    }

    companion object {
        const val TAG = "health-reports-sync"
        private const val INITIAL_BACKOFF_SECONDS = 30L

        fun uniqueName(owner: String) = "$TAG-$owner"

        fun request(owner: String): OneTimeWorkRequest = OneTimeWorkRequestBuilder<HealthReportsSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(workDataOf(HealthReportsSyncWorker.KEY_OWNER to owner))
            .addTag(TAG)
            .build()
    }
}

/**
 * Qué se hace con la partición de un deportista cuya sesión terminó. Aislado para poder cambiarlo
 * sin tocar la cola (ADR-029 offline §4, confirmado por el responsable el 2026-10-26).
 */
fun interface HealthReportsSessionPolicy {
    suspend fun onSessionEnded(owner: String)
}

/**
 * Detiene la cola de HU042 al cerrar sesión o cambiar de deportista, aplica [policy] a la partición
 * anterior y reanuda la sincronización solo para el deportista autenticado. El estado inicial sin
 * usuario no cancela nada, porque WorkManager puede iniciar el proceso solo para sincronizar.
 */
class HealthReportsCoordinator(
    private val currentUser: StateFlow<SessionUser?>,
    private val hasPending: suspend (owner: String) -> Boolean,
    private val scheduler: TrainingSyncScheduler,
    private val policy: HealthReportsSessionPolicy,
) {
    fun start(scope: CoroutineScope): Job = scope.launch {
        var previous: String? = null
        currentUser.map { user -> user?.userId?.let(HealthOwner::keyOf) }.distinctUntilChanged().collect { owner ->
            val ended = previous?.takeIf { it != owner }
            if (ended != null) {
                scheduler.cancelAll()
                safely { policy.onSessionEnded(ended) }
            }
            if (owner != null && safely { hasPending(owner) } == true) scheduler.schedule(owner)
            previous = owner
        }
    }

    /** Una falla de la fuente local no detiene la coordinación; se reintenta en el próximo cambio de sesión. */
    private suspend fun <T> safely(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
