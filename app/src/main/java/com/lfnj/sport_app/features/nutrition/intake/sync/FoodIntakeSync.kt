package com.lfnj.sport_app.features.nutrition.intake.sync

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
import com.lfnj.sport_app.features.nutrition.intake.data.FoodIntakeApi
import com.lfnj.sport_app.features.nutrition.intake.data.FoodIntakeResponseDto
import com.lfnj.sport_app.features.nutrition.intake.data.RegisterFoodIntakeRequestDto
import com.lfnj.sport_app.features.nutrition.intake.data.local.FoodIntakeCodec
import com.lfnj.sport_app.features.nutrition.intake.data.local.FoodIntakeDao
import com.lfnj.sport_app.features.nutrition.intake.data.local.FoodIntakeEntity
import com.lfnj.sport_app.features.nutrition.intake.model.FoodOwner
import com.lfnj.sport_app.features.nutrition.intake.model.FoodSyncFailure
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
 * Sincroniza la cola de consumos de un deportista (ADR-037 HU043 §5, cola como HU034 con la política
 * de ADR-029):
 *
 * - envía cada consumo pendiente, en orden de captura, con su `intake_id`, su `Idempotency-Key` y su
 *   cuerpo cifrado, así que un reintento tras una respuesta perdida no lo duplica (CA3, CA5);
 * - solo reintenta pérdida de red, renovación no disponible y `502`/`503`/`504`; un `4xx` deja el
 *   consumo en `SYNC_FAILED` con una causa explicable;
 * - solo un `201` que corresponde al consumo enviado lo marca `SYNCED`; el contrato devuelve `201`
 *   también al repetir el mismo `intake_id` con los mismos datos. Un `409 intake_id_reused`
 *   significa datos distintos y nunca se presenta como confirmado;
 * - un consumo con más de 30 días vence y queda `SYNC_FAILED`;
 * - usa la sesión vigente del deportista dueño de la partición y nunca guarda tokens.
 */
class FoodIntakeSyncEngine(
    private val dao: FoodIntakeDao,
    private val transactions: TransactionRunner,
    private val codec: FoodIntakeCodec,
    private val api: FoodIntakeApi?,
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
        for (intake in dao.pendingIntakes(owner)) {
            val body = codec.decodeRequest(intake)
            if (body == null) {
                dao.replaceIntake(intake.failed(FoodSyncFailure.UNREADABLE.name))
                continue
            }
            dao.markAttempt(intake.intakeId, clock.millis())
            val outcome = sessionManager.authorized { auth -> apiCall(json) { api.register(auth, intake.idempotencyKey, body) } }
            when (outcome) {
                AuthorizedResult.SignedOut -> return SyncRun.SUSPENDED
                AuthorizedResult.RenewalUnavailable -> return SyncRun.RETRY
                is AuthorizedResult.Completed -> when (val result = outcome.result) {
                    is ApiResult.Success -> confirm(owner, intake, body, result.value)
                    ApiResult.NoConnection, ApiResult.Timeout -> return SyncRun.RETRY
                    ApiResult.NotConfigured -> return SyncRun.SUSPENDED
                    ApiResult.InvalidResponse -> dao.replaceIntake(intake.failed(FoodSyncFailure.INVALID_RESPONSE.name))
                    is ApiResult.HttpFailure ->
                        if (result.status in RETRYABLE_STATUSES) return SyncRun.RETRY else reject(intake, result)
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
        sessionManager.currentUser.value?.let { return Identity.Owner(FoodOwner.keyOf(it.userId)) }
        return when (val restored = sessionManager.restore()) {
            is RestoreOutcome.Restored -> Identity.Owner(FoodOwner.keyOf(restored.user.userId))
            RestoreOutcome.NoConnection, is RestoreOutcome.Unavailable -> Identity.Unreachable
            RestoreOutcome.NoSession, RestoreOutcome.Expired, RestoreOutcome.UnsupportedActor, RestoreOutcome.NotConfigured ->
                Identity.SignedOut
        }
    }

    private suspend fun confirm(owner: String, intake: FoodIntakeEntity, body: RegisterFoodIntakeRequestDto, response: FoodIntakeResponseDto) {
        val registeredAt = response.confirms(body)
        if (registeredAt == null) {
            dao.replaceIntake(intake.failed(FoodSyncFailure.INVALID_RESPONSE.name))
            return
        }
        transactions.run {
            // Solo se confirma si sigue en la partición (no se retiró ni se limpió durante el envío).
            if (dao.intake(owner, intake.intakeId) != null) {
                dao.replaceIntake(intake.synced(codec.encodeIntake(body, codec.labelKeys(intake), registeredAt)))
            }
        }
    }

    private suspend fun reject(intake: FoodIntakeEntity, failure: ApiResult.HttpFailure) {
        val issues = failure.fieldIssues
        val reason = when {
            failure.status == 422 && issues.any { it.issue == ISSUE_TOO_OLD } -> FoodSyncFailure.EXPIRED
            failure.status == 422 && issues.any { it.issue == ISSUE_UNIT_NOT_ALLOWED || (it.issue == ISSUE_UNKNOWN_VALUE && it.field.orEmpty().contains(FIELD_FOOD_CODE)) } ->
                FoodSyncFailure.CATALOG_CHANGED
            failure.status == 422 -> FoodSyncFailure.VALIDATION
            failure.status == 403 -> FoodSyncFailure.PERMISSION_REQUIRED
            failure.status == 409 -> FoodSyncFailure.CONFLICT
            else -> FoodSyncFailure.REJECTED
        }
        val detail = issues.firstOrNull()?.takeIf { reason == FoodSyncFailure.VALIDATION || reason == FoodSyncFailure.CATALOG_CHANGED }
        dao.replaceIntake(intake.failed(reason.name, detail?.field, detail?.issue))
    }

    /** Vence los pendientes con más de 30 días en todas las particiones del dispositivo. */
    private suspend fun expirePending() {
        val threshold = clock.millis() - RETENTION.toMillis()
        dao.pendingCreatedBefore(threshold).forEach { dao.replaceIntake(it.failed(FoodSyncFailure.EXPIRED.name)) }
    }

    companion object {
        /** Vencimiento de los consumos pendientes (30 días, ADR-029 offline §6). */
        val RETENTION: Duration = Duration.ofDays(30)
        private val RETRYABLE_STATUSES = setOf(502, 503, 504)
        private const val ISSUE_TOO_OLD = "too_old"
        private const val ISSUE_UNIT_NOT_ALLOWED = "unit_not_allowed"
        private const val ISSUE_UNKNOWN_VALUE = "unknown_value"
        private const val FIELD_FOOD_CODE = "food_code"
    }
}

/** Trabajo durable de HU043: ejecuta [FoodIntakeSyncEngine] para la partición recibida. */
class FoodIntakeSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val engine: FoodIntakeSyncEngine,
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

/** Crea [FoodIntakeSyncWorker] con las dependencias del contenedor manual; `null` para los demás trabajos. */
class FoodIntakeSyncWorkerFactory(private val engine: () -> FoodIntakeSyncEngine) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        if (handles(workerClassName)) FoodIntakeSyncWorker(appContext, workerParameters, engine()) else null

    companion object {
        /** Solo crea el trabajo de HU043; los de HU032, HU034, HU004, HU039 y HU042 siguen en sus fábricas. */
        fun handles(workerClassName: String): Boolean = workerClassName == FoodIntakeSyncWorker::class.java.name
    }
}

/**
 * Trabajo único por partición con conectividad y reintento exponencial. Los datos de entrada solo
 * contienen la huella del deportista. Tiene una etiqueta propia: [cancelAll] detiene solo HU043.
 */
class WorkManagerFoodIntakeSyncScheduler(private val context: Context) : TrainingSyncScheduler {
    override fun schedule(owner: String) {
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(owner), ExistingWorkPolicy.APPEND_OR_REPLACE, request(owner))
    }

    override fun cancelAll() {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
    }

    companion object {
        const val TAG = "food-intakes-sync"
        private const val INITIAL_BACKOFF_SECONDS = 30L

        fun uniqueName(owner: String) = "$TAG-$owner"

        fun request(owner: String): OneTimeWorkRequest = OneTimeWorkRequestBuilder<FoodIntakeSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(workDataOf(FoodIntakeSyncWorker.KEY_OWNER to owner))
            .addTag(TAG)
            .build()
    }
}

/** Qué se hace con la partición de un deportista cuya sesión terminó (ADR-029 offline §4). */
fun interface FoodIntakeSessionPolicy {
    suspend fun onSessionEnded(owner: String)
}

/**
 * Detiene la cola de HU043 al cerrar sesión o cambiar de deportista, aplica [policy] a la partición
 * anterior y reanuda la sincronización solo para el deportista autenticado. El estado inicial sin
 * usuario no cancela nada, porque WorkManager puede iniciar el proceso solo para sincronizar.
 */
class FoodIntakeCoordinator(
    private val currentUser: StateFlow<SessionUser?>,
    private val hasPending: suspend (owner: String) -> Boolean,
    private val scheduler: TrainingSyncScheduler,
    private val policy: FoodIntakeSessionPolicy,
) {
    fun start(scope: CoroutineScope): Job = scope.launch {
        var previous: String? = null
        currentUser.map { user -> user?.userId?.let(FoodOwner::keyOf) }.distinctUntilChanged().collect { owner ->
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
