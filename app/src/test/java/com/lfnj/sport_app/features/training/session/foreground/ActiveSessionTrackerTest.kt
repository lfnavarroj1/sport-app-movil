package com.lfnj.sport_app.features.training.session.foreground

import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.training.session.model.LocalTrainingSession
import com.lfnj.sport_app.features.training.session.model.SyncState
import com.lfnj.sport_app.features.training.session.model.TrainingOwner
import com.lfnj.sport_app.features.training.session.model.TrainingSessionDetails
import com.lfnj.sport_app.features.training.session.model.TrainingSport
import com.lfnj.sport_app.testing.TrainingFixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** HU038: el servicio en primer plano sigue la sesión `ACTIVE` de HU032 sin modificarla. */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveSessionTrackerTest {
    private val owner = TrainingOwner.keyOf(TrainingFixtures.USER_ID)
    private val ana = SessionUser(TrainingFixtures.USER_ID, "Ana", "ana@example.com", ActorType.ATHLETE)
    private val other = SessionUser(TrainingFixtures.OTHER_USER_ID, "Otra", "otra@example.com", ActorType.ATHLETE)

    private class FakeSource : ActiveSessionSource {
        val sessions = MutableStateFlow<Map<String, List<LocalTrainingSession>>>(emptyMap())
        override fun observe(owner: String): Flow<List<LocalTrainingSession>> = sessions.map { it[owner].orEmpty() }
        override fun monotonicStartMillis(sessionId: String): Long? = if (sessionId == "s-1") 42L else null
    }

    private class FakePointer(var value: Pair<String, String>? = null) : ActiveSessionPointer {
        override fun read() = value
        override fun write(owner: String, sessionId: String) {
            value = owner to sessionId
        }
        override fun clear() {
            value = null
        }
    }

    private class FakeLauncher(var allowed: Boolean = true) : ActiveSessionServiceLauncher {
        var starts = 0
        var stops = 0
        override fun start(): Boolean {
            starts++
            return allowed
        }
        override fun stop() {
            stops++
        }
    }

    private fun session(id: String, finished: Boolean = false, state: SyncState = SyncState.LOCAL) = LocalTrainingSession(
        id, state, null,
        TrainingSessionDetails(TrainingSport.RUNNING, null, START, if (finished) START.plusSeconds(600) else null, null),
    )

    private val user = MutableStateFlow<SessionUser?>(null)
    private val source = FakeSource()
    private val pointer = FakePointer()
    private val launcher = FakeLauncher()

    private fun tracker() = ActiveSessionTracker(user, source, pointer, launcher)

    @Test
    fun `una sesion abierta del deportista arranca el servicio y guarda solo el puntero`() = runTest {
        val tracker = tracker()
        val job = tracker.start(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        user.value = ana
        source.sessions.value = mapOf(owner to listOf(session("s-1")))

        assertEquals(ActiveSession(owner, "s-1", START, 42L), tracker.active.value)
        assertEquals(1, launcher.starts)
        assertEquals(owner to "s-1", pointer.value)
        job.cancelAndJoin()
    }

    @Test
    fun `al finalizar la sesion se detiene el servicio y se borra el puntero`() = runTest {
        val tracker = tracker()
        val job = tracker.start(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        user.value = ana
        source.sessions.value = mapOf(owner to listOf(session("s-1")))

        source.sessions.value = mapOf(owner to listOf(session("s-1", finished = true)))

        assertNull(tracker.active.value)
        assertEquals(1, launcher.stops)
        assertNull(pointer.value)
        job.cancelAndJoin()
    }

    @Test
    fun `una sesion rechazada no mantiene el servicio`() = runTest {
        val tracker = tracker()
        val job = tracker.start(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        user.value = ana
        source.sessions.value = mapOf(owner to listOf(session("s-1", state = SyncState.SYNC_FAILED)))

        assertNull(tracker.active.value)
        assertEquals(0, launcher.starts)
        job.cancelAndJoin()
    }

    @Test
    fun `al cerrar sesion o cambiar de deportista se borra el puntero y se detiene`() = runTest {
        val tracker = tracker()
        val job = tracker.start(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        user.value = ana
        source.sessions.value = mapOf(owner to listOf(session("s-1")))

        user.value = null

        assertNull(tracker.active.value)
        assertNull(pointer.value)
        assertEquals(1, launcher.stops)

        user.value = other
        assertNull(tracker.active.value)
        job.cancelAndJoin()
    }

    @Test
    fun `tras reiniciar el proceso sin sesion de aplicacion continua la sesion del puntero`() = runTest {
        pointer.value = owner to "s-2"
        source.sessions.value = mapOf(owner to listOf(session("s-2")))
        val tracker = tracker()
        val job = tracker.start(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        val active = tracker.active.value
        assertEquals("s-2", active?.sessionId)
        assertNull("sin marca monotónica: el tiempo se reconstruye desde started_at", active?.monotonicStartMillis)
        assertEquals(1, launcher.starts)

        // Al restaurar la sesión del mismo deportista no se reinicia el servicio.
        user.value = ana
        assertEquals("s-2", tracker.active.value?.sessionId)
        assertEquals(1, launcher.starts)
        job.cancelAndJoin()
    }

    @Test
    fun `el puntero de una sesion que ya no esta abierta no arranca nada`() = runTest {
        pointer.value = owner to "s-9"
        source.sessions.value = mapOf(owner to listOf(session("s-2")))
        val tracker = tracker()
        val job = tracker.start(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        assertNull(tracker.active.value)
        assertEquals(0, launcher.starts)
        job.cancelAndJoin()
    }

    @Test
    fun `si Android no permite iniciar en segundo plano se reintenta al volver al primer plano`() = runTest {
        launcher.allowed = false
        val tracker = tracker()
        val job = tracker.start(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        user.value = ana
        source.sessions.value = mapOf(owner to listOf(session("s-1")))
        assertEquals(1, launcher.starts)

        launcher.allowed = true
        tracker.onAppForeground()
        tracker.onAppForeground()

        assertEquals(2, launcher.starts)
        job.cancelAndJoin()
    }

    private companion object {
        val START: Instant = Instant.parse("2026-10-26T13:00:00Z")
    }
}
