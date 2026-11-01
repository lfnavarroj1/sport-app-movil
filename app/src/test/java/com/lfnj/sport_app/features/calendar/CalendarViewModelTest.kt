package com.lfnj.sport_app.features.calendar

import com.lfnj.sport_app.features.calendar.data.CalendarGateway
import com.lfnj.sport_app.features.calendar.data.CalendarRefresh
import com.lfnj.sport_app.features.calendar.model.CalendarItem
import com.lfnj.sport_app.features.calendar.model.CalendarPeriod
import com.lfnj.sport_app.features.calendar.model.CalendarSnapshot
import com.lfnj.sport_app.features.calendar.model.CalendarSourceState
import com.lfnj.sport_app.features.calendar.model.EventLocation
import com.lfnj.sport_app.features.calendar.model.SourceStatus
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** HU035: estados de la pantalla `/calendario` (carga, vacío, sin red, `STALE`, errores y cambio de mes). */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val zone = ZoneId.of("America/Bogota")
    private val now = Instant.parse("2026-11-10T15:00:00Z")
    private val clock = MutableClock(now, zone)
    private val november = YearMonth.of(2026, 11)

    /** Caché observable y respuestas del BFF programadas por la prueba. */
    private class FakeGateway(initial: CalendarSnapshot? = null) : CalendarGateway {
        val cache = MutableStateFlow(initial)
        val periods = mutableListOf<CalendarPeriod>()
        val responses = ArrayDeque<CalendarRefresh>()
        var onUpdate: (CalendarPeriod) -> CalendarSnapshot? = { null }
        var gate: CompletableDeferred<Unit>? = null

        override fun observe(owner: String): Flow<CalendarSnapshot?> = cache

        override suspend fun refresh(owner: String, period: CalendarPeriod): CalendarRefresh {
            periods += period
            gate?.await()
            val result = responses.removeFirstOrNull() ?: CalendarRefresh.NoConnection
            if (result == CalendarRefresh.Updated) cache.value = onUpdate(period)
            return result
        }
    }

    private fun snapshot(
        month: YearMonth = november,
        items: List<CalendarItem> = listOf(event()),
        syncedAt: Instant = now.minus(Duration.ofHours(1)),
        training: SourceStatus = SourceStatus.OK,
    ) = CalendarSnapshot(
        month, Instant.EPOCH, Instant.EPOCH, items,
        listOf(CalendarSourceState("events", SourceStatus.OK, false), CalendarSourceState("training", training, false)),
        Instant.EPOCH, syncedAt,
    )

    private fun event() = CalendarItem.Event(
        "EVENT_PUBLISHED:1", LocalDate.of(2026, 11, 15), "Carrera 10K", "Running",
        Instant.parse("2026-11-15T12:00:00Z"), Instant.parse("2026-11-15T15:00:00Z"), "America/Bogota",
        EventLocation("Parque", "Bogotá", "CO"),
    )

    private fun viewModel(gateway: FakeGateway) = CalendarViewModel("owner", gateway, clock)

    @Test
    fun `abre el mes actual de la zona del dispositivo y consulta ese periodo`() = runTest {
        val gateway = FakeGateway()
        val vm = viewModel(gateway)

        assertTrue(vm.uiState.value.loading)
        advanceUntilIdle()

        assertEquals(november, vm.uiState.value.month)
        assertEquals(zone, vm.uiState.value.zone)
        assertEquals(CalendarPeriod.forMonth(november, zone), gateway.periods.single())
    }

    @Test
    fun `muestra primero la cache y luego el periodo confirmado`() = runTest {
        val gateway = FakeGateway(snapshot(syncedAt = now.minus(Duration.ofHours(2))))
        val gate = CompletableDeferred<Unit>().also { gateway.gate = it }
        gateway.responses += CalendarRefresh.Updated
        gateway.onUpdate = { snapshot(syncedAt = now) }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.refreshing)
        assertEquals(now.minus(Duration.ofHours(2)), vm.uiState.value.snapshot?.syncedAt)
        assertEquals(listOf(LocalDate.of(2026, 11, 15)), vm.uiState.value.days.map { it.date })

        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.refreshing)
        assertNull(vm.uiState.value.problem)
        assertEquals(now, vm.uiState.value.snapshot?.syncedAt)
    }

    @Test
    fun `sin red conserva el periodo con su antiguedad y lo marca STALE despues de 24 horas (CA8)`() = runTest {
        val gateway = FakeGateway(snapshot(syncedAt = now.minus(Duration.ofHours(25))))
        val vm = viewModel(gateway)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(CalendarProblem.NO_CONNECTION, state.problem)
        assertEquals(now.minus(Duration.ofHours(25)), state.snapshot?.syncedAt)
        assertTrue(state.stale)
        assertFalse(state.neverSynced)
    }

    @Test
    fun `una cache reciente no se marca STALE`() = runTest {
        val vm = viewModel(FakeGateway(snapshot(syncedAt = now.minus(Duration.ofHours(23)))))
        advanceUntilIdle()

        assertFalse(vm.uiState.value.stale)
    }

    @Test
    fun `sin cache y sin red informa que aun no se sincronizo, no un calendario vacio (CA8)`() = runTest {
        val vm = viewModel(FakeGateway())
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.neverSynced)
        assertFalse(state.loading)
        assertNull(state.snapshot)
    }

    @Test
    fun `sin red en otro mes ofrece el periodo sincronizado y lo muestra al pedirlo`() = runTest {
        val gateway = FakeGateway(snapshot(month = YearMonth.of(2026, 10), items = emptyList()))
        val vm = viewModel(gateway)
        advanceUntilIdle()

        assertNull(vm.uiState.value.snapshot)
        assertEquals(YearMonth.of(2026, 10), vm.uiState.value.syncedMonth)
        assertFalse(vm.uiState.value.neverSynced)

        vm.showSyncedPeriod()
        advanceUntilIdle()

        assertEquals(YearMonth.of(2026, 10), vm.uiState.value.month)
        assertEquals(YearMonth.of(2026, 10), vm.uiState.value.snapshot?.month)
        assertEquals(CalendarProblem.NO_CONNECTION, vm.uiState.value.problem)
    }

    @Test
    fun `cambiar de mes consulta el nuevo periodo y descarta la respuesta del anterior`() = runTest {
        val gateway = FakeGateway()
        val gate = CompletableDeferred<Unit>().also { gateway.gate = it }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        gateway.gate = null
        gateway.responses += CalendarRefresh.Updated
        gateway.onUpdate = { snapshot(month = it.month, items = emptyList()) }
        vm.nextMonth()
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(november, november.plusMonths(1)), gateway.periods.map { it.month })
        assertEquals(november.plusMonths(1), vm.uiState.value.month)
        assertTrue(vm.uiState.value.snapshot!!.confirmedEmpty)
        assertNull(vm.uiState.value.problem)

        vm.previousMonth()
        vm.previousMonth()
        advanceUntilIdle()
        assertEquals(november.minusMonths(1), vm.uiState.value.month)
        vm.currentMonth()
        advanceUntilIdle()
        assertEquals(november, vm.uiState.value.month)
    }

    @Test
    fun `la cache de otro mes no se muestra como el mes visible`() = runTest {
        val vm = viewModel(FakeGateway(snapshot(month = YearMonth.of(2026, 12))))
        advanceUntilIdle()

        assertNull(vm.uiState.value.snapshot)
        assertTrue(vm.uiState.value.days.isEmpty())
    }

    @Test
    fun `una fuente incompleta se refleja en el estado y no como vacio (CA7)`() = runTest {
        val vm = viewModel(FakeGateway(snapshot(items = emptyList(), training = SourceStatus.TIMEOUT)))
        advanceUntilIdle()

        val snapshot = checkNotNull(vm.uiState.value.snapshot)
        assertEquals(listOf("training"), snapshot.incompleteSources)
        assertFalse(snapshot.confirmedEmpty)
    }

    @Test
    fun `sin la capacidad no se muestran datos guardados`() = runTest {
        val gateway = FakeGateway(snapshot())
        gateway.responses += CalendarRefresh.CapabilityRequired
        val vm = viewModel(gateway)
        advanceUntilIdle()

        assertEquals(CalendarProblem.CAPABILITY_REQUIRED, vm.uiState.value.problem)
        assertNull(vm.uiState.value.snapshot)
        assertNull(vm.uiState.value.syncedMonth)
    }

    @Test
    fun `cada falla del BFF tiene su estado y se puede reintentar`() = runTest {
        val gateway = FakeGateway()
        gateway.responses += listOf(
            CalendarRefresh.Unavailable("c-1"), CalendarRefresh.PermissionRequired, CalendarRefresh.InvalidPeriod(listOf("out_of_range")),
            CalendarRefresh.InvalidResponse, CalendarRefresh.StorageFailed,
        )
        val vm = viewModel(gateway)
        val seen = mutableListOf<CalendarProblem?>()
        advanceUntilIdle()
        seen += vm.uiState.value.problem
        repeat(4) {
            vm.retry()
            advanceUntilIdle()
            seen += vm.uiState.value.problem
        }

        assertEquals(
            listOf(
                CalendarProblem.UNAVAILABLE, CalendarProblem.PERMISSION_REQUIRED, CalendarProblem.INVALID_PERIOD,
                CalendarProblem.INVALID_RESPONSE, CalendarProblem.STORAGE,
            ),
            seen,
        )
        assertEquals(5, gateway.periods.size)
    }

    @Test
    fun `un reintento durante una consulta en curso no la duplica`() = runTest {
        val gateway = FakeGateway()
        gateway.gate = CompletableDeferred()
        val vm = viewModel(gateway)
        advanceUntilIdle()

        vm.retry()
        vm.retry()
        advanceUntilIdle()

        assertEquals(1, gateway.periods.size)
    }

    @Test
    fun `una sesion vencida pide volver al acceso`() = runTest {
        val gateway = FakeGateway()
        gateway.responses += CalendarRefresh.SignedOut
        val vm = viewModel(gateway)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.sessionExpired)
        vm.onSessionExpiredHandled()
        assertFalse(vm.uiState.value.sessionExpired)
    }

    @Test
    fun `el modo y el dia seleccionado se conservan hasta cambiar de mes`() = runTest {
        val vm = viewModel(FakeGateway(snapshot()))
        advanceUntilIdle()

        vm.setMode(CalendarMode.LIST)
        vm.selectDay(LocalDate.of(2026, 11, 15))
        assertEquals(CalendarMode.LIST, vm.uiState.value.mode)
        assertEquals(LocalDate.of(2026, 11, 15), vm.uiState.value.selectedDay)

        vm.nextMonth()
        assertEquals(CalendarMode.LIST, vm.uiState.value.mode)
        assertNull(vm.uiState.value.selectedDay)
    }
}
