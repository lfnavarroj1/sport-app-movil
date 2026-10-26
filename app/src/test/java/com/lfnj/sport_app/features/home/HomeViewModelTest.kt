package com.lfnj.sport_app.features.home

import com.lfnj.sport_app.core.session.AccessOutcome
import com.lfnj.sport_app.core.session.AccessSnapshot
import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.LogoutOutcome
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.core.session.SubscriptionStatus
import com.lfnj.sport_app.features.identity.login.LoginNotice
import com.lfnj.sport_app.navigation.Destinations
import com.lfnj.sport_app.navigation.MenuAction
import com.lfnj.sport_app.testing.MainDispatcherRule
import com.lfnj.sport_app.testing.SessionFixtures.FREE_CAPABILITIES
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val user = SessionUser("u-1", "Ana Deportista", "ana@example.com", ActorType.ATHLETE)
    private val free = AccessSnapshot(ActorType.ATHLETE, SubscriptionStatus.ACTIVE, "FREE", emptySet(), FREE_CAPABILITIES.toSet())

    private fun HomeUiState.ids() = menu.map { it.id }

    @Test
    fun `CA7 construye el menu con las capacidades del plan`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = HomeViewModel(user, { AccessOutcome.Loaded(free) })
        assertTrue(vm.uiState.value.loadingAccess)
        assertEquals(listOf("home", "notifications", "logout"), vm.uiState.value.ids())

        advanceUntilIdle()

        assertFalse(vm.uiState.value.loadingAccess)
        assertEquals(13, vm.uiState.value.menu.size)
    }

    @Test
    fun `sin conexion conserva solo las opciones base y permite reintentar`() = runTest(mainDispatcherRule.dispatcher) {
        val answers = ArrayDeque(listOf(AccessOutcome.NoConnection, AccessOutcome.Loaded(free)))
        val vm = HomeViewModel(user, { answers.removeFirst() })
        advanceUntilIdle()
        assertEquals(AccessOutcome.NoConnection, vm.uiState.value.accessFailure)
        assertEquals(listOf("home", "notifications", "logout"), vm.uiState.value.ids())

        vm.refresh()
        advanceUntilIdle()

        assertEquals(null, vm.uiState.value.accessFailure)
        assertEquals(13, vm.uiState.value.menu.size)
    }

    @Test
    fun `sesion vencida vuelve al acceso informando el vencimiento`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = HomeViewModel(user, { AccessOutcome.SessionExpired })
        advanceUntilIdle()

        assertEquals(HomeExit(Destinations.LOGIN, LoginNotice.SESSION_EXPIRED), vm.uiState.value.navigateTo)
    }

    @Test
    fun `CA10 actor rechazado retira el menu y va al acceso no autorizado`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = HomeViewModel(user, { AccessOutcome.Unauthorized })
        advanceUntilIdle()

        assertTrue(vm.uiState.value.menu.isEmpty())
        assertEquals(HomeExit(Destinations.UNAUTHORIZED), vm.uiState.value.navigateTo)
    }

    // ------------------------------------------------------------------ HU004

    @Test
    fun `HU004 sin operacion de cierre la opcion queda deshabilitada y no hace nada`() = runTest(mainDispatcherRule.dispatcher) {
        var calls = 0
        val vm = HomeViewModel(user, { AccessOutcome.Loaded(free) }, logoutAvailable = false) { calls++; LogoutOutcome.LoggedOut }
        advanceUntilIdle()

        vm.logout()
        advanceUntilIdle()

        assertNull(vm.uiState.value.menu.single { it.id == "logout" }.action)
        assertEquals(0, calls)
        assertNull(vm.uiState.value.navigateTo)
    }

    @Test
    fun `HU004 CA5 el cierre confirmado vuelve al acceso informando la sesion cerrada y retira la identidad`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = HomeViewModel(user, { AccessOutcome.Loaded(free) }, logoutAvailable = true) { LogoutOutcome.LoggedOut }
        advanceUntilIdle()
        assertEquals(MenuAction.LOGOUT, vm.uiState.value.menu.single { it.id == "logout" }.action)

        vm.logout()
        assertTrue(vm.uiState.value.loggingOut)
        advanceUntilIdle()

        assertEquals(HomeExit(Destinations.LOGIN, LoginNotice.LOGGED_OUT), vm.uiState.value.navigateTo)
        assertNull(vm.uiState.value.user)
        assertNull(vm.uiState.value.access)
        assertTrue(vm.uiState.value.menu.isEmpty())
    }

    @Test
    fun `HU004 CA6 una revocacion pendiente lo informa en el acceso`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = HomeViewModel(user, { AccessOutcome.Loaded(free) }, logoutAvailable = true) {
            LogoutOutcome.LoggedOutRemotePending
        }
        advanceUntilIdle()

        vm.logout()
        advanceUntilIdle()

        assertEquals(HomeExit(Destinations.LOGIN, LoginNotice.LOGOUT_REMOTE_PENDING), vm.uiState.value.navigateTo)
        assertNull(vm.uiState.value.user)
    }

    @Test
    fun `HU004 CA6 sin confirmacion remota avisa en el acceso`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = HomeViewModel(user, { AccessOutcome.Loaded(free) }, logoutAvailable = true) {
            LogoutOutcome.LoggedOutRemoteUnconfirmed
        }
        advanceUntilIdle()

        vm.logout()
        advanceUntilIdle()

        assertEquals(HomeExit(Destinations.LOGIN, LoginNotice.LOGOUT_REMOTE_UNCONFIRMED), vm.uiState.value.navigateTo)
    }

    @Test
    fun `HU004 toques repetidos durante el cierre no duplican la solicitud`() = runTest(mainDispatcherRule.dispatcher) {
        var calls = 0
        val gate = CompletableDeferred<LogoutOutcome>()
        val vm = HomeViewModel(user, { AccessOutcome.Loaded(free) }, logoutAvailable = true) { calls++; gate.await() }
        advanceUntilIdle()

        vm.logout()
        vm.logout()
        advanceUntilIdle()
        vm.logout()
        gate.complete(LogoutOutcome.LoggedOut)
        advanceUntilIdle()

        assertEquals(1, calls)
    }

    @Test
    fun `HU004 el cierre cancela la carga de capacidades en curso`() = runTest(mainDispatcherRule.dispatcher) {
        val access = CompletableDeferred<AccessOutcome>()
        val vm = HomeViewModel(user, { access.await() }, logoutAvailable = true) { LogoutOutcome.LoggedOut }

        vm.logout()
        advanceUntilIdle()
        access.complete(AccessOutcome.Loaded(free))
        advanceUntilIdle()

        assertEquals(HomeExit(Destinations.LOGIN, LoginNotice.LOGGED_OUT), vm.uiState.value.navigateTo)
        assertNull(vm.uiState.value.access)
    }

    @Test
    fun `HU032 una sesion de entrenamiento abierta mantiene accesible su registro`() = runTest(mainDispatcherRule.dispatcher) {
        val open = MutableStateFlow(false)
        val vm = HomeViewModel(user, { AccessOutcome.NoConnection }, openTrainingSession = open)
        advanceUntilIdle()
        assertEquals(listOf("home", "notifications", "logout"), vm.uiState.value.ids())

        open.value = true
        advanceUntilIdle()

        assertEquals(listOf("home", "training.session.record", "logout"), vm.uiState.value.ids())
        assertTrue(vm.uiState.value.openTrainingSession)
    }
}
