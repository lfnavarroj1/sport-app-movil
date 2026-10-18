package com.lfnj.sport_app.features.training.metrics.sync

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
import com.lfnj.sport_app.features.training.metrics.data.TrainingMetricCalculationDto
import com.lfnj.sport_app.features.training.metrics.data.TrainingMetricsApi
import com.lfnj.sport_app.features.training.metrics.data.local.MetricCommandEntity
import com.lfnj.sport_app.features.training.metrics.data.local.MetricResultCacheEntity
import com.lfnj.sport_app.features.training.metrics.data.local.TrainingMetricsCodec
import com.lfnj.sport_app.features.training.metrics.data.local.TrainingMetricsDao
import com.lfnj.sport_app.features.training.metrics.model.MetricResultsCache
import com.lfnj.sport_app.features.training.metrics.model.MetricSyncFailure
import com.lfnj.sport_app.features.training.session.data.TrainingSyncScheduler
import com.lfnj.sport_app.features.training.session.data.local.TransactionRunner
import com.lfnj.sport_app.features.training.session.model.TrainingOwner
import com.lfnj.sport_app.features.training.session.sync.SyncRun
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Sincroniza la cola de cálculos de HU034 de un deportista (ADR-030, «Política móvil offline»):
 *
 * - envía cada cálculo pendiente con su `calculation_id`, su `Idempotency-Key` y su cuerpo cifrado,
 *   por lo que un reintento tras una respuesta perdida no duplica el resultado (CA4, CA8);
 * - solo reintenta pérdida de red, renovación no disponible y `502`/`503`/`504`; un `4xx` deja el
 *   cálculo en `SYNC_FAILED` con el campo y la causa de la validación (offline §4);
 * - un cálculo con más de 30 días vence y queda `SYNC_FAILED`; nunca se presenta como confirmado;
 * - el resultado confirmado (`201`, o `200` si el mismo `calculation_id` ya existía) se agrega al
 *   historial local mientras la proyección converge; el móvil no calcula nada;
 * - usa la sesión vigente del deportista dueño de la partición y nunca guarda tokens.
 */
class MetricsSyncEngine(
    private val dao: TrainingMetricsDao,
    private val transactions: TransactionRunner,
    private val codec: TrainingMetricsCodec,
    private val api: TrainingMetricsApi?,
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
        for (command in dao.pendingCommands(owner)) {
            val body = codec.decodeRequest(command)
            if (body == null) {
                dao.replaceCommand(command.failed(MetricSyncFailure.UNREADABLE.name))
                continue
            }
            dao.markAttempt(command.calculationId, clock.millis())
            val outcome = sessionManager.authorized { auth -> apiCall(json) { api.calculate(auth, command.idempotencyKey, body) } }
            when (outcome) {
                AuthorizedResult.SignedOut -> return SyncRun.SUSPENDED
                AuthorizedResult.RenewalUnavailable -> return SyncRun.RETRY
                is AuthorizedResult.Completed -> when (val result = outcome.result) {
                    is ApiResult.Success -> confirm(owner, command, result.value)
                    ApiResult.NoConnection, ApiResult.Timeout -> return SyncRun.RETRY
                    ApiResult.NotConfigured -> return SyncRun.SUSPENDED
                    ApiResult.InvalidResponse -> dao.replaceCommand(command.failed(MetricSyncFailure.INVALID_RESPONSE.name))
                    is ApiResult.HttpFailure ->
                        if (result.status in RETRYABLE_STATUSES) return SyncRun.RETRY else reject(command, result)
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
        sessionManager.currentUser.value?.let { return Identity.Owner(TrainingOwner.keyOf(it.userId)) }
        return when (val restored = sessionManager.restore()) {
            is RestoreOutcome.Restored -> Identity.Owner(TrainingOwner.keyOf(restored.user.userId))
            RestoreOutcome.NoConnection, is RestoreOutcome.Unavailable -> Identity.Unreachable
            RestoreOutcome.NoSession, RestoreOutcome.Expired, RestoreOutcome.UnsupportedActor, RestoreOutcome.NotConfigured ->
                Identity.SignedOut
        }
    }

    private suspend fun confirm(owner: String, command: MetricCommandEntity, response: TrainingMetricCalculationDto) {
        val result = response.toModel()?.takeIf { it.calculationId == command.calculationId }
        if (result == null) {
            dao.replaceCommand(command.failed(MetricSyncFailure.INVALID_RESPONSE.name))
            return
        }
        transactions.run {
            val cache = dao.results(owner)?.let(codec::decodeResults) ?: MetricResultsCache.EMPTY
            val awaiting = cache.awaiting.filter { it.calculationId != result.calculationId } + result
            dao.replaceResults(MetricResultCacheEntity(owner, codec.encodeResults(cache.copy(awaiting = awaiting))))
            dao.deleteCommand(owner, command.calculationId)
        }
    }

    private suspend fun reject(command: MetricCommandEntity, failure: ApiResult.HttpFailure) {
        val reason = when {
            failure.status == 422 -> MetricSyncFailure.VALIDATION
            failure.status == 403 && failure.code == "capability_required" -> MetricSyncFailure.CAPABILITY_REQUIRED
            failure.status == 403 -> MetricSyncFailure.PERMISSION_REQUIRED
            failure.status == 409 -> MetricSyncFailure.CONFLICT
            else -> MetricSyncFailure.REJECTED
        }
        val detail = failure.fieldIssues.firstOrNull()?.takeIf { reason == MetricSyncFailure.VALIDATION }
        dao.replaceCommand(command.failed(reason.name, detail?.field, detail?.issue))
    }

    /** Vence los pendientes con más de 30 días en todas las particiones del dispositivo. */
    private suspend fun expirePending() {
        val threshold = clock.millis() - RETENTION.toMillis()
        dao.pendingCreatedBefore(threshold).forEach { dao.replaceCommand(it.failed(MetricSyncFailure.EXPIRED.name)) }
    }

    companion object {
        /** Vencimiento de los cálculos pendientes (ADR-030, política offline §4). */
        val RETENTION: Duration = Duration.ofDays(30)
        private val RETRYABLE_STATUSES = setOf(502, 503, 504)
    }
}

/** Trabajo durable de HU034: ejecuta [MetricsSyncEngine] para la partición recibida. */
class MetricsSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val engine: MetricsSyncEngine,
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

/** Crea [MetricsSyncWorker] con las dependencias del contenedor manual. */
class MetricsSyncWorkerFactory(private val engine: () -> MetricsSyncEngine) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        if (workerClassName == MetricsSyncWorker::class.java.name) MetricsSyncWorker(appContext, workerParameters, engine()) else null
}

/**
 * Trabajo único por partición con conectividad y reintento exponencial (ADR-030 offline §3). Los
 * datos de entrada solo contienen la huella del deportista; el nombre del trabajo no lleva datos.
 * Tiene una etiqueta propia: [cancelAll] detiene solo la sincronización de HU034.
 */
class WorkManagerMetricsSyncScheduler(private val context: Context) : TrainingSyncScheduler {
    override fun schedule(owner: String) {
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(owner), ExistingWorkPolicy.APPEND_OR_REPLACE, request(owner))
    }

    override fun cancelAll() {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
    }

    companion object {
        const val TAG = "training-metrics-sync"
        private const val INITIAL_BACKOFF_SECONDS = 30L

        fun uniqueName(owner: String) = "$TAG-$owner"

        fun request(owner: String): OneTimeWorkRequest = OneTimeWorkRequestBuilder<MetricsSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(workDataOf(MetricsSyncWorker.KEY_OWNER to owner))
            .addTag(TAG)
            .build()
    }
}
