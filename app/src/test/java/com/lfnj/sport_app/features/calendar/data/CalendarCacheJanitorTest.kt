package com.lfnj.sport_app.features.calendar.data

import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.calendar.model.CalendarOwner
import com.lfnj.sport_app.testing.TrainingFixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** HU035, ADR-031 offline §5: cerrar sesión elimina la caché; otro deportista no reutiliza la anterior. */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarCacheJanitorTest {
    private val ana = SessionUser(TrainingFixtures.USER_ID, "Ana Deportista", "ana@example.com", ActorType.ATHLETE)
    private val beto = SessionUser(TrainingFixtures.OTHER_USER_ID, "Beto Deportista", "beto@example.com", ActorType.ATHLETE)
    private val anaKey = CalendarOwner.keyOf(ana.userId)
    private val betoKey = CalendarOwner.keyOf(beto.userId)

    private class RecordingStore(private val failing: Boolean = false) : CalendarCacheStore {
        val calls = mutableListOf<String>()
        override suspend fun clear(owner: String) {
            calls += "clear:$owner"
            if (failing) error("disco")
        }
        override suspend fun clearAllExcept(owner: String) {
            calls += "keep:$owner"
            if (failing) error("disco")
        }
    }

    private fun TestScope.started(user: MutableStateFlow<SessionUser?>, store: CalendarCacheStore) =
        CalendarCacheJanitor(user, store).start(TestScope(UnconfinedTestDispatcher(testScheduler)))

    @Test
    fun `al iniciar sesion conserva solo la particion del deportista`() = runTest {
        val user = MutableStateFlow<SessionUser?>(null)
        val store = RecordingStore()
        val job = started(user, store)

        user.value = ana

        assertEquals(listOf("keep:$anaKey"), store.calls)
        job.cancelAndJoin()
    }

    @Test
    fun `al cerrar sesion elimina la cache del deportista`() = runTest {
        val user = MutableStateFlow<SessionUser?>(ana)
        val store = RecordingStore()
        val job = started(user, store)

        user.value = null

        assertEquals(listOf("keep:$anaKey", "clear:$anaKey"), store.calls)
        job.cancelAndJoin()
    }

    @Test
    fun `al cambiar de deportista elimina la cache anterior`() = runTest {
        val user = MutableStateFlow<SessionUser?>(ana)
        val store = RecordingStore()
        val job = started(user, store)

        user.value = beto

        assertEquals(listOf("keep:$anaKey", "clear:$anaKey", "keep:$betoKey"), store.calls)
        job.cancelAndJoin()
    }

    @Test
    fun `una renovacion del mismo deportista no borra nada`() = runTest {
        val user = MutableStateFlow<SessionUser?>(ana)
        val store = RecordingStore()
        val job = started(user, store)

        user.value = ana.copy(fullName = "Ana D.")

        assertEquals(listOf("keep:$anaKey"), store.calls)
        job.cancelAndJoin()
    }

    @Test
    fun `una falla de almacenamiento no detiene la limpieza siguiente`() = runTest {
        val user = MutableStateFlow<SessionUser?>(ana)
        val store = RecordingStore(failing = true)
        val job = started(user, store)

        user.value = null
        user.value = beto

        assertEquals(listOf("keep:$anaKey", "clear:$anaKey", "keep:$betoKey"), store.calls)
        job.cancelAndJoin()
    }
}
