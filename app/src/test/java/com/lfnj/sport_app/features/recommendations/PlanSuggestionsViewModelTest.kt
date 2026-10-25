package com.lfnj.sport_app.features.recommendations

import com.lfnj.sport_app.features.recommendations.data.PlanSuggestionsGateway
import com.lfnj.sport_app.features.recommendations.data.PlanSuggestionsRefresh
import com.lfnj.sport_app.features.recommendations.model.ContextSourceStatus
import com.lfnj.sport_app.features.recommendations.model.PlanSuggestionsSnapshot
import com.lfnj.sport_app.features.recommendations.model.PlanTemplateSuggestion
import com.lfnj.sport_app.features.recommendations.model.WeeklySession
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
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** HU028: estados de «Entrenamientos sugeridos» (carga, vacío, no personalizada, sin red, `STALE`, errores y detalle). */
@OptIn(ExperimentalCoroutinesApi::class)
class PlanSuggestionsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val now = Instant.parse("2026-10-25T15:00:00Z")
    private val clock = MutableClock(now, ZoneId.of("America/Bogota"))

    /** Caché observable y respuestas del BFF programadas por la prueba. */
    private class FakeGateway(initial: PlanSuggestionsSnapshot? = null) : PlanSuggestionsGateway {
        val cache = MutableStateFlow(initial)
        val responses = ArrayDeque<PlanSuggestionsRefresh>()
        var onUpdate: () -> PlanSuggestionsSnapshot? = { null }
        var gate: CompletableDeferred<Unit>? = null
        var calls = 0

        override fun observe(owner: String): Flow<PlanSuggestionsSnapshot?> = cache

        override suspend fun refresh(owner: String): PlanSuggestionsRefresh {
            calls++
            gate?.await()
            val result = responses.removeFirstOrNull() ?: PlanSuggestionsRefresh.NoConnection
            if (result == PlanSuggestionsRefresh.Updated) cache.value = onUpdate()
            return result
        }
    }

    private fun template(id: String, reasons: List<String> = listOf("SPORT_MATCH")) = PlanTemplateSuggestion(
        templateId = id,
        professionalId = "5c4b3a29-1807-4f6e-9d5c-4b3a29180706",
        title = "Plantilla $id",
        summary = "Objetivos sintéticos de la plantilla.",
        sport = "running",
        goal = "HEALTH",
        level = "BEGINNER",
        durationWeeks = 6,
        weeklySessions = listOf(WeeklySession(DayOfWeek.TUESDAY, "Rodaje", 40)),
        publishedAt = Instant.parse("2026-10-20T14:00:00Z"),
        authorName = "Laura Gómez",
        reasons = reasons,
    )

    private fun snapshot(
        items: List<PlanTemplateSuggestion> = listOf(template("a"), template("b")),
        personalized: Boolean = true,
        missing: List<String> = emptyList(),
        syncedAt: Instant = now,
    ) = PlanSuggestionsSnapshot(items, personalized, missing, now, ContextSourceStatus.OK, syncedAt)

    @Test
    fun `muestra carga y luego la lista confirmada por el BFF`() = runTest {
        val gateway = FakeGateway().apply {
            gate = CompletableDeferred()
            responses += PlanSuggestionsRefresh.Updated
            onUpdate = { snapshot() }
        }
        val viewModel = PlanSuggestionsViewModel("owner", gateway, clock)

        assertTrue(viewModel.uiState.value.loading)
        assertTrue(viewModel.uiState.value.refreshing)
        gateway.gate?.complete(Unit)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertFalse(state.refreshing)
        assertNull(state.problem)
        assertEquals(listOf("a", "b"), state.snapshot?.items?.map { it.templateId })
        assertFalse(state.stale)
    }

    @Test
    fun `una lista confirmada sin plantillas es un estado vacio`() = runTest {
        val gateway = FakeGateway().apply {
            responses += PlanSuggestionsRefresh.Updated
            onUpdate = { snapshot(items = emptyList()) }
        }
        val viewModel = PlanSuggestionsViewModel("owner", gateway, clock)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.empty)
        assertNull(viewModel.uiState.value.problem)
    }

    @Test
    fun `la lista no personalizada se expone con su contexto faltante (CA2)`() = runTest {
        val gateway = FakeGateway().apply {
            responses += PlanSuggestionsRefresh.Updated
            onUpdate = { snapshot(items = listOf(template("a", reasons = emptyList())), personalized = false, missing = listOf("sports")) }
        }
        val viewModel = PlanSuggestionsViewModel("owner", gateway, clock)
        advanceUntilIdle()

        val shown = checkNotNull(viewModel.uiState.value.snapshot)
        assertFalse(shown.personalized)
        assertEquals(listOf("sports"), shown.missingContext)
    }

    @Test
    fun `sin red y sin cache informa que nunca se sincronizo`() = runTest {
        val viewModel = PlanSuggestionsViewModel("owner", FakeGateway(), clock)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.neverSynced)
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test
    fun `sin red conserva la ultima lista con su antiguedad y la marca STALE a las 24 horas`() = runTest {
        val old = snapshot(syncedAt = now.minus(Duration.ofHours(25)))
        val viewModel = PlanSuggestionsViewModel("owner", FakeGateway(old), clock)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(PlanSuggestionsProblem.NO_CONNECTION, state.problem)
        assertEquals(old, state.snapshot)
        assertTrue(state.stale)
        assertFalse(state.neverSynced)
    }

    @Test
    fun `una lista de menos de 24 horas no es STALE`() = runTest {
        val viewModel = PlanSuggestionsViewModel("owner", FakeGateway(snapshot(syncedAt = now.minus(Duration.ofHours(23)))), clock)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.stale)
    }

    @Test
    fun `un error del BFF conserva la cache y reintentar la actualiza`() = runTest {
        val gateway = FakeGateway(snapshot()).apply {
            responses += PlanSuggestionsRefresh.Unavailable("corr")
            responses += PlanSuggestionsRefresh.Updated
            onUpdate = { snapshot(items = listOf(template("c"))) }
        }
        val viewModel = PlanSuggestionsViewModel("owner", gateway, clock)
        advanceUntilIdle()
        assertEquals(PlanSuggestionsProblem.UNAVAILABLE, viewModel.uiState.value.problem)
        assertEquals(2, viewModel.uiState.value.snapshot?.items?.size)

        viewModel.retry()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.problem)
        assertEquals(listOf("c"), viewModel.uiState.value.snapshot?.items?.map { it.templateId })
        assertEquals(2, gateway.calls)
    }

    @Test
    fun `no duplica la consulta mientras otra esta en curso`() = runTest {
        val gateway = FakeGateway().apply { gate = CompletableDeferred() }
        val viewModel = PlanSuggestionsViewModel("owner", gateway, clock)

        viewModel.retry()
        viewModel.retry()
        gateway.gate?.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, gateway.calls)
    }

    @Test
    fun `sin la capacidad o sin permiso no muestra datos guardados`() = runTest {
        val gateway = FakeGateway(snapshot()).apply { responses += PlanSuggestionsRefresh.CapabilityRequired }
        val viewModel = PlanSuggestionsViewModel("owner", gateway, clock)
        advanceUntilIdle()
        assertEquals(PlanSuggestionsProblem.CAPABILITY_REQUIRED, viewModel.uiState.value.problem)
        assertNull(viewModel.uiState.value.snapshot)

        gateway.responses += PlanSuggestionsRefresh.PermissionRequired
        viewModel.retry()
        advanceUntilIdle()
        assertEquals(PlanSuggestionsProblem.PERMISSION_REQUIRED, viewModel.uiState.value.problem)
        assertNull(viewModel.uiState.value.snapshot)
    }

    @Test
    fun `cada falla se traduce a su problema de pantalla`() = runTest {
        val cases = mapOf(
            PlanSuggestionsRefresh.InvalidRequest to PlanSuggestionsProblem.INVALID_REQUEST,
            PlanSuggestionsRefresh.InvalidResponse to PlanSuggestionsProblem.INVALID_RESPONSE,
            PlanSuggestionsRefresh.StorageFailed to PlanSuggestionsProblem.STORAGE,
            PlanSuggestionsRefresh.Unavailable(null) to PlanSuggestionsProblem.UNAVAILABLE,
        )
        cases.forEach { (refresh, problem) ->
            val viewModel = PlanSuggestionsViewModel("owner", FakeGateway().apply { responses += refresh }, clock)
            advanceUntilIdle()
            assertEquals(refresh.toString(), problem, viewModel.uiState.value.problem)
        }
    }

    @Test
    fun `una sesion vencida pide volver al acceso una sola vez`() = runTest {
        val viewModel = PlanSuggestionsViewModel("owner", FakeGateway().apply { responses += PlanSuggestionsRefresh.SignedOut }, clock)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.sessionExpired)

        viewModel.onSessionExpiredHandled()
        assertFalse(viewModel.uiState.value.sessionExpired)
    }

    @Test
    fun `abre y cierra el detalle de una plantilla (CA4)`() = runTest {
        val viewModel = PlanSuggestionsViewModel("owner", FakeGateway(snapshot()), clock)
        advanceUntilIdle()

        viewModel.openDetail("b")
        assertEquals("b", viewModel.uiState.value.selected?.templateId)

        viewModel.closeDetail()
        assertNull(viewModel.uiState.value.selected)
    }

    @Test
    fun `una plantilla que deja de sugerirse cierra su detalle (CA3)`() = runTest {
        val gateway = FakeGateway(snapshot())
        val viewModel = PlanSuggestionsViewModel("owner", gateway, clock)
        advanceUntilIdle()
        viewModel.openDetail("a")

        gateway.cache.value = snapshot(items = listOf(template("b")))
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.selected)
        gateway.cache.value = snapshot()
        advanceUntilIdle()
        assertNull("no reabre el detalle por sí solo", viewModel.uiState.value.selected)
    }

    @Test
    fun `una cache ilegible o ausente no muestra plantillas`() = runTest {
        val viewModel = PlanSuggestionsViewModel("owner", FakeGateway(null), clock)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.snapshot)
        viewModel.openDetail("a")
        assertNull(viewModel.uiState.value.selected)
    }
}
