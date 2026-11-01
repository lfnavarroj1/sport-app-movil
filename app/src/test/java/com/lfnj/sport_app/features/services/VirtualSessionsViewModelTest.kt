package com.lfnj.sport_app.features.services

import com.lfnj.sport_app.features.services.data.BookResult
import com.lfnj.sport_app.features.services.data.CancelResult
import com.lfnj.sport_app.features.services.data.VirtualSessionsGateway
import com.lfnj.sport_app.features.services.data.VirtualSessionsRefresh
import com.lfnj.sport_app.features.services.model.Appointment
import com.lfnj.sport_app.features.services.model.AppointmentStatus
import com.lfnj.sport_app.features.services.model.AppointmentsSnapshot
import com.lfnj.sport_app.features.services.model.AvailabilitySlot
import com.lfnj.sport_app.features.services.model.CatalogSnapshot
import com.lfnj.sport_app.features.services.model.CoachingService
import com.lfnj.sport_app.features.services.model.ServiceFee
import com.lfnj.sport_app.features.services.model.ServiceModality
import com.lfnj.sport_app.testing.MainDispatcherRule
import com.lfnj.sport_app.testing.MutableClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** HU040: estados de «Sesiones virtuales» (carga, vacío, sin red, `STALE`, conflictos, reintento y cancelación). */
@OptIn(ExperimentalCoroutinesApi::class)
class VirtualSessionsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val now = Instant.parse("2026-10-25T15:00:00Z")
    private val clock = MutableClock(now, ZoneId.of("America/Bogota"))

    /** Cachés observables y respuestas del BFF programadas por la prueba. */
    private class FakeGateway(catalog: CatalogSnapshot? = null, appointments: AppointmentsSnapshot? = null) : VirtualSessionsGateway {
        val catalogCache = MutableStateFlow(catalog)
        val appointmentsCache = MutableStateFlow(appointments)
        val catalogResponses = ArrayDeque<VirtualSessionsRefresh>()
        val appointmentsResponses = ArrayDeque<VirtualSessionsRefresh>()
        val bookResponses = ArrayDeque<BookResult>()
        val cancelResponses = ArrayDeque<CancelResult>()
        val bookKeys = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        var catalogCalls = 0
        var appointmentsCalls = 0
        var bookGate: CompletableDeferred<Unit>? = null
        private var keys = 0

        override fun observeCatalog(owner: String): Flow<CatalogSnapshot?> = catalogCache
        override fun observeAppointments(owner: String): Flow<AppointmentsSnapshot?> = appointmentsCache

        override suspend fun refreshCatalog(owner: String): VirtualSessionsRefresh {
            catalogCalls++
            return catalogResponses.removeFirstOrNull() ?: VirtualSessionsRefresh.Updated
        }

        override suspend fun refreshAppointments(owner: String): VirtualSessionsRefresh {
            appointmentsCalls++
            return appointmentsResponses.removeFirstOrNull() ?: VirtualSessionsRefresh.Updated
        }

        override suspend fun book(owner: String, serviceId: String, availabilityId: String, idempotencyKey: String): BookResult {
            bookKeys += idempotencyKey
            bookGate?.await()
            val result = bookResponses.removeFirst()
            if (result is BookResult.Booked) {
                appointmentsCache.value = (appointmentsCache.value ?: AppointmentsSnapshot(emptyList(), Instant.EPOCH)).upsert(result.appointment, Instant.EPOCH)
            }
            return result
        }

        override suspend fun cancel(owner: String, appointmentId: String): CancelResult {
            cancelled += appointmentId
            val result = cancelResponses.removeFirst()
            if (result is CancelResult.Cancelled) {
                appointmentsCache.value = appointmentsCache.value?.upsert(result.appointment, Instant.EPOCH)
            }
            return result
        }

        override fun newIdempotencyKey(): String = "clave-${++keys}"
    }

    private val slotSoon = AvailabilitySlot("slot-pronto", now.plus(Duration.ofMinutes(30)), now.plus(Duration.ofMinutes(90)), ZoneId.of("America/Bogota"), 1)
    private val slot = AvailabilitySlot("slot-1", now.plus(Duration.ofDays(3)), now.plus(Duration.ofDays(3)).plus(Duration.ofHours(1)), ZoneId.of("Europe/Madrid"), 1)
    private val service = CoachingService("svc", "prov", "Asesoría de ritmo", "Descripción", ServiceModality.VIRTUAL, ServiceFee("120000.00", "COP", "SESSION"), listOf(slotSoon, slot))

    private fun catalog(services: List<CoachingService> = listOf(service), syncedAt: Instant = now) = CatalogSnapshot(services, false, syncedAt)

    private fun appointment(
        id: String = "cita-1",
        startsAt: Instant = slot.startsAt,
        status: AppointmentStatus = AppointmentStatus.BOOKED,
        availabilityId: String = slot.availabilityId,
    ) = Appointment(
        appointmentId = id,
        serviceId = "svc",
        availabilityId = availabilityId,
        providerId = "prov",
        serviceName = "Asesoría de ritmo",
        modality = "VIRTUAL",
        fee = ServiceFee("120000.00", "COP", "SESSION"),
        startsAt = startsAt,
        endsAt = startsAt.plus(Duration.ofHours(1)),
        timeZone = ZoneId.of("America/Bogota"),
        status = status,
        bookedAt = now.minus(Duration.ofDays(1)),
        cancelledAt = null,
        version = 1,
    )

    private fun viewModel(gateway: FakeGateway) = VirtualSessionsViewModel("owner", gateway, clock)

    // ----------------------------------------------------------------- lectura

    @Test
    fun `muestra carga y luego el catalogo y las citas confirmadas`() = runTest {
        val gateway = FakeGateway()
        val vm = viewModel(gateway)
        assertTrue(vm.uiState.value.catalogLoading)
        assertTrue(vm.uiState.value.appointmentsLoading)

        gateway.catalogCache.value = catalog()
        gateway.appointmentsCache.value = AppointmentsSnapshot(emptyList(), now)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf("svc"), state.catalog?.services?.map { it.serviceId })
        assertFalse(state.catalogLoading)
        assertFalse(state.offline)
        assertEquals(1, gateway.catalogCalls)
        assertEquals(1, gateway.appointmentsCalls)
    }

    @Test
    fun `un catalogo sin servicios es un estado vacio, no un error`() = runTest {
        val gateway = FakeGateway(catalog(emptyList()))
        val vm = viewModel(gateway)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.catalogEmpty)
        assertNull(vm.uiState.value.catalogProblem)
    }

    @Test
    fun `sin red conserva la informacion guardada, la marca STALE a las 24 h y bloquea la reserva`() = runTest {
        val gateway = FakeGateway(catalog(syncedAt = now.minus(Duration.ofHours(30))), AppointmentsSnapshot(emptyList(), now.minus(Duration.ofHours(30)))).apply {
            catalogResponses += VirtualSessionsRefresh.NoConnection
            appointmentsResponses += VirtualSessionsRefresh.NoConnection
        }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.offline)
        assertTrue(state.catalogStale)
        assertTrue(state.appointmentsStale)
        assertEquals(VirtualSessionsProblem.NO_CONNECTION, state.catalogProblem)
        assertEquals(1, state.catalog?.services?.size)

        vm.openService("svc")
        vm.chooseSlot(slot.availabilityId)
        vm.confirmBooking()
        advanceUntilIdle()
        assertEquals(BookingProblem.OFFLINE, vm.uiState.value.booking?.problem)
        assertTrue(gateway.bookKeys.isEmpty())
    }

    @Test
    fun `sin red y sin cache informa que nunca se sincronizo y reintentar vuelve a consultar`() = runTest {
        val gateway = FakeGateway().apply {
            catalogResponses += VirtualSessionsRefresh.NoConnection
            appointmentsResponses += VirtualSessionsRefresh.NoConnection
        }
        val vm = viewModel(gateway)
        advanceUntilIdle()
        assertNull(vm.uiState.value.catalog)
        assertEquals(VirtualSessionsProblem.NO_CONNECTION, vm.uiState.value.catalogProblem)
        assertFalse(vm.uiState.value.catalogLoading)

        gateway.catalogCache.value = catalog()
        vm.retry()
        advanceUntilIdle()
        assertEquals(2, gateway.catalogCalls)
        assertFalse(vm.uiState.value.offline)
        assertNull(vm.uiState.value.catalogProblem)
    }

    @Test
    fun `sin permiso de deportista no muestra datos guardados`() = runTest {
        val gateway = FakeGateway(catalog()).apply { catalogResponses += VirtualSessionsRefresh.PermissionRequired }
        val vm = viewModel(gateway)
        advanceUntilIdle()
        assertNull(vm.uiState.value.catalog)
        assertEquals(VirtualSessionsProblem.PERMISSION_REQUIRED, vm.uiState.value.catalogProblem)
    }

    @Test
    fun `la sesion vencida vuelve al acceso`() = runTest {
        val gateway = FakeGateway().apply { catalogResponses += VirtualSessionsRefresh.SignedOut }
        val vm = viewModel(gateway)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.sessionExpired)
        vm.onSessionExpiredHandled()
        assertFalse(vm.uiState.value.sessionExpired)
    }

    // ----------------------------------------------------------------- reservar

    @Test
    fun `un intervalo que empieza en menos de 1 h no abre la confirmacion`() = runTest {
        val vm = viewModel(FakeGateway(catalog(), AppointmentsSnapshot(emptyList(), now)))
        advanceUntilIdle()
        vm.openService("svc")
        assertFalse(vm.uiState.value.isBookable(slotSoon))
        vm.chooseSlot(slotSoon.availabilityId)
        assertNull(vm.uiState.value.booking)
    }

    @Test
    fun `un intervalo ya reservado por el deportista no se ofrece de nuevo`() = runTest {
        val vm = viewModel(FakeGateway(catalog(), AppointmentsSnapshot(listOf(appointment()), now)))
        advanceUntilIdle()
        vm.openService("svc")
        assertFalse(vm.uiState.value.isBookable(slot))
        vm.chooseSlot(slot.availabilityId)
        assertNull(vm.uiState.value.booking)
    }

    @Test
    fun `confirma la reserva, la anuncia y muestra Mis sesiones (CA1)`() = runTest {
        val gateway = FakeGateway(catalog(), AppointmentsSnapshot(emptyList(), now)).apply { bookResponses += BookResult.Booked(appointment()) }
        val vm = viewModel(gateway)
        advanceUntilIdle()
        vm.openService("svc")
        vm.chooseSlot(slot.availabilityId)
        assertEquals("clave-1", vm.uiState.value.booking?.idempotencyKey)

        vm.confirmBooking()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertNull(state.booking)
        assertTrue(state.notice is VirtualSessionsNotice.Booked)
        assertEquals(VirtualSessionsTab.MY_SESSIONS, state.tab)
        assertEquals(listOf("cita-1"), state.appointments?.upcoming(now)?.map { it.appointmentId })
        assertEquals(listOf("clave-1"), gateway.bookKeys)
    }

    @Test
    fun `no envia dos veces mientras la reserva esta en curso`() = runTest {
        val gateway = FakeGateway(catalog(), AppointmentsSnapshot(emptyList(), now)).apply {
            bookGate = CompletableDeferred()
            bookResponses += BookResult.Booked(appointment())
        }
        val vm = viewModel(gateway)
        advanceUntilIdle()
        vm.openService("svc")
        vm.chooseSlot(slot.availabilityId)
        vm.confirmBooking()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.booking?.sending == true)
        vm.confirmBooking()
        vm.dismissBooking()
        assertTrue(vm.uiState.value.booking != null)
        gateway.bookGate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, gateway.bookKeys.size)
    }

    @Test
    fun `un resultado desconocido se reintenta con la misma clave aunque no haya red (CA5)`() = runTest {
        val gateway = FakeGateway(catalog(), AppointmentsSnapshot(emptyList(), now)).apply {
            bookResponses += BookResult.NoConnection
            bookResponses += BookResult.Booked(appointment())
        }
        val vm = viewModel(gateway)
        advanceUntilIdle()
        vm.openService("svc")
        vm.chooseSlot(slot.availabilityId)
        vm.confirmBooking()
        advanceUntilIdle()
        assertEquals(BookingProblem.UNKNOWN_OUTCOME, vm.uiState.value.booking?.problem)
        assertTrue(vm.uiState.value.offline)

        vm.confirmBooking()
        advanceUntilIdle()
        assertEquals(listOf("clave-1", "clave-1"), gateway.bookKeys)
        assertTrue(vm.uiState.value.notice is VirtualSessionsNotice.Booked)
        assertFalse(vm.uiState.value.offline)
    }

    @Test
    fun `availability_full es un conflicto funcional que actualiza el catalogo (CA2)`() = runTest {
        val gateway = FakeGateway(catalog(), AppointmentsSnapshot(emptyList(), now)).apply { bookResponses += BookResult.Full }
        val vm = viewModel(gateway)
        advanceUntilIdle()
        vm.openService("svc")
        vm.chooseSlot(slot.availabilityId)
        vm.confirmBooking()
        advanceUntilIdle()
        assertEquals(BookingProblem.FULL, vm.uiState.value.booking?.problem)
        assertEquals(2, gateway.catalogCalls)
        assertFalse(vm.uiState.value.offline)
    }

    @Test
    fun `appointment_exists explica la cita duplicada y actualiza Mis sesiones (CA2)`() = runTest {
        val gateway = FakeGateway(catalog(), AppointmentsSnapshot(emptyList(), now)).apply { bookResponses += BookResult.AlreadyBooked("cita-1") }
        val vm = viewModel(gateway)
        advanceUntilIdle()
        vm.openService("svc")
        vm.chooseSlot(slot.availabilityId)
        vm.confirmBooking()
        advanceUntilIdle()
        assertEquals(BookingProblem.ALREADY_BOOKED, vm.uiState.value.booking?.problem)
        assertEquals(2, gateway.appointmentsCalls)
    }

    @Test
    fun `sin la capacidad explica el plan requerido (CA3)`() = runTest {
        val gateway = FakeGateway(catalog(), AppointmentsSnapshot(emptyList(), now)).apply { bookResponses += BookResult.CapabilityRequired }
        val vm = viewModel(gateway)
        advanceUntilIdle()
        vm.openService("svc")
        vm.chooseSlot(slot.availabilityId)
        vm.confirmBooking()
        advanceUntilIdle()
        assertEquals(BookingProblem.CAPABILITY_REQUIRED, vm.uiState.value.booking?.problem)
    }

    @Test
    fun `una clave reutilizada se reemplaza para el siguiente intento`() = runTest {
        val gateway = FakeGateway(catalog(), AppointmentsSnapshot(emptyList(), now)).apply {
            bookResponses += BookResult.KeyReused
            bookResponses += BookResult.Booked(appointment())
        }
        val vm = viewModel(gateway)
        advanceUntilIdle()
        vm.openService("svc")
        vm.chooseSlot(slot.availabilityId)
        vm.confirmBooking()
        advanceUntilIdle()
        assertEquals(BookingProblem.KEY_RENEWED, vm.uiState.value.booking?.problem)
        vm.confirmBooking()
        advanceUntilIdle()
        assertEquals(2, gateway.bookKeys.size)
        assertNotEquals(gateway.bookKeys[0], gateway.bookKeys[1])
    }

    @Test
    fun `cada intento de reserva usa una clave nueva`() = runTest {
        val vm = viewModel(FakeGateway(catalog(), AppointmentsSnapshot(emptyList(), now)))
        advanceUntilIdle()
        vm.openService("svc")
        vm.chooseSlot(slot.availabilityId)
        val first = vm.uiState.value.booking?.idempotencyKey
        vm.dismissBooking()
        vm.chooseSlot(slot.availabilityId)
        assertNotEquals(first, vm.uiState.value.booking?.idempotencyKey)
    }

    // ----------------------------------------------------------------- cancelar (CA4)

    @Test
    fun `cancela con 24 h o mas y el intervalo vuelve a consultarse`() = runTest {
        val booked = appointment()
        val gateway = FakeGateway(catalog(), AppointmentsSnapshot(listOf(booked), now)).apply {
            cancelResponses += CancelResult.Cancelled(booked.copy(status = AppointmentStatus.CANCELLED_BY_ATHLETE, cancelledAt = now, version = 2))
        }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        vm.requestCancel("cita-1")
        assertEquals("cita-1", vm.uiState.value.cancelling?.appointment?.appointmentId)
        vm.confirmCancel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertNull(state.cancelling)
        assertTrue(state.notice is VirtualSessionsNotice.Cancelled)
        assertEquals(AppointmentStatus.CANCELLED_BY_ATHLETE, state.appointments?.items?.single()?.status)
        assertEquals(2, gateway.catalogCalls)
    }

    @Test
    fun `a 2 h del inicio no ofrece la cancelacion`() = runTest {
        val soon = appointment(startsAt = now.plus(Duration.ofHours(2)))
        val gateway = FakeGateway(catalog(), AppointmentsSnapshot(listOf(soon), now))
        val vm = viewModel(gateway)
        advanceUntilIdle()

        assertFalse(soon.canCancel(now))
        vm.requestCancel("cita-1")
        assertNull(vm.uiState.value.cancelling)
        assertTrue(gateway.cancelled.isEmpty())
    }

    @Test
    fun `si el backend cierra la ventana se explica y se actualiza Mis sesiones`() = runTest {
        val gateway = FakeGateway(catalog(), AppointmentsSnapshot(listOf(appointment()), now)).apply { cancelResponses += CancelResult.WindowClosed }
        val vm = viewModel(gateway)
        advanceUntilIdle()
        vm.requestCancel("cita-1")
        vm.confirmCancel()
        advanceUntilIdle()
        assertEquals(CancelProblem.WINDOW_CLOSED, vm.uiState.value.cancelling?.problem)
        assertEquals(2, gateway.appointmentsCalls)
    }

    @Test
    fun `sin red no envia la cancelacion`() = runTest {
        val gateway = FakeGateway(catalog(), AppointmentsSnapshot(listOf(appointment()), now)).apply {
            catalogResponses += VirtualSessionsRefresh.NoConnection
            appointmentsResponses += VirtualSessionsRefresh.NoConnection
        }
        val vm = viewModel(gateway)
        advanceUntilIdle()
        vm.requestCancel("cita-1")
        vm.confirmCancel()
        advanceUntilIdle()
        assertEquals(CancelProblem.OFFLINE, vm.uiState.value.cancelling?.problem)
        assertTrue(gateway.cancelled.isEmpty())
    }
}
