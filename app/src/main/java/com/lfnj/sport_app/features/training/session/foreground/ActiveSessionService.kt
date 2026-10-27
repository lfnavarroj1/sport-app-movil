package com.lfnj.sport_app.features.training.session.foreground

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lfnj.sport_app.R
import com.lfnj.sport_app.SportApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano de la sesión de entrenamiento en curso (HU032; creado por HU038 y
 * reutilizable por HU036/HU037). Mantiene una notificación persistente con el cronómetro en el
 * canal `training_session` y ejecuta las [ActiveSessionTask] mientras exista una sesión `ACTIVE`.
 *
 * Tipo `specialUse`: el tipo `health` exigiría permisos de sensores fuera del alcance de HU038.
 */
class ActiveSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var running: Job? = null
    private lateinit var wakeLock: SessionWakeLock

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wakeLock = SessionWakeLock(PowerWakeLockHandle(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val (tracker, tasks) = dependencies(applicationContext)
        if (!enterForeground(tracker.active.value?.startedAt?.toEpochMilli())) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running?.isActive != true) {
            val runner = ActiveSessionRunner(tasks, wakeLock)
            running = scope.launch {
                runner.follow(
                    active = tracker.active,
                    onSession = { session -> notify(session.startedAt.toEpochMilli()) },
                    onEnded = {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    },
                )
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock.release()
        super.onDestroy()
    }

    private fun enterForeground(startedAtMillis: Long?): Boolean = try {
        ensureChannel(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification(startedAtMillis), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification(startedAtMillis))
        }
        true
    } catch (_: IllegalStateException) {
        // Android 12+: ForegroundServiceStartNotAllowedException; se reintenta al volver al primer plano.
        false
    } catch (_: SecurityException) {
        false
    }

    private fun notify(startedAtMillis: Long) {
        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(startedAtMillis))
        } catch (_: SecurityException) {
            // Sin permiso de notificaciones el servicio sigue corriendo; el sistema lo lista en sus ajustes.
        }
    }

    private fun notification(startedAtMillis: Long?): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.training_session_ongoing_title))
            .setContentText(getString(R.string.training_session_ongoing_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                if (startedAtMillis != null) {
                    setWhen(startedAtMillis)
                    setShowWhen(true)
                    setUsesChronometer(true)
                }
                open?.let(::setContentIntent)
            }
            .build()
    }

    companion object {
        /** Canal de la notificación persistente de la sesión en curso. */
        const val CHANNEL_ID = "training_session"
        const val NOTIFICATION_ID = 32

        /**
         * Dependencias del servicio: por defecto, las del contenedor de la aplicación (como las fábricas
         * de trabajos de WorkManager). Android instancia el servicio, así que las pruebas instrumentadas
         * sustituyen este proveedor (p. ej. por un motor con reloj acelerado); producción no lo cambia.
         */
        @VisibleForTesting
        @Volatile
        internal var dependencies: (Context) -> ActiveSessionDependencies = { context ->
            (context.applicationContext as SportApp).container.let { ActiveSessionDependencies(it.activeSessionTracker, it.activeSessionTasks) }
        }

        /** Crea el canal; es idempotente y respeta los ajustes que el usuario haya cambiado. */
        fun ensureChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.training_session_channel), NotificationManager.IMPORTANCE_LOW)
                    .apply { description = context.getString(R.string.training_session_channel_description) }
            )
        }
    }
}

/** Sesión a seguir y tareas que la acompañan dentro del servicio. */
data class ActiveSessionDependencies(val tracker: ActiveSessionTracker, val tasks: List<ActiveSessionTask>)

/**
 * Lógica del servicio, independiente de Android: por cada sesión activa toma el wake lock y lo
 * renueva mientras sigue activa, ejecuta las tareas y, al terminar o cambiar la sesión, cancela las
 * tareas y libera el wake lock. Sin sesión invoca `onEnded`.
 */
class ActiveSessionRunner(private val tasks: List<ActiveSessionTask>, private val wakeLock: SessionWakeLock) {

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun follow(active: Flow<ActiveSession?>, onSession: (ActiveSession) -> Unit, onEnded: () -> Unit) {
        try {
            active.distinctUntilChanged().collectLatest { session ->
                if (session == null) {
                    wakeLock.release()
                    onEnded()
                    return@collectLatest
                }
                onSession(session)
                try {
                    coroutineScope {
                        launch {
                            while (true) {
                                wakeLock.acquire()
                                delay(SessionWakeLock.RENEW_MILLIS)
                            }
                        }
                        tasks.forEach { task -> launch { runIsolated(task, session) } }
                    }
                } finally {
                    wakeLock.release()
                }
            }
        } finally {
            wakeLock.release()
        }
    }

    /** Una tarea que falla no detiene el servicio ni las demás tareas de la sesión. */
    private suspend fun runIsolated(task: ActiveSessionTask, session: ActiveSession) {
        try {
            task.run(session)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Sin detalles: el registro de errores no debe exponer datos de la sesión.
        }
    }
}

/** Arranque real del servicio; se ignora si Android no lo permite en este momento. */
class AndroidActiveSessionServiceLauncher(context: Context) : ActiveSessionServiceLauncher {
    private val context = context.applicationContext

    override fun start(): Boolean = try {
        ContextCompat.startForegroundService(context, Intent(context, ActiveSessionService::class.java))
        true
    } catch (_: IllegalStateException) {
        // Android 12+: no se permite iniciar un servicio en primer plano desde segundo plano.
        false
    } catch (_: SecurityException) {
        false
    }

    override fun stop() {
        context.stopService(Intent(context, ActiveSessionService::class.java))
    }
}

/** Abstracción del wake lock del sistema para probar su liberación en la JVM. */
interface WakeLockHandle {
    val held: Boolean
    fun acquire(timeoutMillis: Long)
    fun release()
}

private class PowerWakeLockHandle(context: Context) : WakeLockHandle {
    private val lock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sportapp:training_session")
        .apply { setReferenceCounted(false) }

    override val held: Boolean get() = lock.isHeld

    override fun acquire(timeoutMillis: Long) = lock.acquire(timeoutMillis)

    override fun release() {
        if (lock.isHeld) lock.release()
    }
}

/**
 * `PARTIAL_WAKE_LOCK` de la sesión (HA007 < 3 s con la pantalla apagada): se toma con
 * [TIMEOUT_MILLIS] como máximo de seguridad y se renueva cada [RENEW_MILLIS] solo mientras la sesión
 * sigue activa; [release] es idempotente. `WAKE_LOCK` llega al manifiesto fusionado desde WorkManager.
 */
class SessionWakeLock(private val handle: WakeLockHandle) {
    val held: Boolean get() = handle.held

    fun acquire() = handle.acquire(TIMEOUT_MILLIS)

    fun release() = handle.release()

    companion object {
        const val TIMEOUT_MILLIS = 10 * 60_000L
        const val RENEW_MILLIS = 5 * 60_000L
    }
}
