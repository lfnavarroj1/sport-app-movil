package com.lfnj.sport_app.features.nutrition.intake.sync

import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.nutrition.intake.model.FoodOwner
import com.lfnj.sport_app.testing.FakeScheduler
import com.lfnj.sport_app.testing.TrainingFixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HU043 con ADR-029 offline §4: la cola se detiene al cerrar sesión, se aplica la limpieza a la
 * partición saliente y solo el mismo deportista la reanuda. La fábrica solo crea su propio trabajo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FoodIntakeCoordinatorTest {
    private val ana = SessionUser(TrainingFixtures.USER_ID, "Ana Deportista", "ana@example.com", ActorType.ATHLETE)
    private val beto = SessionUser(TrainingFixtures.OTHER_USER_ID, "Beto Deportista", "beto@example.com", ActorType.ATHLETE)
    private val anaKey = FoodOwner.keyOf(TrainingFixtures.USER_ID)
    private val currentUser = MutableStateFlow<SessionUser?>(null)
    private val scheduler = FakeScheduler()
    private val pending = mutableSetOf(anaKey)
    private val ended = mutableListOf<String>()

    private fun start(scope: CoroutineScope) =
        FoodIntakeCoordinator(currentUser, { owner -> owner in pending }, scheduler) { owner -> ended += owner }.start(scope)

    @Test
    fun `el arranque sin usuario no cancela ni limpia nada`() = runTest(UnconfinedTestDispatcher()) {
        start(backgroundScope)

        assertEquals(0, scheduler.cancellations)
        assertTrue(ended.isEmpty())
    }

    @Test
    fun `al autenticarse se programa la particion con pendientes y al cerrar sesion se detiene y se limpia`() = runTest(UnconfinedTestDispatcher()) {
        start(backgroundScope)

        currentUser.value = ana
        assertEquals(listOf(anaKey), scheduler.scheduled)

        currentUser.value = null
        assertEquals(1, scheduler.cancellations)
        assertEquals(listOf(anaKey), ended)
    }

    @Test
    fun `otro deportista no reanuda una particion ajena`() = runTest(UnconfinedTestDispatcher()) {
        start(backgroundScope)

        currentUser.value = beto

        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun `una falla de la limpieza no detiene la coordinacion`() = runTest(UnconfinedTestDispatcher()) {
        FoodIntakeCoordinator(currentUser, { true }, scheduler) { error("base dañada") }.start(backgroundScope)

        currentUser.value = ana
        currentUser.value = null
        currentUser.value = ana

        assertEquals(2, scheduler.scheduled.size)
    }

    @Test
    fun `la fabrica de WorkManager solo crea el trabajo de HU043`() {
        assertTrue(FoodIntakeSyncWorkerFactory.handles(FoodIntakeSyncWorker::class.java.name))
        listOf(
            "com.lfnj.sport_app.features.training.session.sync.TrainingSyncWorker",
            "com.lfnj.sport_app.features.training.metrics.sync.MetricsSyncWorker",
            "com.lfnj.sport_app.core.session.revocation.RevocationWorker",
            "com.lfnj.sport_app.features.notifications.sync.NotificationsSyncWorker",
            "com.lfnj.sport_app.features.health.sync.HealthReportsSyncWorker",
        ).forEach { assertFalse(it, FoodIntakeSyncWorkerFactory.handles(it)) }
    }

    @Test
    fun `el trabajo usa una etiqueta propia y requiere red`() {
        val request = WorkManagerFoodIntakeSyncScheduler.request(anaKey)
        assertTrue(WorkManagerFoodIntakeSyncScheduler.TAG in request.tags)
        assertEquals(androidx.work.NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(anaKey, request.workSpec.input.getString(FoodIntakeSyncWorker.KEY_OWNER))
    }
}
