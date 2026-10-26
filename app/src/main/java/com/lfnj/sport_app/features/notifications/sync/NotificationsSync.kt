package com.lfnj.sport_app.features.notifications.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.lfnj.sport_app.core.session.RestoreOutcome
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.notifications.data.InboxGateway
import com.lfnj.sport_app.features.notifications.data.InboxRefresh
import com.lfnj.sport_app.features.notifications.model.NotificationsOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Resultado de una corrida de la sincronización periódica. */
enum class InboxSyncRun {
    COMPLETED,
    /** Falla transitoria (red, `503`/`504`, renovación no disponible): WorkManager reintenta con espera exponencial. */
    RETRY,
    /** Sin sesión de deportista: no hay bandeja que sincronizar hasta un nuevo inicio de sesión. */
    SUSPENDED,
}

/**
 * Sincroniza la bandeja del deportista autenticado (ADR-045 §1): consulta la primera página y el
 * repositorio anuncia en el sistema los elementos nuevos. Tras un reinicio del proceso recupera la
 * sesión con la credencial cifrada; nunca guarda tokens ni datos en los parámetros del trabajo.
 */
class NotificationsSyncEngine(
    private val sessionManager: SessionManager,
    private val inbox: InboxGateway,
) {
    suspend fun run(): InboxSyncRun {
        val user = sessionManager.currentUser.value ?: when (val restored = sessionManager.restore()) {
            is RestoreOutcome.Restored -> restored.user
            RestoreOutcome.NoConnection, is RestoreOutcome.Unavailable -> return InboxSyncRun.RETRY
            RestoreOutcome.NoSession, RestoreOutcome.Expired, RestoreOutcome.UnsupportedActor, RestoreOutcome.NotConfigured ->
                return InboxSyncRun.SUSPENDED
        }
        return when (inbox.refresh(NotificationsOwner.keyOf(user.userId))) {
            InboxRefresh.Updated -> InboxSyncRun.COMPLETED
            InboxRefresh.NoConnection, is InboxRefresh.Unavailable -> InboxSyncRun.RETRY
            InboxRefresh.SignedOut -> InboxSyncRun.SUSPENDED
            // Respuestas no recuperables por reintento: se espera a la próxima corrida periódica.
            InboxRefresh.PermissionRequired, is InboxRefresh.InvalidRequest, InboxRefresh.InvalidResponse, InboxRefresh.StorageFailed ->
                InboxSyncRun.COMPLETED
        }
    }
}

/** Trabajo periódico de HU039: ejecuta [NotificationsSyncEngine]. */
class NotificationsSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val engine: NotificationsSyncEngine,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (engine.run()) {
        InboxSyncRun.RETRY -> if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        InboxSyncRun.COMPLETED, InboxSyncRun.SUSPENDED -> Result.success()
    }

    companion object {
        /** Reintento acotado dentro de un periodo; el siguiente periodo vuelve a intentarlo. */
        const val MAX_RETRIES = 3
    }
}

/** Crea [NotificationsSyncWorker] con las dependencias del contenedor manual. */
class NotificationsSyncWorkerFactory(private val engine: () -> NotificationsSyncEngine) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        if (workerClassName == NotificationsSyncWorker::class.java.name) NotificationsSyncWorker(appContext, workerParameters, engine()) else null
}

/** Programa o cancela la sincronización periódica de la bandeja. */
interface InboxSyncScheduler {
    fun schedule()
    fun cancel()
}

/** Trabajo periódico único cada 15 minutos con conectividad (ADR-045 §1); sin datos de entrada. */
class WorkManagerInboxSyncScheduler(private val context: Context) : InboxSyncScheduler {
    override fun schedule() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request())
    }

    override fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }

    companion object {
        const val UNIQUE_NAME = "notifications-inbox-sync"
        const val TAG = "notifications-inbox-sync"
        const val INTERVAL_MINUTES = 15L
        private const val INITIAL_BACKOFF_SECONDS = 30L

        fun request(): PeriodicWorkRequest = PeriodicWorkRequestBuilder<NotificationsSyncWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
    }
}

/**
 * Activa la sincronización periódica mientras haya un deportista autenticado y la detiene al
 * terminar su sesión. El estado inicial sin sesión no cancela: tras un reinicio del proceso el
 * trabajo recupera la sesión por sí mismo.
 */
class InboxSyncCoordinator(
    private val currentUser: StateFlow<SessionUser?>,
    private val scheduler: InboxSyncScheduler,
) {
    fun start(scope: CoroutineScope): Job = scope.launch {
        var previous: String? = null
        currentUser.map { user -> user?.userId?.let(NotificationsOwner::keyOf) }.distinctUntilChanged().collect { owner ->
            try {
                if (previous != null && owner == null) scheduler.cancel()
                if (owner != null) scheduler.schedule()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // WorkManager no disponible: se reintenta en el próximo cambio de sesión.
            }
            previous = owner
        }
    }
}
