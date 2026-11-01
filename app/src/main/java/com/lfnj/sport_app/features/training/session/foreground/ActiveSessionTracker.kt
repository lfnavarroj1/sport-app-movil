package com.lfnj.sport_app.features.training.session.foreground

import android.content.Context
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.training.session.data.TrainingClock
import com.lfnj.sport_app.features.training.session.model.LocalTrainingSession
import com.lfnj.sport_app.features.training.session.model.TrainingOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/**
 * Sesión `ACTIVE` de HU032 que mantiene vivo el servicio en primer plano. [monotonicStartMillis]
 * existe solo si el inicio ocurrió en este proceso; tras un reinicio el tiempo transcurrido se
 * reconstruye desde [startedAt] (ADR-029, política offline §8).
 */
data class ActiveSession(
    val owner: String,
    val sessionId: String,
    val startedAt: Instant,
    val monotonicStartMillis: Long?,
) {
    fun elapsedMillis(clock: TrainingClock): Long {
        val elapsed = monotonicStartMillis?.let { clock.elapsedRealtimeMillis() - it }
            ?: Duration.between(startedAt, clock.now()).toMillis()
        return elapsed.coerceAtLeast(0)
    }
}

/**
 * Trabajo que acompaña a la sesión activa dentro del servicio en primer plano (HU038; reutilizable
 * por HU036/HU037). Se cancela cuando la sesión termina o cambia.
 */
fun interface ActiveSessionTask {
    suspend fun run(session: ActiveSession)
}

/** Fuente de sesiones de HU032 en solo lectura ([com.lfnj.sport_app.features.training.session.data.TrainingSessionRepository]). */
interface ActiveSessionSource {
    fun observe(owner: String): Flow<List<LocalTrainingSession>>
    fun monotonicStartMillis(sessionId: String): Long?
}

/** Arranca o detiene el servicio en primer plano; la implementación Android vive en [ActiveSessionService]. */
interface ActiveSessionServiceLauncher {
    /** `false` si el sistema no permite iniciarlo ahora (p. ej. la aplicación está en segundo plano). */
    fun start(): Boolean
    fun stop()
}

/**
 * Puntero a la sesión que sigue el servicio: solo `owner_key` (huella) y `session_id`, sin datos de
 * la sesión. Permite que un reinicio del servicio por el sistema, antes de restaurar la sesión de
 * la aplicación, continúe la misma sesión sin duplicar recordatorios.
 */
interface ActiveSessionPointer {
    fun read(): Pair<String, String>?
    fun write(owner: String, sessionId: String)
    fun clear()
}

/** `SharedPreferences` privadas, excluidas de la copia de seguridad y de la transferencia. */
class SharedPreferencesActiveSessionPointer(context: Context) : ActiveSessionPointer {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun read(): Pair<String, String>? {
        val owner = preferences.getString(KEY_OWNER, null) ?: return null
        val sessionId = preferences.getString(KEY_SESSION, null) ?: return null
        return owner to sessionId
    }

    override fun write(owner: String, sessionId: String) {
        preferences.edit().putString(KEY_OWNER, owner).putString(KEY_SESSION, sessionId).apply()
    }

    override fun clear() {
        preferences.edit().clear().apply()
    }

    companion object {
        /** Archivo `sportapp_active_session.xml`. */
        const val FILE_NAME = "sportapp_active_session"
        private const val KEY_OWNER = "owner_key"
        private const val KEY_SESSION = "session_id"
    }
}

/**
 * Decide cuándo corre el servicio en primer plano de la sesión de HU032, sin modificar su flujo
 * ni su almacenamiento: observa la sesión abierta del deportista autenticado y, si el proceso se
 * reinició sin sesión de aplicación, la del puntero guardado. Al cerrar sesión o cambiar de
 * deportista borra el puntero y detiene el servicio.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveSessionTracker(
    private val currentUser: StateFlow<SessionUser?>,
    private val source: ActiveSessionSource,
    private val pointer: ActiveSessionPointer,
    private val launcher: ActiveSessionServiceLauncher,
) {
    private val _active = MutableStateFlow<ActiveSession?>(null)

    /** Sesión que el servicio debe acompañar, o `null`. */
    val active: StateFlow<ActiveSession?> = _active.asStateFlow()

    /** Recuerda si el último intento de arrancar el servicio fue rechazado por el sistema. */
    @Volatile
    private var startPending = false

    fun start(scope: CoroutineScope) = scope.launch {
        var previousOwner: String? = null
        currentUser
            .map { user -> user?.userId?.let(TrainingOwner::keyOf) }
            .distinctUntilChanged()
            .flatMapLatest { owner ->
                if (previousOwner != null && previousOwner != owner) pointerSafely { clear() }
                previousOwner = owner
                val target = owner ?: pointerSafely { read() }?.first
                target?.let { key -> openSession(key, restoredOnly = owner == null) } ?: flowOf(null)
            }
            .distinctUntilChanged()
            .catch { emit(null) }
            .collect(::publish)
    }

    /** Reintenta arrancar el servicio cuando la aplicación vuelve al primer plano. */
    fun onAppForeground() {
        if (startPending && _active.value != null) startPending = !launcher.start()
    }

    private fun openSession(owner: String, restoredOnly: Boolean): Flow<ActiveSession?> =
        source.observe(owner).map { sessions ->
            val pointed = if (restoredOnly) pointerSafely { read() }?.second else null
            sessions.firstOrNull { it.open && (pointed == null || it.sessionId == pointed) }?.let { session ->
                val details = checkNotNull(session.details)
                ActiveSession(owner, session.sessionId, details.startedAt, source.monotonicStartMillis(session.sessionId))
            }
        }

    private fun publish(session: ActiveSession?) {
        val previous = _active.value
        _active.value = session
        if (session != null) {
            pointerSafely { write(session.owner, session.sessionId) }
            if (previous == null || previous.sessionId != session.sessionId || startPending) startPending = !launcher.start()
        } else {
            startPending = false
            // Sin sesión del deportista autenticado se olvida el puntero; un proceso recién iniciado sin
            // sesión de aplicación lo conserva para la próxima restauración.
            if (currentUser.value != null) pointerSafely { clear() }
            if (previous != null) launcher.stop()
        }
    }

    private fun <T> pointerSafely(block: ActiveSessionPointer.() -> T): T? = try {
        pointer.block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
