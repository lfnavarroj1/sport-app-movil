package com.lfnj.sport_app.features.recommendations

import com.lfnj.sport_app.features.recommendations.data.RouteSuggestionsGateway
import com.lfnj.sport_app.features.recommendations.data.RouteSuggestionsRefresh
import com.lfnj.sport_app.features.recommendations.model.ContextSourceStatus
import com.lfnj.sport_app.features.recommendations.model.RoutePoint
import com.lfnj.sport_app.features.recommendations.model.RouteSuggestion
import com.lfnj.sport_app.features.recommendations.model.RouteSuggestionsSnapshot
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
import java.time.ZoneId

/** HU026: estados de «Rutas sugeridas» (carga, vacío, no personalizada, sin red, `STALE`, errores y detalle). */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteSuggestionsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val now = Instant.parse("2026-10-25T15:00:00Z")
    private val clock = MutableClock(now, ZoneId.of("America/Bogota"))

    /** Caché observable y respuestas del BFF programadas por la prueba. */
    private class FakeGateway(initial: RouteSuggestionsSnapshot? = null) : RouteSuggestionsGateway {
        val cache = MutableStateFlow(initial)
        val responses = ArrayDeque<RouteSuggestionsRefresh>()
        var onUpdate: () -> RouteSuggestionsSnapshot? = { null }
        var gate: CompletableDeferred<Unit>? = null
        var calls = 0

        override fun observe(owner: String): Flow<RouteSuggestionsSnapshot?> = cache

        override suspend fun refresh(owner: String): RouteSuggestionsRefresh {
            calls++
            gate?.await()
            val result = responses.removeFirstOrNull() ?: RouteSuggestionsRefresh.NoConnection
            if (result == RouteSuggestionsRefresh.Updated) cache.value = onUpdate()
            return result
        }
    }

    private fun route(id: String, reasons: List<String> = listOf("SPORT_MATCH", "CITY_MATCH")) = RouteSuggestion(
        routeId = id,
        title = "Ruta $id",
        summary = "Descripción sintética.",
        sport = "running",
        distanceKm = "5.0",
        difficulty = "LOW",
        city = "Bogotá",
        countryCode = "CO",
        startPoint = RoutePoint(4.6097, -74.0817),
        waypoints = listOf(RoutePoint(4.6584, -74.0937)),
        publishedAt = Instant.parse("2026-10-20T14:00:00Z"),
        authorName = "Carlos Ruiz",
        reasons = reasons,
    )

    private fun snapshot(
        items: List<RouteSuggestion> = listOf(route("a"), route("b")),
        personalized: Boolean = true,
        missing: List<String> = emptyList(),
        syncedAt: Instant = now,
    ) = RouteSuggestionsSnapshot(items, personalized, missing, now, ContextSourceStatus.OK, syncedAt)

    @Test
    fun `muestra carga y luego la lista confirmada por el BFF`() = runTest {
        val gateway = FakeGateway().apply {
            gate = CompletableDeferred()
            responses += RouteSuggestionsRefresh.Updated
            onUpdate = { snapshot() }
        }
        val viewModel = RouteSuggestionsViewModel("owner", gateway, clock)

        assertTrue(viewModel.uiState.value.loading)
        assertTrue(viewModel.uiState.value.refreshing)
        gateway.gate?.complete(Unit)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertFalse(state.refreshing)
        assertNull(state.problem)
        assertEquals(listOf("a", "b"), state.snapshot?.items?.map { it.routeId })
        assertFalse(state.stale)
    }

    @Test
    fun `una lista confirmada sin rutas es un estado vacio`() = runTest {
        val gateway = FakeGateway().apply {
            responses += RouteSuggestionsRefresh.Updated
            onUpdate = { snapshot(items = emptyList()) }
        }
        val viewModel = RouteSuggestionsViewModel("owner", gateway, clock)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.empty)
        assertNull(viewModel.uiState.value.problem)
    }

    @Test
    fun `la lista no personalizada se expone con su contexto faltante (CA3)`() = runTest {
        val gateway = FakeGateway().apply {
            responses += RouteSuggestionsRefresh.Updated
            onUpdate = { snapshot(items = listOf(route("a", reasons = emptyList())), personalized = false, missing = listOf("sports")) }
        }
        val viewModel = RouteSuggestionsViewModel("owner", gateway, clock)
        advanceUntilIdle()

        val shown = checkNotNull(viewModel.uiState.value.snapshot)
        assertFalse(shown.personalized)
        assertEquals(listOf("sports"), shown.missingContext)
    }

    @Test
    fun `sin red y sin cache informa que nunca se sincronizo`() = runTest {
        val viewModel = RouteSuggestionsViewModel("owner", FakeGateway(), clock)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.neverSynced)
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test
    fun `sin red conserva la ultima lista con su antiguedad y la marca STALE a las 24 horas`() = runTest {
        val old = snapshot(syncedAt = now.minus(Duration.ofHours(25)))
        val viewModel = RouteSuggestionsViewModel("owner", FakeGateway(old), clock)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(RouteSuggestionsProblem.NO_CONNECTION, state.problem)
        assertEquals(old, state.snapshot)
        assertTrue(state.stale)
        assertFalse(state.neverSynced)
    }

    @Test
    fun `una lista de menos de 24 horas no es STALE`() = runTest {
        val viewModel = RouteSuggestionsViewModel("owner", FakeGateway(snapshot(syncedAt = now.minus(Duration.ofHours(23)))), clock)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.stale)
    }

    @Test
    fun `un error del BFF conserva la cache y reintentar la actualiza`() = runTest {
        val gateway = FakeGateway(snapshot()).apply {
            responses += RouteSuggestionsRefresh.Unavailable("corr")
            responses += RouteSuggestionsRefresh.Updated
            onUpdate = { snapshot(items = listOf(route("c"))) }
        }
        val viewModel = RouteSuggestionsViewModel("owner", gateway, clock)
        advanceUntilIdle()
        assertEquals(RouteSuggestionsProblem.UNAVAILABLE, viewModel.uiState.value.problem)
        assertEquals(2, viewModel.uiState.value.snapshot?.items?.size)

        viewModel.retry()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.problem)
        assertEquals(listOf("c"), viewModel.uiState.value.snapshot?.items?.map { it.routeId })
        assertEquals(2, gateway.calls)
    }

    @Test
    fun `no duplica la consulta mientras otra esta en curso`() = runTest {
        val gateway = FakeGateway().apply { gate = CompletableDeferred() }
        val viewModel = RouteSuggestionsViewModel("owner", gateway, clock)

        viewModel.retry()
        viewModel.retry()
        gateway.gate?.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, gateway.calls)
    }

    @Test
    fun `sin la capacidad o sin permiso no muestra datos guardados`() = runTest {
        val gateway = FakeGateway(snapshot()).apply { responses += RouteSuggestionsRefresh.CapabilityRequired }
        val viewModel = RouteSuggestionsViewModel("owner", gateway, clock)
        advanceUntilIdle()
        assertEquals(RouteSuggestionsProblem.CAPABILITY_REQUIRED, viewModel.uiState.value.problem)
        assertNull(viewModel.uiState.value.snapshot)

        gateway.responses += RouteSuggestionsRefresh.PermissionRequired
        viewModel.retry()
        advanceUntilIdle()
        assertEquals(RouteSuggestionsProblem.PERMISSION_REQUIRED, viewModel.uiState.value.problem)
        assertNull(viewModel.uiState.value.snapshot)
    }

    @Test
    fun `cada falla se traduce a su problema de pantalla`() = runTest {
        val cases = mapOf(
            RouteSuggestionsRefresh.InvalidRequest to RouteSuggestionsProblem.INVALID_REQUEST,
            RouteSuggestionsRefresh.InvalidResponse to RouteSuggestionsProblem.INVALID_RESPONSE,
            RouteSuggestionsRefresh.StorageFailed to RouteSuggestionsProblem.STORAGE,
            RouteSuggestionsRefresh.Unavailable(null) to RouteSuggestionsProblem.UNAVAILABLE,
        )
        cases.forEach { (refresh, problem) ->
            val viewModel = RouteSuggestionsViewModel("owner", FakeGateway().apply { responses += refresh }, clock)
            advanceUntilIdle()
            assertEquals(refresh.toString(), problem, viewModel.uiState.value.problem)
        }
    }

    @Test
    fun `una excepcion inesperada del repositorio se informa como fallo de almacenamiento`() = runTest {
        val gateway = object : RouteSuggestionsGateway {
            override fun observe(owner: String): Flow<RouteSuggestionsSnapshot?> = MutableStateFlow(null)
            override suspend fun refresh(owner: String): RouteSuggestionsRefresh = error("disco")
        }
        val viewModel = RouteSuggestionsViewModel("owner", gateway, clock)
        advanceUntilIdle()

        assertEquals(RouteSuggestionsProblem.STORAGE, viewModel.uiState.value.problem)
    }

    @Test
    fun `una sesion vencida pide volver al acceso una sola vez`() = runTest {
        val viewModel = RouteSuggestionsViewModel("owner", FakeGateway().apply { responses += RouteSuggestionsRefresh.SignedOut }, clock)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.sessionExpired)

        viewModel.onSessionExpiredHandled()
        assertFalse(viewModel.uiState.value.sessionExpired)
    }

    @Test
    fun `abre y cierra el detalle de una ruta`() = runTest {
        val viewModel = RouteSuggestionsViewModel("owner", FakeGateway(snapshot()), clock)
        advanceUntilIdle()

        viewModel.openDetail("b")
        assertEquals("b", viewModel.uiState.value.selected?.routeId)

        viewModel.closeDetail()
        assertNull(viewModel.uiState.value.selected)
    }

    @Test
    fun `una ruta retirada que deja de sugerirse cierra su detalle (CA4)`() = runTest {
        val gateway = FakeGateway(snapshot())
        val viewModel = RouteSuggestionsViewModel("owner", gateway, clock)
        advanceUntilIdle()
        viewModel.openDetail("a")

        gateway.cache.value = snapshot(items = listOf(route("b")))
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.selected)
        gateway.cache.value = snapshot()
        advanceUntilIdle()
        assertNull("no reabre el detalle por sí solo", viewModel.uiState.value.selected)
    }

    @Test
    fun `una cache ilegible o ausente no muestra rutas`() = runTest {
        val viewModel = RouteSuggestionsViewModel("owner", FakeGateway(null), clock)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.snapshot)
        viewModel.openDetail("a")
        assertNull(viewModel.uiState.value.selected)
    }
}
