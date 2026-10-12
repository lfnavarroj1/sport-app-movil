package com.lfnj.sport_app.features.home

import com.lfnj.sport_app.core.session.AccessOutcome
import com.lfnj.sport_app.core.session.AccessSnapshot
import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.core.session.SubscriptionStatus
import com.lfnj.sport_app.navigation.Destinations
import com.lfnj.sport_app.testing.MainDispatcherRule
import com.lfnj.sport_app.testing.SessionFixtures.FREE_CAPABILITIES
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val user = SessionUser("u-1", "Ana Deportista", "ana@example.com", ActorType.ATHLETE)
    private val free = AccessSnapshot(ActorType.ATHLETE, SubscriptionStatus.ACTIVE, "FREE", emptySet(), FREE_CAPABILITIES.toSet())

    @Test
    fun `CA7 construye el menu con las capacidades del plan`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = HomeViewModel(user) { AccessOutcome.Loaded(free) }
        assertTrue(vm.uiState.value.loadingAccess)
        assertEquals(listOf("home"), vm.uiState.value.menu.map { it.id })

        advanceUntilIdle()

        assertFalse(vm.uiState.value.loadingAccess)
        assertEquals(11, vm.uiState.value.menu.size)
    }

    @Test
    fun `sin conexion conserva solo las opciones base y permite reintentar`() = runTest(mainDispatcherRule.dispatcher) {
        val answers = ArrayDeque(listOf(AccessOutcome.NoConnection, AccessOutcome.Loaded(free)))
        val vm = HomeViewModel(user) { answers.removeFirst() }
        advanceUntilIdle()
        assertEquals(AccessOutcome.NoConnection, vm.uiState.value.accessFailure)
        assertEquals(listOf("home"), vm.uiState.value.menu.map { it.id })

        vm.refresh()
        advanceUntilIdle()

        assertEquals(null, vm.uiState.value.accessFailure)
        assertEquals(11, vm.uiState.value.menu.size)
    }

    @Test
    fun `sesion vencida vuelve al acceso informando el vencimiento`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = HomeViewModel(user) { AccessOutcome.SessionExpired }
        advanceUntilIdle()

        assertEquals(HomeExit(Destinations.LOGIN, sessionExpired = true), vm.uiState.value.navigateTo)
    }

    @Test
    fun `CA10 actor rechazado retira el menu y va al acceso no autorizado`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = HomeViewModel(user) { AccessOutcome.Unauthorized }
        advanceUntilIdle()

        assertTrue(vm.uiState.value.menu.isEmpty())
        assertEquals(HomeExit(Destinations.UNAUTHORIZED), vm.uiState.value.navigateTo)
    }
}
