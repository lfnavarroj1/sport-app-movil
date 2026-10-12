package com.lfnj.sport_app.features.identity.start

import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.RestoreOutcome
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.navigation.Destinations
import com.lfnj.sport_app.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionStartViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val user = SessionUser("u-1", "Ana Deportista", "ana@example.com", ActorType.ATHLETE)

    @Test
    fun `sesion recuperada abre athlete_home`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = SessionStartViewModel { RestoreOutcome.Restored(user) }
        advanceUntilIdle()

        assertEquals(StartNavigation(Destinations.ATHLETE_HOME), vm.uiState.value.navigateTo)
    }

    @Test
    fun `destinos segun el resultado de la recuperacion`() = runTest(mainDispatcherRule.dispatcher) {
        val cases = mapOf(
            RestoreOutcome.NoSession to StartNavigation(Destinations.LOGIN),
            RestoreOutcome.NotConfigured to StartNavigation(Destinations.LOGIN),
            RestoreOutcome.Expired to StartNavigation(Destinations.LOGIN, sessionExpired = true),
            RestoreOutcome.UnsupportedActor to StartNavigation(Destinations.UNAUTHORIZED),
        )
        cases.forEach { (outcome, expected) ->
            val vm = SessionStartViewModel { outcome }
            advanceUntilIdle()
            assertEquals(expected, vm.uiState.value.navigateTo)
        }
    }

    @Test
    fun `sin conexion permite reintentar o ir al acceso`() = runTest(mainDispatcherRule.dispatcher) {
        val answers = ArrayDeque(listOf(RestoreOutcome.NoConnection, RestoreOutcome.Restored(user)))
        val vm = SessionStartViewModel { answers.removeFirst() }
        advanceUntilIdle()
        assertEquals(RestoreOutcome.NoConnection, vm.uiState.value.failure)
        assertEquals(null, vm.uiState.value.navigateTo)

        vm.retry()
        advanceUntilIdle()
        assertEquals(StartNavigation(Destinations.ATHLETE_HOME), vm.uiState.value.navigateTo)

        vm.onNavigated()
        vm.continueToLogin()
        assertEquals(StartNavigation(Destinations.LOGIN), vm.uiState.value.navigateTo)
    }
}
