package com.lfnj.sport_app.features.training.session.sync

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
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.training.session.data.TrainingSyncScheduler
import com.lfnj.sport_app.features.training.session.model.TrainingOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Trabajo durable de HU032: ejecuta [TrainingSyncEngine] para la partición recibida. */
class TrainingSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val engine: TrainingSyncEngine,
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

/** Crea [TrainingSyncWorker] con las dependencias del contenedor manual. */
class TrainingSyncWorkerFactory(private val engine: () -> TrainingSyncEngine) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        if (workerClassName == TrainingSyncWorker::class.java.name) {
            TrainingSyncWorker(appContext, workerParameters, engine())
        } else {
            null
        }
}

/**
 * Trabajo único por partición con conectividad y reintento exponencial (ADR-029 offline §3).
 * `APPEND_OR_REPLACE` encadena una nueva ejecución detrás de la vigente para no perder un comando
 * guardado mientras otra ejecución terminaba. Los datos de entrada solo contienen la huella del deportista.
 */
class WorkManagerTrainingSyncScheduler(private val context: Context) : TrainingSyncScheduler {
    override fun schedule(owner: String) {
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(owner), ExistingWorkPolicy.APPEND_OR_REPLACE, request(owner))
    }

    override fun cancelAll() {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
    }

    companion object {
        const val TAG = "training-session-sync"
        private const val INITIAL_BACKOFF_SECONDS = 30L

        fun uniqueName(owner: String) = "$TAG-$owner"

        fun request(owner: String): OneTimeWorkRequest = OneTimeWorkRequestBuilder<TrainingSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(workDataOf(TrainingSyncWorker.KEY_OWNER to owner))
            .addTag(TAG)
            .build()
    }
}

/**
 * Enlaza la sincronización con la sesión de la aplicación (ADR-029 offline §4): al cerrar o perder
 * la sesión detiene todo trabajo; al autenticarse un deportista programa su partición si tiene
 * comandos pendientes. El estado inicial sin usuario no cancela nada, porque WorkManager puede
 * iniciar el proceso solo para sincronizar.
 */
class TrainingSyncCoordinator(
    private val currentUser: StateFlow<SessionUser?>,
    private val hasPendingCommands: suspend (owner: String) -> Boolean,
    private val scheduler: TrainingSyncScheduler,
) {
    fun start(scope: CoroutineScope): Job = scope.launch {
        var previous: String? = null
        currentUser.map { user -> user?.userId?.let(TrainingOwner::keyOf) }.distinctUntilChanged().collect { owner ->
            if (previous != null && owner != previous) scheduler.cancelAll()
            if (owner != null && pending(owner)) scheduler.schedule(owner)
            previous = owner
        }
    }

    /** Una falla de la fuente local no detiene la coordinación; se reintenta en el próximo cambio de sesión. */
    private suspend fun pending(owner: String): Boolean = try {
        hasPendingCommands(owner)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }
}
