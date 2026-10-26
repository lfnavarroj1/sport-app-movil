package com.lfnj.sport_app.features.notifications

import com.lfnj.sport_app.features.notifications.data.PreferencesGateway
import com.lfnj.sport_app.features.notifications.data.PreferencesResult
import com.lfnj.sport_app.features.notifications.data.SavePreferencesResult
import com.lfnj.sport_app.features.notifications.model.NotificationPreferences
import com.lfnj.sport_app.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** HU039: preferencias `event_matches`/`event_reminders` con control optimista y sin conexión. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationPreferencesViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeGateway : PreferencesGateway {
        val reads = ArrayDeque<PreferencesResult>()
        val saves = ArrayDeque<SavePreferencesResult>()
        val sent = mutableListOf<NotificationPreferences>()

        override suspend fun preferences(): PreferencesResult = reads.removeFirstOrNull() ?: PreferencesResult.NoConnection

        override suspend fun savePreferences(desired: NotificationPreferences): SavePreferencesResult {
            sent += desired
            return saves.removeFirstOrNull() ?: SavePreferencesResult.NoConnection
        }
    }

    private val defaults = NotificationPreferences(eventMatches = true, eventReminders = true, pushEnabled = false, version = 0)

    @Test
    fun `carga las preferencias vigentes y guarda solo lo cambiado conservando push y version`() = runTest {
        val gateway = FakeGateway().apply {
            reads += PreferencesResult.Loaded(defaults)
            saves += SavePreferencesResult.Saved(defaults.copy(eventMatches = false, version = 1))
        }
        val vm = NotificationPreferencesViewModel(gateway)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.loading)
        assertFalse(vm.uiState.value.canSave)

        vm.setEventMatches(false)
        assertTrue(vm.uiState.value.canSave)
        vm.save()
        advanceUntilIdle()

        assertEquals(listOf(defaults.copy(eventMatches = false)), gateway.sent)
        val state = vm.uiState.value
        assertTrue(state.saved)
        assertEquals(1, state.confirmed?.version)
        assertFalse(state.eventMatches)
        assertFalse(state.changed)
    }

    @Test
    fun `un conflicto relee la version vigente y lo declara sin sobrescribir`() = runTest {
        val current = defaults.copy(eventReminders = false, version = 3)
        val gateway = FakeGateway().apply {
            reads += PreferencesResult.Loaded(defaults)
            saves += SavePreferencesResult.Conflict
            reads += PreferencesResult.Loaded(current)
        }
        val vm = NotificationPreferencesViewModel(gateway)
        advanceUntilIdle()

        vm.setEventMatches(false)
        vm.save()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(PreferencesProblem.CONFLICT, state.problem)
        assertEquals(current, state.confirmed)
        assertTrue(state.eventMatches)
        assertFalse(state.eventReminders)
        assertEquals(1, gateway.sent.size)
    }

    @Test
    fun `sin red no muestra valores supuestos y permite reintentar`() = runTest {
        val gateway = FakeGateway().apply {
            reads += PreferencesResult.NoConnection
            reads += PreferencesResult.Loaded(defaults)
        }
        val vm = NotificationPreferencesViewModel(gateway)
        advanceUntilIdle()
        assertEquals(PreferencesProblem.NO_CONNECTION, vm.uiState.value.problem)
        assertNull(vm.uiState.value.confirmed)

        vm.retry()
        advanceUntilIdle()

        assertNull(vm.uiState.value.problem)
        assertEquals(defaults, vm.uiState.value.confirmed)
    }

    @Test
    fun `un guardado sin red conserva la eleccion y lo informa`() = runTest {
        val gateway = FakeGateway().apply {
            reads += PreferencesResult.Loaded(defaults)
            saves += SavePreferencesResult.NoConnection
        }
        val vm = NotificationPreferencesViewModel(gateway)
        advanceUntilIdle()

        vm.setEventReminders(false)
        vm.save()
        advanceUntilIdle()

        assertEquals(PreferencesProblem.NO_CONNECTION, vm.uiState.value.problem)
        assertFalse(vm.uiState.value.eventReminders)
        assertTrue(vm.uiState.value.canSave)
    }

    @Test
    fun `sesion vencida al leer o guardar pide volver al acceso`() = runTest {
        val gateway = FakeGateway().apply { reads += PreferencesResult.SignedOut }
        val vm = NotificationPreferencesViewModel(gateway)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.sessionExpired)
    }
}
