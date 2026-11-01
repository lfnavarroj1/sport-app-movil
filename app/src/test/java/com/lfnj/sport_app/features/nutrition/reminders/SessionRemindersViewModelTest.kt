package com.lfnj.sport_app.features.nutrition.reminders

import com.lfnj.sport_app.features.notifications.model.SystemNotificationsStatus
import com.lfnj.sport_app.features.nutrition.reminders.data.ReminderRefresh
import com.lfnj.sport_app.features.nutrition.reminders.data.ReminderSave
import com.lfnj.sport_app.features.nutrition.reminders.data.SessionRemindersGateway
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderConfig
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderPreferences
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderSetting
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderType
import com.lfnj.sport_app.testing.MainDispatcherRule
import com.lfnj.sport_app.testing.RecordingReminderNotifier
import com.lfnj.sport_app.testing.SessionRemindersFixtures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.ZoneId

/** HU038: estados de «Recordatorios durante sesión» (carga, sin reglas, sin red, notificaciones, guardar). */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRemindersViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeGateway(initial: ReminderConfig? = null) : SessionRemindersGateway {
        val cache = MutableStateFlow(initial)
        val refreshes = ArrayDeque<ReminderRefresh>()
        val saves = ArrayDeque<ReminderSave>()
        val saved = mutableListOf<Triple<ReminderSetting, ReminderSetting, Int>>()
        var saveGate: CompletableDeferred<Unit>? = null

        override fun observe(owner: String): Flow<ReminderConfig?> = cache

        override suspend fun refresh(owner: String): ReminderRefresh = refreshes.removeFirstOrNull() ?: ReminderRefresh.NoConnection

        override suspend fun save(owner: String, hydration: ReminderSetting, nutrition: ReminderSetting, expectedVersion: Int): ReminderSave {
            saved += Triple(hydration, nutrition, expectedVersion)
            saveGate?.await()
            val result = saves.removeFirstOrNull() ?: ReminderSave.NoConnection
            if (result == ReminderSave.Saved) {
                cache.value = cache.value?.copy(preferences = ReminderPreferences(hydration, nutrition, expectedVersion + 1))
            }
            return result
        }
    }

    private val notifier = RecordingReminderNotifier()

    private fun viewModel(gateway: FakeGateway) = SessionRemindersViewModel("owner", gateway, notifier, ZoneId.of("America/Bogota"))

    @Test
    fun `muestra carga y luego la configuracion confirmada`() = runTest {
        val gateway = FakeGateway(SessionRemindersFixtures.config()).apply { refreshes += ReminderRefresh.Updated }
        val vm = viewModel(gateway)
        assertTrue(vm.uiState.value.loading)

        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.loading)
        assertFalse(state.insufficientInformation)
        assertEquals(15, state.setting(ReminderType.HYDRATION)?.intervalMinutes)
        assertNull(state.problem)
    }

    @Test
    fun `sin ruleset descargado declara informacion insuficiente (CA5)`() = runTest {
        val vm = viewModel(FakeGateway())
        advanceUntilIdle()

        assertTrue(vm.uiState.value.insufficientInformation)
        assertEquals(RemindersProblem.NO_CONNECTION, vm.uiState.value.problem)
    }

    @Test
    fun `sin red conserva la configuracion guardada y lo informa`() = runTest {
        val vm = viewModel(FakeGateway(SessionRemindersFixtures.config()))
        advanceUntilIdle()

        assertFalse(vm.uiState.value.insufficientInformation)
        assertEquals(RemindersProblem.NO_CONNECTION, vm.uiState.value.problem)
        assertEquals(SessionRemindersFixtures.DEFAULTS, vm.uiState.value.config?.preferences)
    }

    @Test
    fun `sin la capacidad se informa el rechazo`() = runTest {
        val gateway = FakeGateway().apply { refreshes += ReminderRefresh.CapabilityRequired }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        assertEquals(RemindersProblem.CAPABILITY_REQUIRED, vm.uiState.value.problem)
    }

    @Test
    fun `declara las notificaciones del sistema deshabilitadas y relee el estado (CA4)`() = runTest {
        notifier.status = SystemNotificationsStatus.PERMISSION_REQUIRED
        val vm = viewModel(FakeGateway(SessionRemindersFixtures.config()))
        assertEquals(SystemNotificationsStatus.PERMISSION_REQUIRED, vm.uiState.value.systemNotifications)

        notifier.status = SystemNotificationsStatus.ENABLED
        vm.refreshSystemStatus()
        assertEquals(SystemNotificationsStatus.ENABLED, vm.uiState.value.systemNotifications)
    }

    @Test
    fun `los intervalos se ajustan sin salir del rango del ruleset`() = runTest {
        val vm = viewModel(FakeGateway(SessionRemindersFixtures.config()))
        advanceUntilIdle()

        repeat(5) { vm.changeInterval(ReminderType.HYDRATION, -5) }
        assertEquals(10, vm.uiState.value.setting(ReminderType.HYDRATION)?.intervalMinutes)
        repeat(30) { vm.changeInterval(ReminderType.NUTRITION, 5) }
        assertEquals(120, vm.uiState.value.setting(ReminderType.NUTRITION)?.intervalMinutes)
        assertTrue(vm.uiState.value.valid)
        assertTrue(vm.uiState.value.canSave)
    }

    @Test
    fun `volver a los valores confirmados no deja cambios pendientes`() = runTest {
        val vm = viewModel(FakeGateway(SessionRemindersFixtures.config()))
        advanceUntilIdle()

        vm.setEnabled(ReminderType.NUTRITION, false)
        assertTrue(vm.uiState.value.dirty)
        vm.setEnabled(ReminderType.NUTRITION, true)
        assertFalse(vm.uiState.value.dirty)
        assertFalse(vm.uiState.value.canSave)
    }

    @Test
    fun `guardar envia la version leida, evita duplicados y confirma`() = runTest {
        val gateway = FakeGateway(SessionRemindersFixtures.config()).apply {
            saves += ReminderSave.Saved
            saveGate = CompletableDeferred()
        }
        val vm = viewModel(gateway)
        advanceUntilIdle()
        vm.changeInterval(ReminderType.HYDRATION, -5)
        vm.setEnabled(ReminderType.NUTRITION, false)

        vm.save()
        vm.save()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.saving)
        gateway.saveGate?.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(Triple(ReminderSetting(true, 10), ReminderSetting(false, 45), 0)), gateway.saved)
        val state = vm.uiState.value
        assertEquals(RemindersSaveOutcome.SAVED, state.saveOutcome)
        assertFalse(state.dirty)
        assertEquals(1, state.config?.preferences?.version)
    }

    @Test
    fun `un conflicto de version descarta el borrador y muestra las vigentes (CA5)`() = runTest {
        val gateway = FakeGateway(SessionRemindersFixtures.config()).apply { saves += ReminderSave.VersionConflict }
        val vm = viewModel(gateway)
        advanceUntilIdle()
        vm.changeInterval(ReminderType.HYDRATION, 5)

        vm.save()
        advanceUntilIdle()

        assertEquals(RemindersSaveOutcome.VERSION_CONFLICT, vm.uiState.value.saveOutcome)
        assertFalse(vm.uiState.value.dirty)
    }

    @Test
    fun `sin red o con 422 el borrador se conserva`() = runTest {
        val gateway = FakeGateway(SessionRemindersFixtures.config()).apply {
            saves += ReminderSave.NoConnection
            saves += ReminderSave.Invalid(listOf("hydration.interval_minutes"))
        }
        val vm = viewModel(gateway)
        advanceUntilIdle()
        vm.changeInterval(ReminderType.HYDRATION, 5)

        vm.save()
        advanceUntilIdle()
        assertEquals(RemindersSaveOutcome.NO_CONNECTION, vm.uiState.value.saveOutcome)
        assertTrue(vm.uiState.value.dirty)

        vm.save()
        advanceUntilIdle()
        assertEquals(RemindersSaveOutcome.INVALID, vm.uiState.value.saveOutcome)
        assertEquals(20, vm.uiState.value.setting(ReminderType.HYDRATION)?.intervalMinutes)
    }

    @Test
    fun `una sesion vencida al guardar vuelve al acceso`() = runTest {
        val gateway = FakeGateway(SessionRemindersFixtures.config()).apply { saves += ReminderSave.SignedOut }
        val vm = viewModel(gateway)
        advanceUntilIdle()
        vm.changeInterval(ReminderType.HYDRATION, 5)

        vm.save()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.sessionExpired)
        vm.onSessionExpiredHandled()
        assertFalse(vm.uiState.value.sessionExpired)
    }
}
