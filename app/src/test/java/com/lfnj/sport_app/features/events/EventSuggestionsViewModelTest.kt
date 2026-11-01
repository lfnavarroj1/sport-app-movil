package com.lfnj.sport_app.features.events

import com.lfnj.sport_app.features.events.data.EventSuggestionsGateway
import com.lfnj.sport_app.features.events.data.EventSuggestionsRefresh
import com.lfnj.sport_app.features.events.model.EventPlace
import com.lfnj.sport_app.features.events.model.EventPrice
import com.lfnj.sport_app.features.events.model.EventSuggestion
import com.lfnj.sport_app.features.events.model.EventSuggestionsSnapshot
import com.lfnj.sport_app.features.events.model.MyRegistration
import com.lfnj.sport_app.features.recommendations.model.ContextSourceStatus
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

/** HU027: estados de «Eventos sugeridos» (carga, vacío, no personalizada, sin red, `STALE`, errores y reintento). */
@OptIn(ExperimentalCoroutinesApi::class)
class EventSuggestionsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val now = Instant.parse("2026-10-25T15:00:00Z")
    private val clock = MutableClock(now, ZoneId.of("America/Bogota"))

    /** Caché observable y respuestas del BFF programadas por la prueba. */
    private class FakeGateway(initial: EventSuggestionsSnapshot? = null) : EventSuggestionsGateway {
        val cache = MutableStateFlow(initial)
        val responses = ArrayDeque<EventSuggestionsRefresh>()
        var onUpdate: () -> EventSuggestionsSnapshot? = { null }
        var gate: CompletableDeferred<Unit>? = null
        var calls = 0

        override fun observe(owner: String): Flow<EventSuggestionsSnapshot?> = cache

        override suspend fun refresh(owner: String): EventSuggestionsRefresh {
            calls++
            gate?.await()
            val result = responses.removeFirstOrNull() ?: EventSuggestionsRefresh.NoConnection
            if (result == EventSuggestionsRefresh.Updated) cache.value = onUpdate()
            return result
        }
    }

    private fun event(id: String, reasons: List<String> = listOf("SPORT_MATCH", "CITY_MATCH"), registered: Boolean = false) = EventSuggestion(
        eventId = id,
        title = "Evento $id",
        summary = "Descripción sintética.",
        discipline = "Atletismo",
        location = EventPlace("Parque Simón Bolívar", "Bogotá", "CO"),
        startsAt = Instant.parse("2026-11-15T12:00:00Z"),
        endsAt = Instant.parse("2026-11-15T17:00:00Z"),
        timeZone = "America/Bogota",
        capacity = 500,
        fee = EventPrice("45000.00", "COP"),
        myRegistration = if (registered) MyRegistration("registro-$id", Instant.parse("2026-10-21T10:00:00Z")) else null,
        reasons = reasons,
    )

    private fun snapshot(
        items: List<EventSuggestion> = listOf(event("a"), event("b", registered = true)),
        personalized: Boolean = true,
        missing: List<String> = emptyList(),
        syncedAt: Instant = now,
    ) = EventSuggestionsSnapshot(items, personalized, missing, now, ContextSourceStatus.OK, syncedAt)

    @Test
    fun `muestra carga y luego la lista confirmada por el BFF`() = runTest {
        val gateway = FakeGateway().apply {
            gate = CompletableDeferred()
            responses += EventSuggestionsRefresh.Updated
            onUpdate = { snapshot() }
        }
        val viewModel = EventSuggestionsViewModel("owner", gateway, clock)

        assertTrue(viewModel.uiState.value.loading)
        assertTrue(viewModel.uiState.value.refreshing)
        gateway.gate?.complete(Unit)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertFalse(state.refreshing)
        assertNull(state.problem)
        assertEquals(listOf("a", "b"), state.snapshot?.items?.map { it.eventId })
        assertEquals(listOf(false, true), state.snapshot?.items?.map { it.registered })
        assertFalse(state.stale)
        assertEquals(ZoneId.of("America/Bogota"), state.zone)
    }

    @Test
    fun `una lista confirmada sin eventos es un estado vacio`() = runTest {
        val gateway = FakeGateway().apply {
            responses += EventSuggestionsRefresh.Updated
            onUpdate = { snapshot(items = emptyList()) }
        }
        val viewModel = EventSuggestionsViewModel("owner", gateway, clock)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.empty)
        assertNull(viewModel.uiState.value.problem)
    }

    @Test
    fun `la lista no personalizada se expone con su contexto faltante (CA2)`() = runTest {
        val gateway = FakeGateway().apply {
            responses += EventSuggestionsRefresh.Updated
            onUpdate = { snapshot(items = listOf(event("a", reasons = listOf("UPCOMING"))), personalized = false, missing = listOf("sports")) }
        }
        val viewModel = EventSuggestionsViewModel("owner", gateway, clock)
        advanceUntilIdle()

        val shown = checkNotNull(viewModel.uiState.value.snapshot)
        assertFalse(shown.personalized)
        assertEquals(listOf("sports"), shown.missingContext)
    }

    @Test
    fun `sin red y sin cache informa que nunca se sincronizo`() = runTest {
        val viewModel = EventSuggestionsViewModel("owner", FakeGateway(), clock)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.neverSynced)
        assertFalse(viewModel.uiState.value.loading)
        assertFalse(viewModel.uiState.value.empty)
    }

    @Test
    fun `sin red conserva la ultima lista con su antiguedad y la marca STALE a las 24 horas`() = runTest {
        val old = snapshot(syncedAt = now.minus(Duration.ofHours(25)))
        val viewModel = EventSuggestionsViewModel("owner", FakeGateway(old), clock)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(EventSuggestionsProblem.NO_CONNECTION, state.problem)
        assertEquals(old, state.snapshot)
        assertTrue(state.stale)
        assertFalse(state.neverSynced)
    }

    @Test
    fun `una lista de menos de 24 horas no es STALE`() = runTest {
        val viewModel = EventSuggestionsViewModel("owner", FakeGateway(snapshot(syncedAt = now.minus(Duration.ofHours(23)))), clock)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.stale)
    }

    @Test
    fun `un error del BFF conserva la cache y reintentar la actualiza`() = runTest {
        val gateway = FakeGateway(snapshot()).apply {
            responses += EventSuggestionsRefresh.Unavailable("corr")
            responses += EventSuggestionsRefresh.Updated
            onUpdate = { snapshot(items = listOf(event("c"))) }
        }
        val viewModel = EventSuggestionsViewModel("owner", gateway, clock)
        advanceUntilIdle()
        assertEquals(EventSuggestionsProblem.UNAVAILABLE, viewModel.uiState.value.problem)
        assertEquals(2, viewModel.uiState.value.snapshot?.items?.size)

        viewModel.retry()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.problem)
        assertEquals(listOf("c"), viewModel.uiState.value.snapshot?.items?.map { it.eventId })
        assertEquals(2, gateway.calls)
    }

    @Test
    fun `no duplica la consulta mientras otra esta en curso`() = runTest {
        val gateway = FakeGateway().apply { gate = CompletableDeferred() }
        val viewModel = EventSuggestionsViewModel("owner", gateway, clock)

        viewModel.retry()
        viewModel.retry()
        gateway.gate?.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, gateway.calls)
    }

    @Test
    fun `sin la capacidad o sin permiso no muestra datos guardados`() = runTest {
        val gateway = FakeGateway(snapshot()).apply { responses += EventSuggestionsRefresh.CapabilityRequired }
        val viewModel = EventSuggestionsViewModel("owner", gateway, clock)
        advanceUntilIdle()
        assertEquals(EventSuggestionsProblem.CAPABILITY_REQUIRED, viewModel.uiState.value.problem)
        assertNull(viewModel.uiState.value.snapshot)

        gateway.responses += EventSuggestionsRefresh.PermissionRequired
        viewModel.retry()
        advanceUntilIdle()
        assertEquals(EventSuggestionsProblem.PERMISSION_REQUIRED, viewModel.uiState.value.problem)
        assertNull(viewModel.uiState.value.snapshot)
    }

    @Test
    fun `cada falla se traduce a su problema de pantalla`() = runTest {
        val cases = mapOf(
            EventSuggestionsRefresh.InvalidRequest to EventSuggestionsProblem.INVALID_REQUEST,
            EventSuggestionsRefresh.InvalidResponse to EventSuggestionsProblem.INVALID_RESPONSE,
            EventSuggestionsRefresh.StorageFailed to EventSuggestionsProblem.STORAGE,
            EventSuggestionsRefresh.Unavailable(null) to EventSuggestionsProblem.UNAVAILABLE,
        )
        cases.forEach { (refresh, problem) ->
            val viewModel = EventSuggestionsViewModel("owner", FakeGateway().apply { responses += refresh }, clock)
            advanceUntilIdle()
            assertEquals(refresh.toString(), problem, viewModel.uiState.value.problem)
        }
    }

    @Test
    fun `una excepcion inesperada del repositorio se informa como fallo de almacenamiento`() = runTest {
        val gateway = object : EventSuggestionsGateway {
            override fun observe(owner: String): Flow<EventSuggestionsSnapshot?> = MutableStateFlow(null)
            override suspend fun refresh(owner: String): EventSuggestionsRefresh = error("disco")
        }
        val viewModel = EventSuggestionsViewModel("owner", gateway, clock)
        advanceUntilIdle()

        assertEquals(EventSuggestionsProblem.STORAGE, viewModel.uiState.value.problem)
    }

    @Test
    fun `una sesion vencida pide volver al acceso una sola vez`() = runTest {
        val viewModel = EventSuggestionsViewModel("owner", FakeGateway().apply { responses += EventSuggestionsRefresh.SignedOut }, clock)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.sessionExpired)

        viewModel.onSessionExpiredHandled()
        assertFalse(viewModel.uiState.value.sessionExpired)
    }

    @Test
    fun `la pantalla sigue la cache cuando otra consulta la reemplaza`() = runTest {
        val gateway = FakeGateway(snapshot())
        val viewModel = EventSuggestionsViewModel("owner", gateway, clock)
        advanceUntilIdle()

        gateway.cache.value = snapshot(items = listOf(event("b", registered = true)))
        advanceUntilIdle()

        assertEquals(listOf("b"), viewModel.uiState.value.snapshot?.items?.map { it.eventId })
    }

    @Test
    fun `una cache ilegible o ausente no muestra eventos`() = runTest {
        val viewModel = EventSuggestionsViewModel("owner", FakeGateway(null), clock)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.snapshot)
    }
}
