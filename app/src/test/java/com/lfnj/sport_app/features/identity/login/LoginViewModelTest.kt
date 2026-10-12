package com.lfnj.sport_app.features.identity.login

import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.LoginOutcome
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.navigation.Destinations
import com.lfnj.sport_app.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val athlete = SessionUser("u-1", "Ana Deportista", "ana@example.com", ActorType.ATHLETE)
    private val calls = mutableListOf<Pair<String, String>>()

    private fun viewModel(outcome: LoginOutcome) = LoginViewModel({ email, password ->
        calls += email to password
        outcome
    })

    private fun LoginViewModel.fill(email: String = "ana@example.com", password: String = "Secreta-123") {
        onEmailChange(email)
        onPasswordChange(password)
    }

    @Test
    fun `campos vacios no llaman al BFF`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(LoginOutcome.Authenticated(athlete))

        vm.fill(email = "  ", password = "")
        vm.submit()
        advanceUntilIdle()

        assertEquals(FieldError.EMPTY, vm.uiState.value.emailError)
        assertEquals(FieldError.EMPTY, vm.uiState.value.passwordError)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `campos que superan el contrato no llaman al BFF`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(LoginOutcome.Authenticated(athlete))

        vm.fill(email = "a".repeat(250) + "@x.co", password = "p".repeat(129))
        vm.submit()
        advanceUntilIdle()

        assertEquals(FieldError.TOO_LONG, vm.uiState.value.emailError)
        assertEquals(FieldError.TOO_LONG, vm.uiState.value.passwordError)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `CA6 y CA7 un deportista autenticado navega a athlete_home`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(LoginOutcome.Authenticated(athlete))

        vm.fill(email = "  ana@example.com ")
        vm.submit()
        assertTrue(vm.uiState.value.submitting)
        advanceUntilIdle()

        assertEquals(Destinations.ATHLETE_HOME, vm.uiState.value.navigateTo)
        assertEquals("", vm.uiState.value.password)
        assertEquals(listOf("ana@example.com" to "Secreta-123"), calls)
        vm.onNavigated()
        assertNull(vm.uiState.value.navigateTo)
    }

    @Test
    fun `CA2 y CA3 credenciales rechazadas muestran el mensaje generico y borran la contrasena`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(LoginOutcome.InvalidCredentials)

        vm.fill()
        vm.submit()
        advanceUntilIdle()

        assertEquals(LoginError.InvalidCredentials, vm.uiState.value.error)
        assertEquals("", vm.uiState.value.password)
        assertEquals("ana@example.com", vm.uiState.value.email)
        assertNull(vm.uiState.value.navigateTo)
    }

    @Test
    fun `CA11 un actor no deportista recibe un mensaje controlado y no navega`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(LoginOutcome.ActorNotAllowedInChannel)

        vm.fill()
        vm.submit()
        advanceUntilIdle()

        assertEquals(LoginError.ActorNotAllowed, vm.uiState.value.error)
        assertNull(vm.uiState.value.navigateTo)
        assertEquals("", vm.uiState.value.password)
    }

    @Test
    fun `CA10 un actor desconocido va al acceso no autorizado`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(LoginOutcome.UnsupportedActor)

        vm.fill()
        vm.submit()
        advanceUntilIdle()

        assertEquals(Destinations.UNAUTHORIZED, vm.uiState.value.navigateTo)
    }

    @Test
    fun `CA4 la espera se expresa en minutos redondeados hacia arriba`() = runTest(mainDispatcherRule.dispatcher) {
        listOf(30L to 1L, 60L to 1L, 61L to 2L, 900L to 15L).forEach { (seconds, minutes) ->
            val vm = viewModel(LoginOutcome.TooManyAttempts(seconds))
            vm.fill()
            vm.submit()
            advanceUntilIdle()
            assertEquals(LoginError.TooManyAttempts(minutes), vm.uiState.value.error)
        }
        val withoutHeader = viewModel(LoginOutcome.TooManyAttempts(null))
        withoutHeader.fill()
        withoutHeader.submit()
        advanceUntilIdle()
        assertEquals(LoginError.TooManyAttempts(null), withoutHeader.uiState.value.error)
    }

    @Test
    fun `sin conexion conserva los datos para reintentar`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(LoginOutcome.NoConnection)

        vm.fill()
        vm.submit()
        advanceUntilIdle()

        assertEquals(LoginError.NoConnection, vm.uiState.value.error)
        assertEquals("Secreta-123", vm.uiState.value.password)
        assertFalse(vm.uiState.value.submitting)
    }

    @Test
    fun `servicio no disponible conserva la referencia`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(LoginOutcome.Unavailable("c-1"))

        vm.fill()
        vm.submit()
        advanceUntilIdle()

        assertEquals(LoginError.Unavailable("c-1"), vm.uiState.value.error)
    }

    @Test
    fun `ignora envios repetidos mientras hay uno en curso`() = runTest(mainDispatcherRule.dispatcher) {
        val gate = CompletableDeferred<LoginOutcome>()
        var count = 0
        val vm = LoginViewModel({ _, _ -> count++; gate.await() })

        vm.fill()
        vm.submit()
        vm.submit()
        advanceUntilIdle()
        gate.complete(LoginOutcome.InvalidCredentials)
        advanceUntilIdle()

        assertEquals(1, count)
    }

    @Test
    fun `editar un campo limpia el error`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(LoginOutcome.InvalidCredentials)
        vm.fill()
        vm.submit()
        advanceUntilIdle()

        vm.onPasswordChange("otra")

        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `la sesion vencida se informa hasta el siguiente intento`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = LoginViewModel({ _, _ -> LoginOutcome.InvalidCredentials }, sessionExpired = true)
        assertTrue(vm.uiState.value.sessionExpired)

        vm.fill()
        vm.submit()

        assertFalse(vm.uiState.value.sessionExpired)
    }

    @Test
    fun `el estado no expone la contrasena al convertirse en texto`() {
        val state = LoginUiState(email = "ana@example.com", password = "Secreta-123")

        assertFalse("Secreta-123" in state.toString())
        assertFalse("ana@example.com" in state.toString())
    }
}
