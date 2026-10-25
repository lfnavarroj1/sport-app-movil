package com.lfnj.sport_app.features.training.dashboard

import com.lfnj.sport_app.features.training.dashboard.data.DashboardGateway
import com.lfnj.sport_app.features.training.dashboard.data.DashboardRefresh
import com.lfnj.sport_app.features.training.dashboard.model.CustomRangeError
import com.lfnj.sport_app.features.training.dashboard.model.DashboardPeriod
import com.lfnj.sport_app.features.training.dashboard.model.DashboardRange
import com.lfnj.sport_app.features.training.dashboard.model.DashboardSnapshot
import com.lfnj.sport_app.testing.DashboardFixtures
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
import java.time.ZoneId

/** HU033: estados de `/entrenamientos/tablero` (carga, vacío, sin red, `STALE`, errores, reintento y periodos). */
@OptIn(ExperimentalCoroutinesApi::class)
class TrainingDashboardViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val zone = ZoneId.of("America/Bogota")
    private val now = Instant.parse("2026-10-25T15:00:00Z")
    private val clock = MutableClock(now, zone)
    private val today = LocalDate.of(2026, 10, 25)

    /** Caché observable por opción y respuestas del BFF programadas por la prueba. */
    private class FakeGateway(initial: Map<DashboardRange, DashboardSnapshot> = emptyMap()) : DashboardGateway {
        val cache = DashboardRange.entries.associateWith { MutableStateFlow(initial[it]) }
        val periods = mutableListOf<DashboardPeriod>()
        val responses = ArrayDeque<DashboardRefresh>()
        var onUpdate: (DashboardPeriod) -> DashboardSnapshot? = { null }
        var gate: CompletableDeferred<Unit>? = null

        override fun observe(owner: String, range: DashboardRange): Flow<DashboardSnapshot?> = cache.getValue(range)

        override suspend fun refresh(owner: String, period: DashboardPeriod): DashboardRefresh {
            periods += period
            gate?.await()
            val result = responses.removeFirstOrNull() ?: DashboardRefresh.NoConnection
            if (result == DashboardRefresh.Updated) cache.getValue(period.range).value = onUpdate(period)
            return result
        }
    }

    private fun snapshotOf(period: DashboardPeriod, hasData: Boolean = true, syncedAt: Instant = now) =
        DashboardFixtures.snapshot(range = period.range, from = period.from.toInstant(), to = period.to.toInstant(), hasData = hasData, syncedAt = syncedAt)

    private fun viewModel(gateway: FakeGateway) = TrainingDashboardViewModel("owner", gateway, clock)

    @Test
    fun `abre los ultimos 30 dias de la zona del dispositivo y consulta ese periodo`() = runTest {
        val gateway = FakeGateway()
        val vm = viewModel(gateway)

        assertTrue(vm.uiState.value.loading)
        advanceUntilIdle()

        assertEquals(DashboardRange.LAST_30, vm.uiState.value.range)
        assertEquals(zone, vm.uiState.value.zone)
        assertEquals(DashboardPeriod.preset(DashboardRange.LAST_30, today, zone), gateway.periods.single())
    }

    @Test
    fun `muestra primero la cache y luego el tablero confirmado (CA1)`() = runTest {
        val last30 = DashboardPeriod.preset(DashboardRange.LAST_30, today, zone)
        val gateway = FakeGateway(mapOf(DashboardRange.LAST_30 to snapshotOf(last30, syncedAt = now.minus(Duration.ofHours(2)))))
        val gate = CompletableDeferred<Unit>().also { gateway.gate = it }
        gateway.responses += DashboardRefresh.Updated
        gateway.onUpdate = { snapshotOf(it) }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.refreshing)
        assertEquals(now.minus(Duration.ofHours(2)), vm.uiState.value.snapshot?.syncedAt)

        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.refreshing)
        assertNull(vm.uiState.value.problem)
        assertEquals(now, vm.uiState.value.snapshot?.syncedAt)
        assertEquals(2L, vm.uiState.value.snapshot?.totals?.sessionsCount)
    }

    @Test
    fun `sin datos muestra el vacio confirmado (CA2)`() = runTest {
        val gateway = FakeGateway()
        gateway.responses += DashboardRefresh.Updated
        gateway.onUpdate = { snapshotOf(it, hasData = false) }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.snapshot!!.confirmedEmpty)
        assertFalse(vm.uiState.value.loading)
    }

    @Test
    fun `sin red conserva el tablero con su antiguedad y lo marca STALE despues de 24 horas`() = runTest {
        val last30 = DashboardPeriod.preset(DashboardRange.LAST_30, today.minusDays(2), zone)
        val vm = viewModel(FakeGateway(mapOf(DashboardRange.LAST_30 to snapshotOf(last30, syncedAt = now.minus(Duration.ofHours(25))))))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(DashboardProblem.NO_CONNECTION, state.problem)
        assertEquals(now.minus(Duration.ofHours(25)), state.snapshot?.syncedAt)
        // Las fechas del tablero mostrado son las suyas, no las del periodo pedido hoy.
        assertEquals(today.minusDays(2), state.snapshot?.lastDay(zone))
        assertTrue(state.stale)
        assertFalse(state.neverSynced)
    }

    @Test
    fun `una cache reciente no se marca STALE`() = runTest {
        val last30 = DashboardPeriod.preset(DashboardRange.LAST_30, today, zone)
        val vm = viewModel(FakeGateway(mapOf(DashboardRange.LAST_30 to snapshotOf(last30, syncedAt = now.minus(Duration.ofHours(23))))))
        advanceUntilIdle()

        assertFalse(vm.uiState.value.stale)
    }

    @Test
    fun `sin cache y sin red informa que no hay tablero guardado, no un tablero vacio`() = runTest {
        val vm = viewModel(FakeGateway())
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.neverSynced)
        assertFalse(state.loading)
        assertNull(state.snapshot)
    }

    @Test
    fun `un error temporal se puede reintentar y el reintento confirma el tablero`() = runTest {
        val gateway = FakeGateway()
        gateway.responses += DashboardRefresh.Unavailable("8c1f")
        gateway.responses += DashboardRefresh.Updated
        gateway.onUpdate = { snapshotOf(it) }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        assertEquals(DashboardProblem.UNAVAILABLE, vm.uiState.value.problem)
        assertNull(vm.uiState.value.snapshot)

        vm.retry()
        advanceUntilIdle()

        assertNull(vm.uiState.value.problem)
        assertEquals(2, gateway.periods.size)
        assertTrue(vm.uiState.value.snapshot!!.hasData)
    }

    @Test
    fun `sin capacidad o sin permiso no se muestran datos guardados`() = runTest {
        val last30 = DashboardPeriod.preset(DashboardRange.LAST_30, today, zone)
        listOf(DashboardRefresh.CapabilityRequired to DashboardProblem.CAPABILITY_REQUIRED, DashboardRefresh.PermissionRequired to DashboardProblem.PERMISSION_REQUIRED)
            .forEach { (response, problem) ->
                val gateway = FakeGateway(mapOf(DashboardRange.LAST_30 to snapshotOf(last30)))
                gateway.responses += response
                val vm = viewModel(gateway)
                advanceUntilIdle()

                assertEquals(problem, vm.uiState.value.problem)
                assertNull(vm.uiState.value.snapshot)
            }
    }

    @Test
    fun `un 422 se informa como periodo invalido y la sesion vencida vuelve al acceso`() = runTest {
        val gateway = FakeGateway()
        gateway.responses += DashboardRefresh.InvalidPeriod(listOf("out_of_range"))
        gateway.responses += DashboardRefresh.SignedOut
        val vm = viewModel(gateway)
        advanceUntilIdle()
        assertEquals(DashboardProblem.INVALID_PERIOD, vm.uiState.value.problem)

        vm.retry()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.sessionExpired)
        vm.onSessionExpiredHandled()
        assertFalse(vm.uiState.value.sessionExpired)
    }

    @Test
    fun `cambiar a 7 o 90 dias consulta el nuevo periodo y descarta la respuesta del anterior`() = runTest {
        val gateway = FakeGateway()
        val gate = CompletableDeferred<Unit>().also { gateway.gate = it }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        gateway.gate = null
        gateway.responses += DashboardRefresh.Updated
        gateway.onUpdate = { snapshotOf(it, hasData = false) }
        vm.selectRange(DashboardRange.LAST_7)
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(DashboardRange.LAST_30, DashboardRange.LAST_7), gateway.periods.map { it.range })
        assertEquals(DashboardRange.LAST_7, vm.uiState.value.range)
        assertEquals(DashboardRange.LAST_7, vm.uiState.value.snapshot?.range)
        assertNull(vm.uiState.value.problem)

        vm.selectRange(DashboardRange.LAST_90)
        advanceUntilIdle()
        assertEquals(DashboardPeriod.preset(DashboardRange.LAST_90, today, zone), gateway.periods.last())
        assertNull(vm.uiState.value.snapshot)
        assertTrue(vm.uiState.value.neverSynced)
    }

    @Test
    fun `personalizado espera las fechas y no consulta hasta tener un rango valido`() = runTest {
        val gateway = FakeGateway()
        val vm = viewModel(gateway)
        advanceUntilIdle()

        vm.selectRange(DashboardRange.CUSTOM)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.awaitingCustomRange)
        assertFalse(vm.uiState.value.loading)
        assertEquals(1, gateway.periods.size)

        vm.selectCustomRange(today, today.minusDays(3))
        assertEquals(CustomRangeError.END_BEFORE_START, vm.uiState.value.customError)
        vm.dismissCustomError()
        vm.selectCustomRange(today.minusDays(400), today)
        assertEquals(CustomRangeError.TOO_LONG, vm.uiState.value.customError)
        advanceUntilIdle()
        assertEquals(1, gateway.periods.size)
    }

    @Test
    fun `un rango personalizado valido se consulta y su cache solo se muestra para esas fechas`() = runTest {
        val gateway = FakeGateway()
        gateway.responses += DashboardRefresh.Updated
        gateway.onUpdate = { snapshotOf(it) }
        val vm = viewModel(gateway)
        advanceUntilIdle()
        gateway.responses.clear()
        gateway.responses += DashboardRefresh.Updated

        vm.selectCustomRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))
        advanceUntilIdle()

        val september = checkNotNull(DashboardPeriod.custom(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), zone))
        assertEquals(september, gateway.periods.last())
        assertEquals(LocalDate.of(2026, 9, 1), vm.uiState.value.customStart)
        assertEquals(september.from.toInstant(), vm.uiState.value.snapshot?.from)

        // Sin red, otras fechas no muestran el tablero de septiembre como si fuera el suyo.
        vm.selectCustomRange(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31))
        advanceUntilIdle()
        assertNull(vm.uiState.value.snapshot)
        assertTrue(vm.uiState.value.neverSynced)

        // Al volver de un periodo predefinido se conserva el último rango elegido.
        vm.selectRange(DashboardRange.LAST_7)
        vm.selectRange(DashboardRange.CUSTOM)
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 8, 1), vm.uiState.value.customStart)
        assertFalse(vm.uiState.value.awaitingCustomRange)
    }

    @Test
    fun `la cache de otra opcion no se muestra como la elegida`() = runTest {
        val last7 = DashboardPeriod.preset(DashboardRange.LAST_7, today, zone)
        val vm = viewModel(FakeGateway(mapOf(DashboardRange.LAST_7 to snapshotOf(last7))))
        advanceUntilIdle()

        assertNull(vm.uiState.value.snapshot)
        vm.selectRange(DashboardRange.LAST_7)
        advanceUntilIdle()
        assertEquals(DashboardRange.LAST_7, vm.uiState.value.snapshot?.range)
    }
}
