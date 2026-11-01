package com.lfnj.sport_app.features.health.sync

import com.lfnj.sport_app.core.session.revocation.RevocationWorker
import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.health.model.HealthOwner
import com.lfnj.sport_app.features.notifications.sync.NotificationsSyncWorker
import com.lfnj.sport_app.features.training.metrics.sync.MetricsSyncWorker
import com.lfnj.sport_app.features.training.session.sync.TrainingSyncWorker
import com.lfnj.sport_app.testing.FakeScheduler
import com.lfnj.sport_app.testing.TrainingFixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** HU042: la cola se detiene al cerrar sesión y solo la reanuda el mismo deportista (ADR-029 offline §4). */
@OptIn(ExperimentalCoroutinesApi::class)
class HealthReportsCoordinatorTest {
    private val scheduler = FakeScheduler()
    private val ended = mutableListOf<String>()
    private val pending = mutableSetOf<String>()
    private val user = MutableStateFlow<SessionUser?>(null)
    private val owner = HealthOwner.keyOf(TrainingFixtures.USER_ID)
    private val other = HealthOwner.keyOf(TrainingFixtures.OTHER_USER_ID)

    private fun athlete(id: String) = SessionUser(id, "Ana Deportista", "ana@example.com", ActorType.ATHLETE)

    private fun coordinator() = HealthReportsCoordinator(user, { it in pending }, scheduler, HealthReportsSessionPolicy { ended += it })

    @Test
    fun `el inicio sin usuario no cancela nada porque WorkManager puede iniciar el proceso`() = runTest(UnconfinedTestDispatcher()) {
        val job = coordinator().start(this)

        assertEquals(0, scheduler.cancellations)
        assertTrue(ended.isEmpty())
        job.cancel()
    }

    @Test
    fun `al autenticarse se programa la particion con pendientes`() = runTest(UnconfinedTestDispatcher()) {
        pending += owner
        val job = coordinator().start(this)

        user.value = athlete(TrainingFixtures.USER_ID)

        assertEquals(listOf(owner), scheduler.scheduled)
        job.cancel()
    }

    @Test
    fun `al cerrar sesion se cancela la cola y se aplica la politica a la particion anterior`() = runTest(UnconfinedTestDispatcher()) {
        val job = coordinator().start(this)
        user.value = athlete(TrainingFixtures.USER_ID)

        user.value = null

        assertEquals(1, scheduler.cancellations)
        assertEquals(listOf(owner), ended)
        job.cancel()
    }

    @Test
    fun `otro deportista no reanuda la particion anterior`() = runTest(UnconfinedTestDispatcher()) {
        pending += owner
        val job = coordinator().start(this)
        user.value = athlete(TrainingFixtures.USER_ID)
        scheduler.scheduled.clear()

        user.value = athlete(TrainingFixtures.OTHER_USER_ID)

        assertEquals(listOf(owner), ended)
        assertFalse(owner in scheduler.scheduled)
        assertFalse("Sin pendientes no se programa", other in scheduler.scheduled)
        job.cancel()
    }

    @Test
    fun `una falla de la politica no detiene la coordinacion`() = runTest(UnconfinedTestDispatcher()) {
        pending += owner
        val job = HealthReportsCoordinator(user, { it in pending }, scheduler, HealthReportsSessionPolicy { error("almacenamiento") }).start(this)
        user.value = athlete(TrainingFixtures.USER_ID)
        user.value = null
        scheduler.scheduled.clear()

        user.value = athlete(TrainingFixtures.USER_ID)

        assertEquals(listOf(owner), scheduler.scheduled)
        job.cancel()
    }

    @Test
    fun `la fabrica de HU042 solo crea su trabajo y devuelve null para HU032 HU034 HU004 y HU039`() {
        assertTrue(HealthReportsSyncWorkerFactory.handles(HealthReportsSyncWorker::class.java.name))
        listOf(TrainingSyncWorker::class.java, MetricsSyncWorker::class.java, RevocationWorker::class.java, NotificationsSyncWorker::class.java)
            .forEach { assertFalse(it.name, HealthReportsSyncWorkerFactory.handles(it.name)) }
    }
}
