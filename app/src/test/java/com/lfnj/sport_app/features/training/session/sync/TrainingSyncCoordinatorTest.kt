package com.lfnj.sport_app.features.training.session.sync

import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.training.session.model.TrainingOwner
import com.lfnj.sport_app.testing.FakeScheduler
import com.lfnj.sport_app.testing.TrainingFixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-029 offline §4: la sincronización se detiene al cerrar sesión y solo la reanuda el mismo deportista. */
@OptIn(ExperimentalCoroutinesApi::class)
class TrainingSyncCoordinatorTest {
    private val ana = SessionUser(TrainingFixtures.USER_ID, "Ana Deportista", "ana@example.com", ActorType.ATHLETE)
    private val beto = SessionUser(TrainingFixtures.OTHER_USER_ID, "Beto Deportista", "beto@example.com", ActorType.ATHLETE)
    private val currentUser = MutableStateFlow<SessionUser?>(null)
    private val scheduler = FakeScheduler()
    private val pending = mutableSetOf(TrainingOwner.keyOf(TrainingFixtures.USER_ID))

    private fun start(scope: CoroutineScope) =
        TrainingSyncCoordinator(currentUser, { owner -> owner in pending }, scheduler).start(scope)

    @Test
    fun `el arranque sin usuario no cancela el trabajo que inicio el proceso`() = runTest(UnconfinedTestDispatcher()) {
        start(backgroundScope)

        assertEquals(0, scheduler.cancellations)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun `al autenticarse se programa la particion con pendientes y al cerrar sesion se detiene`() = runTest(UnconfinedTestDispatcher()) {
        start(backgroundScope)

        currentUser.value = ana
        assertEquals(listOf(TrainingOwner.keyOf(TrainingFixtures.USER_ID)), scheduler.scheduled)

        currentUser.value = null
        assertEquals(1, scheduler.cancellations)
    }

    @Test
    fun `otro deportista no reanuda una particion ajena`() = runTest(UnconfinedTestDispatcher()) {
        start(backgroundScope)

        currentUser.value = beto

        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun `una falla de la fuente local no detiene la coordinacion`() = runTest(UnconfinedTestDispatcher()) {
        var fail = true
        TrainingSyncCoordinator(currentUser, { if (fail) error("base dañada") else true }, scheduler).start(backgroundScope)

        currentUser.value = ana
        fail = false
        currentUser.value = null
        currentUser.value = ana

        assertEquals(1, scheduler.scheduled.size)
        assertEquals(1, scheduler.cancellations)
    }
}
