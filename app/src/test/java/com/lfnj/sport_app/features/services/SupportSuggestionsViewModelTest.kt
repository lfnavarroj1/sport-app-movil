package com.lfnj.sport_app.features.services

import com.lfnj.sport_app.features.services.data.SupportSuggestionsGateway
import com.lfnj.sport_app.features.services.data.SupportSuggestionsRefresh
import com.lfnj.sport_app.features.services.model.ServiceFee
import com.lfnj.sport_app.features.services.model.SupportInterval
import com.lfnj.sport_app.features.services.model.SupportServiceSuggestion
import com.lfnj.sport_app.features.services.model.SupportSourceStatus
import com.lfnj.sport_app.features.services.model.SupportSuggestionsSnapshot
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

/** HU029: estados de «Acompañamiento y logística» (carga, vacía explicada, sin red, `STALE`, errores y detalle). */
@OptIn(ExperimentalCoroutinesApi::class)
class SupportSuggestionsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val now = Instant.parse("2026-10-25T15:00:00Z")
    private val clock = MutableClock(now, ZoneId.of("America/Bogota"))

    /** Caché observable y respuestas del BFF programadas por la prueba. */
    private class FakeGateway(initial: SupportSuggestionsSnapshot? = null) : SupportSuggestionsGateway {
        val cache = MutableStateFlow(initial)
        val responses = ArrayDeque<SupportSuggestionsRefresh>()
        var onUpdate: () -> SupportSuggestionsSnapshot? = { null }
        var gate: CompletableDeferred<Unit>? = null
        var calls = 0

        override fun observe(owner: String): Flow<SupportSuggestionsSnapshot?> = cache

        override suspend fun refresh(owner: String): SupportSuggestionsRefresh {
            calls++
            gate?.await()
            val result = responses.removeFirstOrNull() ?: SupportSuggestionsRefresh.NoConnection
            if (result == SupportSuggestionsRefresh.Updated) cache.value = onUpdate()
            return result
        }
    }

    private fun service(id: String, category: String = "TRANSPORT", reasons: List<String> = listOf("EVENT_LOGISTICS")) = SupportServiceSuggestion(
        serviceId = id,
        title = "Servicio $id",
        summary = "Descripción sintética del servicio.",
        category = category,
        modality = "IN_PERSON",
        fee = ServiceFee("80000.00", "COP", "SERVICE"),
        nextInterval = SupportInterval(Instant.parse("2026-11-08T10:00:00Z"), Instant.parse("2026-11-08T12:00:00Z"), ZoneId.of("America/Bogota")),
        authorName = "Carlos Rodríguez",
        reasons = reasons,
    )

    private fun snapshot(
        items: List<SupportServiceSuggestion> = listOf(service("a"), service("b", "COACHING", listOf("UPCOMING_AVAILABILITY"))),
        personalized: Boolean = true,
        missing: List<String> = emptyList(),
        events: SupportSourceStatus? = SupportSourceStatus.OK,
        syncedAt: Instant = now,
    ) = SupportSuggestionsSnapshot(items, personalized, missing, now, events, SupportSourceStatus.OK, syncedAt)

    @Test
    fun `muestra carga y luego la lista confirmada por el BFF`() = runTest {
        val gateway = FakeGateway().apply {
            gate = CompletableDeferred()
            responses += SupportSuggestionsRefresh.Updated
            onUpdate = { snapshot() }
        }
        val viewModel = SupportSuggestionsViewModel("owner", gateway, clock)

        assertTrue(viewModel.uiState.value.loading)
        assertTrue(viewModel.uiState.value.refreshing)
        gateway.gate?.complete(Unit)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertFalse(state.refreshing)
        assertNull(state.problem)
        assertEquals(listOf("a", "b"), state.snapshot?.items?.map { it.serviceId })
        assertFalse(state.stale)
        assertEquals(ZoneId.of("America/Bogota"), state.zone)
    }

    @Test
    fun `sin coincidencias la lista confirmada vacia se explica con su contexto faltante (CA2)`() = runTest {
        val gateway = FakeGateway().apply {
            responses += SupportSuggestionsRefresh.Updated
            onUpdate = { snapshot(items = emptyList(), personalized = false, missing = listOf("event_registrations")) }
        }
        val viewModel = SupportSuggestionsViewModel("owner", gateway, clock)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.empty)
        assertNull(state.problem)
        assertTrue(checkNotNull(state.snapshot).missingRegistrations)
    }

    @Test
    fun `si Eventos no respondio la lista lo declara (CA5)`() = runTest {
        val gateway = FakeGateway().apply {
            responses += SupportSuggestionsRefresh.Updated
            onUpdate = { snapshot(items = listOf(service("b", "COACHING", listOf("UPCOMING_AVAILABILITY"))), personalized = false, missing = listOf("event_registrations"), events = SupportSourceStatus.UNAVAILABLE) }
        }
        val viewModel = SupportSuggestionsViewModel("owner", gateway, clock)
        advanceUntilIdle()

        assertTrue(checkNotNull(viewModel.uiState.value.snapshot).eventsUnavailable)
    }

    @Test
    fun `sin red y sin cache informa que nunca se sincronizo`() = runTest {
        val viewModel = SupportSuggestionsViewModel("owner", FakeGateway(), clock)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.neverSynced)
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test
    fun `sin red conserva la ultima lista con su antiguedad y la marca STALE a las 24 horas`() = runTest {
        val old = snapshot(syncedAt = now.minus(Duration.ofHours(25)))
        val viewModel = SupportSuggestionsViewModel("owner", FakeGateway(old), clock)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(SupportSuggestionsProblem.NO_CONNECTION, state.problem)
        assertEquals(old, state.snapshot)
        assertTrue(state.stale)
        assertFalse(state.neverSynced)
    }

    @Test
    fun `una lista de menos de 24 horas no es STALE`() = runTest {
        val viewModel = SupportSuggestionsViewModel("owner", FakeGateway(snapshot(syncedAt = now.minus(Duration.ofHours(23)))), clock)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.stale)
    }

    @Test
    fun `Servicios caido conserva la cache y reintentar la actualiza (CA5)`() = runTest {
        val gateway = FakeGateway(snapshot()).apply {
            responses += SupportSuggestionsRefresh.Unavailable("corr")
            responses += SupportSuggestionsRefresh.Updated
            onUpdate = { snapshot(items = listOf(service("c"))) }
        }
        val viewModel = SupportSuggestionsViewModel("owner", gateway, clock)
        advanceUntilIdle()
        assertEquals(SupportSuggestionsProblem.UNAVAILABLE, viewModel.uiState.value.problem)
        assertEquals(2, viewModel.uiState.value.snapshot?.items?.size)

        viewModel.retry()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.problem)
        assertEquals(listOf("c"), viewModel.uiState.value.snapshot?.items?.map { it.serviceId })
        assertEquals(2, gateway.calls)
    }

    @Test
    fun `no duplica la consulta mientras otra esta en curso`() = runTest {
        val gateway = FakeGateway().apply { gate = CompletableDeferred() }
        val viewModel = SupportSuggestionsViewModel("owner", gateway, clock)

        viewModel.retry()
        viewModel.retry()
        gateway.gate?.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, gateway.calls)
    }

    @Test
    fun `sin la capacidad o sin permiso no muestra datos guardados`() = runTest {
        val gateway = FakeGateway(snapshot()).apply { responses += SupportSuggestionsRefresh.CapabilityRequired }
        val viewModel = SupportSuggestionsViewModel("owner", gateway, clock)
        advanceUntilIdle()
        assertEquals(SupportSuggestionsProblem.CAPABILITY_REQUIRED, viewModel.uiState.value.problem)
        assertNull(viewModel.uiState.value.snapshot)

        gateway.responses += SupportSuggestionsRefresh.PermissionRequired
        viewModel.retry()
        advanceUntilIdle()
        assertEquals(SupportSuggestionsProblem.PERMISSION_REQUIRED, viewModel.uiState.value.problem)
        assertNull(viewModel.uiState.value.snapshot)
    }

    @Test
    fun `cada falla se traduce a su problema de pantalla`() = runTest {
        val cases = mapOf(
            SupportSuggestionsRefresh.InvalidRequest to SupportSuggestionsProblem.INVALID_REQUEST,
            SupportSuggestionsRefresh.InvalidResponse to SupportSuggestionsProblem.INVALID_RESPONSE,
            SupportSuggestionsRefresh.StorageFailed to SupportSuggestionsProblem.STORAGE,
            SupportSuggestionsRefresh.Unavailable(null) to SupportSuggestionsProblem.UNAVAILABLE,
        )
        cases.forEach { (refresh, problem) ->
            val viewModel = SupportSuggestionsViewModel("owner", FakeGateway().apply { responses += refresh }, clock)
            advanceUntilIdle()
            assertEquals(refresh.toString(), problem, viewModel.uiState.value.problem)
        }
    }

    @Test
    fun `una excepcion inesperada al consultar se informa como falla de almacenamiento`() = runTest {
        val gateway = object : SupportSuggestionsGateway {
            override fun observe(owner: String): Flow<SupportSuggestionsSnapshot?> = MutableStateFlow(null)
            override suspend fun refresh(owner: String): SupportSuggestionsRefresh = error("fallo")
        }
        val viewModel = SupportSuggestionsViewModel("owner", gateway, clock)
        advanceUntilIdle()

        assertEquals(SupportSuggestionsProblem.STORAGE, viewModel.uiState.value.problem)
    }

    @Test
    fun `una sesion vencida pide volver al acceso una sola vez`() = runTest {
        val viewModel = SupportSuggestionsViewModel("owner", FakeGateway().apply { responses += SupportSuggestionsRefresh.SignedOut }, clock)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.sessionExpired)

        viewModel.onSessionExpiredHandled()
        assertFalse(viewModel.uiState.value.sessionExpired)
    }

    @Test
    fun `abre y cierra el detalle informativo de un servicio (CA3)`() = runTest {
        val viewModel = SupportSuggestionsViewModel("owner", FakeGateway(snapshot()), clock)
        advanceUntilIdle()

        viewModel.openDetail("b")
        assertEquals("b", viewModel.uiState.value.selected?.serviceId)

        viewModel.closeDetail()
        assertNull(viewModel.uiState.value.selected)
    }

    @Test
    fun `un servicio que deja de sugerirse cierra su detalle (CA4)`() = runTest {
        val gateway = FakeGateway(snapshot())
        val viewModel = SupportSuggestionsViewModel("owner", gateway, clock)
        advanceUntilIdle()
        viewModel.openDetail("a")

        gateway.cache.value = snapshot(items = listOf(service("b")))
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.selected)
        gateway.cache.value = snapshot()
        advanceUntilIdle()
        assertNull("no reabre el detalle por sí solo", viewModel.uiState.value.selected)
    }

    @Test
    fun `una cache ilegible o ausente no muestra servicios`() = runTest {
        val viewModel = SupportSuggestionsViewModel("owner", FakeGateway(null), clock)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.snapshot)
        viewModel.openDetail("a")
        assertNull(viewModel.uiState.value.selected)
    }
}
