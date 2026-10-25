package com.lfnj.sport_app.features.events

import com.lfnj.sport_app.features.events.data.ConnectivityMonitor
import com.lfnj.sport_app.features.events.data.EventDetailGateway
import com.lfnj.sport_app.features.events.data.EventDetailResult
import com.lfnj.sport_app.features.events.data.RegisterResult
import com.lfnj.sport_app.features.events.model.MyRegistration
import com.lfnj.sport_app.testing.EventRegistrationFixtures
import com.lfnj.sport_app.testing.EventRegistrationFixtures.EVENT_ID
import com.lfnj.sport_app.testing.EventRegistrationFixtures.REGISTRATION_ID
import com.lfnj.sport_app.testing.MainDispatcherRule
import com.lfnj.sport_app.testing.MutableClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** HU041: estado del detalle, habilitación de «Inscribirme», clave de idempotencia y mapeo de errores. */
@OptIn(ExperimentalCoroutinesApi::class)
class EventDetailViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val zone = ZoneId.of("America/Bogota")
    private val clock = MutableClock(Instant.parse("2026-11-10T15:00:00Z"), zone)
    private val online = MutableStateFlow(true)
    private val connectivity = ConnectivityMonitor { online }
    private var keys = 0

    /** Respuestas del BFF programadas por la prueba y claves recibidas. */
    private class FakeGateway : EventDetailGateway {
        val details = ArrayDeque<EventDetailResult>()
        val registers = ArrayDeque<RegisterResult>()
        val detailCalls = mutableListOf<String>()
        val keys = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun detail(eventId: String): EventDetailResult {
            detailCalls += eventId
            return details.removeFirstOrNull() ?: EventDetailResult.Loaded(EventRegistrationFixtures.detail())
        }

        override suspend fun register(eventId: String, idempotencyKey: String): RegisterResult {
            keys += idempotencyKey
            gate?.await()
            return registers.removeFirstOrNull() ?: RegisterResult.Unavailable(null)
        }
    }

    private fun viewModel(gateway: FakeGateway) =
        EventDetailViewModel(EVENT_ID, gateway, connectivity, clock, newKey = { "key-${++keys}" })

    @Test
    fun `carga el detalle y habilita la inscripcion con conexion y evento futuro`() = runTest {
        val vm = viewModel(FakeGateway())
        assertTrue(vm.uiState.value.loading)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.loading)
        assertEquals(EVENT_ID, state.detail?.eventId)
        assertEquals(zone, state.zone)
        assertNull(state.block)
        assertTrue(state.canRegister)
    }

    @Test
    fun `con my_registration el boton queda deshabilitado como ya inscrito`() = runTest {
        val gateway = FakeGateway().apply {
            details += EventDetailResult.Loaded(EventRegistrationFixtures.detail(myRegistration = MyRegistration(REGISTRATION_ID, Instant.EPOCH)))
        }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.registered)
        assertEquals(RegisterBlock.ALREADY_REGISTERED, vm.uiState.value.block)
        assertFalse(vm.uiState.value.canRegister)
    }

    @Test
    fun `un evento ya iniciado deshabilita la inscripcion`() = runTest {
        val gateway = FakeGateway().apply {
            details += EventDetailResult.Loaded(EventRegistrationFixtures.detail(startsAt = clock.instant))
        }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        assertEquals(RegisterBlock.STARTED, vm.uiState.value.block)
        assertFalse(vm.uiState.value.canRegister)
    }

    @Test
    fun `sin conexion la inscripcion se deshabilita y vuelve al recuperar la red`() = runTest {
        val vm = viewModel(FakeGateway())
        advanceUntilIdle()

        online.value = false
        advanceUntilIdle()
        assertEquals(RegisterBlock.OFFLINE, vm.uiState.value.block)
        assertFalse(vm.uiState.value.canRegister)
        vm.requestRegister()
        assertFalse(vm.uiState.value.confirming)

        online.value = true
        advanceUntilIdle()
        assertTrue(vm.uiState.value.canRegister)
    }

    @Test
    fun `sin red al abrir el detalle se informa y se recarga al recuperar la conexion (CA10)`() = runTest {
        online.value = false
        val gateway = FakeGateway().apply { details += EventDetailResult.NoConnection }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        assertEquals(EventDetailProblem.NO_CONNECTION, vm.uiState.value.problem)
        assertNull(vm.uiState.value.detail)
        assertFalse(vm.uiState.value.canRegister)

        online.value = true
        advanceUntilIdle()
        assertEquals(2, gateway.detailCalls.size)
        assertTrue(vm.uiState.value.canRegister)
    }

    @Test
    fun `404 403 y respuesta invalida del detalle`() = runTest {
        val gateway = FakeGateway().apply {
            details += EventDetailResult.NotFound
            details += EventDetailResult.PermissionRequired
            details += EventDetailResult.InvalidResponse
            details += EventDetailResult.Unavailable("c")
        }
        val vm = viewModel(gateway)
        advanceUntilIdle()
        assertEquals(EventDetailProblem.NOT_FOUND, vm.uiState.value.problem)
        vm.retry(); advanceUntilIdle()
        assertEquals(EventDetailProblem.PERMISSION_REQUIRED, vm.uiState.value.problem)
        vm.retry(); advanceUntilIdle()
        assertEquals(EventDetailProblem.INVALID_RESPONSE, vm.uiState.value.problem)
        vm.retry(); advanceUntilIdle()
        assertEquals(EventDetailProblem.UNAVAILABLE, vm.uiState.value.problem)
    }

    @Test
    fun `la inscripcion exige confirmacion explicita y solo muestra el resultado del 201`() = runTest {
        val gateway = FakeGateway().apply { registers += RegisterResult.Registered(EventRegistrationFixtures.confirmed()) }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        vm.requestRegister()
        assertTrue(vm.uiState.value.confirming)
        assertTrue(gateway.keys.isEmpty())

        vm.confirmRegister()
        assertTrue(vm.uiState.value.submitting)
        assertFalse(vm.uiState.value.registered)
        advanceUntilIdle()

        assertEquals(REGISTRATION_ID, vm.uiState.value.confirmed?.registrationId)
        assertTrue(vm.uiState.value.registered)
        assertFalse(vm.uiState.value.submitting)
        assertEquals(listOf("key-1"), gateway.keys)
    }

    @Test
    fun `cancelar la confirmacion no envia nada`() = runTest {
        val gateway = FakeGateway()
        val vm = viewModel(gateway)
        advanceUntilIdle()

        vm.requestRegister()
        vm.cancelRegister()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.confirming)
        assertTrue(gateway.keys.isEmpty())
    }

    @Test
    fun `el doble toque no duplica el envio`() = runTest {
        val gateway = FakeGateway().apply {
            gate = CompletableDeferred()
            registers += RegisterResult.Registered(EventRegistrationFixtures.confirmed())
        }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        vm.requestRegister()
        vm.confirmRegister()
        vm.requestRegister()
        vm.confirmRegister()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.canRegister)
        gateway.gate?.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, gateway.keys.size)
    }

    @Test
    fun `tras no disponible el reintento manual del mismo intento conserva la clave`() = runTest {
        val gateway = FakeGateway().apply {
            registers += RegisterResult.Unavailable(null)
            registers += RegisterResult.NoConnection
            registers += RegisterResult.Registered(EventRegistrationFixtures.confirmed())
        }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        repeat(3) {
            vm.requestRegister()
            vm.confirmRegister()
            advanceUntilIdle()
        }

        assertEquals(listOf("key-1", "key-1", "key-1"), gateway.keys)
        assertTrue(vm.uiState.value.registered)
    }

    @Test
    fun `la clave se renueva tras el exito y tras idempotency_key_reused`() = runTest {
        val gateway = FakeGateway().apply {
            registers += RegisterResult.KeyReused
            registers += RegisterResult.Full
        }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        vm.requestRegister(); vm.confirmRegister(); advanceUntilIdle()
        assertEquals(RegistrationFailure.KEY_REUSED, vm.uiState.value.failure)
        vm.requestRegister(); vm.confirmRegister(); advanceUntilIdle()

        assertEquals(listOf("key-1", "key-2"), gateway.keys)
        assertNotEquals(gateway.keys[0], gateway.keys[1])
    }

    @Test
    fun `ya inscrito informa y recarga el detalle con la inscripcion existente (CA4)`() = runTest {
        val gateway = FakeGateway().apply {
            details += EventDetailResult.Loaded(EventRegistrationFixtures.detail())
            details += EventDetailResult.Loaded(EventRegistrationFixtures.detail(myRegistration = MyRegistration(REGISTRATION_ID, Instant.EPOCH)))
            registers += RegisterResult.AlreadyRegistered(REGISTRATION_ID)
        }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        vm.requestRegister(); vm.confirmRegister(); advanceUntilIdle()

        assertEquals(RegistrationFailure.ALREADY_REGISTERED, vm.uiState.value.failure)
        assertEquals(2, gateway.detailCalls.size)
        assertEquals(RegisterBlock.ALREADY_REGISTERED, vm.uiState.value.block)
        assertNull(vm.uiState.value.confirmed)
    }

    @Test
    fun `sin cupo y cerrado tienen mensajes propios y no se reintentan solos (CA3 CA5)`() = runTest {
        val gateway = FakeGateway().apply {
            registers += RegisterResult.Full
            registers += RegisterResult.Closed
            details += EventDetailResult.Loaded(EventRegistrationFixtures.detail())
            details += EventDetailResult.Loaded(EventRegistrationFixtures.detail(startsAt = clock.instant))
        }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        vm.requestRegister(); vm.confirmRegister(); advanceUntilIdle()
        assertEquals(RegistrationFailure.FULL, vm.uiState.value.failure)
        assertEquals(1, gateway.keys.size)
        assertFalse(vm.uiState.value.registered)

        vm.requestRegister(); vm.confirmRegister(); advanceUntilIdle()
        assertEquals(RegistrationFailure.CLOSED, vm.uiState.value.failure)
        assertEquals(RegisterBlock.STARTED, vm.uiState.value.block)
        assertEquals(2, gateway.keys.size)

        vm.dismissFailure()
        assertNull(vm.uiState.value.failure)
    }

    @Test
    fun `los demas resultados del comando`() = runTest {
        val gateway = FakeGateway().apply {
            registers += RegisterResult.NotFound
            registers += RegisterResult.PermissionRequired
            registers += RegisterResult.Invalid(listOf("invalid_uuid"))
            registers += RegisterResult.InvalidResponse
        }
        val vm = viewModel(gateway)
        advanceUntilIdle()
        val failures = List(4) {
            vm.requestRegister(); vm.confirmRegister(); advanceUntilIdle()
            vm.uiState.value.failure
        }

        assertEquals(
            listOf(RegistrationFailure.NOT_FOUND, RegistrationFailure.PERMISSION_REQUIRED, RegistrationFailure.INVALID, RegistrationFailure.UNAVAILABLE),
            failures,
        )
        assertFalse(vm.uiState.value.registered)
    }

    @Test
    fun `una sesion vencida vuelve al acceso`() = runTest {
        val gateway = FakeGateway().apply { registers += RegisterResult.SignedOut }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        vm.requestRegister(); vm.confirmRegister(); advanceUntilIdle()

        assertTrue(vm.uiState.value.sessionExpired)
        vm.onSessionExpiredHandled()
        assertFalse(vm.uiState.value.sessionExpired)
    }
}
