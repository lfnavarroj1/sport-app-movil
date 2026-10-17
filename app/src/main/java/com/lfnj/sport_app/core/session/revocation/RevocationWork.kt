package com.lfnj.sport_app.core.session.revocation

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Trabajo durable de HU004 CA7: vacía la cola de revocación. No recibe datos de entrada. */
class RevocationWorker(
    context: Context,
    params: WorkerParameters,
    private val queue: RevocationQueue,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (queue.run()) {
        RevocationRun.RETRY -> Result.retry()
        RevocationRun.COMPLETED, RevocationRun.SUSPENDED -> Result.success()
    }
}

/** Crea [RevocationWorker] con la cola del contenedor manual. */
class RevocationWorkerFactory(private val queue: () -> RevocationQueue) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        if (workerClassName == RevocationWorker::class.java.name) RevocationWorker(appContext, workerParameters, queue()) else null
}

/**
 * Trabajo único con conectividad y reintento exponencial (ADR-026 §6). `KEEP` conserva el trabajo
 * ya programado: una sola ejecución procesa todas las entradas de la cola. Tiene una etiqueta
 * propia para que la cancelación de la sincronización de HU032 al cambiar de sesión no lo detenga.
 */
class WorkManagerRevocationScheduler(private val context: Context) : RevocationScheduler {
    override fun schedule() {
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request())
    }

    companion object {
        const val UNIQUE_NAME = "session-revocation"
        const val TAG = "session-revocation"

        fun request(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<RevocationWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RevocationQueue.INITIAL_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .addTag(TAG)
            .build()
    }
}

/**
 * Al abrir la aplicación programa el trabajo si quedaron revocaciones pendientes, por ejemplo si el
 * proceso terminó antes de programarlo. Una falla de la fuente local no detiene el arranque.
 */
class RevocationResumer(private val queue: RevocationQueue, private val scheduler: RevocationScheduler) {
    fun start(scope: CoroutineScope): Job = scope.launch {
        if (queue.hasPending()) scheduler.schedule()
    }
}
