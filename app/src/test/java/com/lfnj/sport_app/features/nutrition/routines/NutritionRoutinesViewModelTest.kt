package com.lfnj.sport_app.features.nutrition.routines

import com.lfnj.sport_app.features.nutrition.intake.model.FoodCatalog
import com.lfnj.sport_app.features.nutrition.intake.model.FoodCatalogItem
import com.lfnj.sport_app.features.nutrition.intake.model.FoodCategory
import com.lfnj.sport_app.features.nutrition.intake.model.FoodUnit
import com.lfnj.sport_app.features.nutrition.routines.data.NutritionRoutinesGateway
import com.lfnj.sport_app.features.nutrition.routines.data.NutritionRoutinesRefresh
import com.lfnj.sport_app.features.nutrition.routines.model.NutritionRoutineSuggestion
import com.lfnj.sport_app.features.nutrition.routines.model.NutritionRoutinesSnapshot
import com.lfnj.sport_app.features.nutrition.routines.model.RoutineFood
import com.lfnj.sport_app.features.nutrition.routines.model.RoutineMeal
import com.lfnj.sport_app.features.nutrition.routines.model.RoutineSourceStatus
import com.lfnj.sport_app.testing.MainDispatcherRule
import com.lfnj.sport_app.testing.MutableClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
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

/** HU030: estados de «Rutinas de alimentación» (carga, vacío, no personalizada, sin red, `STALE`, errores y detalle). */
@OptIn(ExperimentalCoroutinesApi::class)
class NutritionRoutinesViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val now = Instant.parse("2026-10-27T15:00:00Z")
    private val clock = MutableClock(now, ZoneId.of("America/Bogota"))

    /** Caché observable y respuestas del BFF programadas por la prueba. */
    private class FakeGateway(initial: NutritionRoutinesSnapshot? = null) : NutritionRoutinesGateway {
        val cache = MutableStateFlow(initial)
        val responses = ArrayDeque<NutritionRoutinesRefresh>()
        var onUpdate: () -> NutritionRoutinesSnapshot? = { null }
        var gate: CompletableDeferred<Unit>? = null
        var calls = 0

        override fun observe(owner: String): Flow<NutritionRoutinesSnapshot?> = cache

        override suspend fun refresh(owner: String): NutritionRoutinesRefresh {
            calls++
            gate?.await()
            val result = responses.removeFirstOrNull() ?: NutritionRoutinesRefresh.NoConnection
            if (result == NutritionRoutinesRefresh.Updated) cache.value = onUpdate()
            return result
        }
    }

    private fun routine(id: String, reasons: List<String> = listOf("RESTRICTION_COMPATIBLE")) = NutritionRoutineSuggestion(
        templateId = id,
        professionalId = "2e3f4a5b-6c7d-4e8f-9a0b-1c2d3e4f5a6b",
        title = "Rutina $id",
        summary = "Descripción sintética de la rutina.",
        goal = "HEALTH",
        sport = null,
        dietaryPattern = "VEGAN",
        containsAllergens = listOf("SOY"),
        durationDays = 7,
        dailyMeals = listOf(RoutineMeal("BREAKFAST", listOf(RoutineFood("OATS", 1)))),
        publishedAt = Instant.parse("2026-10-20T14:00:00Z"),
        authorName = "Marta Ruiz",
        reasons = reasons,
    )

    private fun snapshot(
        items: List<NutritionRoutineSuggestion> = listOf(routine("a"), routine("b")),
        personalized: Boolean = true,
        missing: List<String> = emptyList(),
        notices: List<String> = listOf("NOT_MEDICAL_ADVICE"),
        syncedAt: Instant = now,
    ) = NutritionRoutinesSnapshot(items, personalized, missing, notices, now, RoutineSourceStatus.OK, syncedAt)

    private fun viewModel(gateway: FakeGateway, catalog: Flow<FoodCatalog?> = MutableStateFlow(null)) =
        NutritionRoutinesViewModel("owner", gateway, catalog, clock)

    @Test
    fun `muestra carga y luego la lista confirmada por el BFF`() = runTest {
        val gateway = FakeGateway().apply {
            gate = CompletableDeferred()
            responses += NutritionRoutinesRefresh.Updated
            onUpdate = { snapshot() }
        }
        val viewModel = viewModel(gateway)

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
    fun `una lista confirmada sin rutinas es un estado vacio`() = runTest {
        val gateway = FakeGateway().apply {
            responses += NutritionRoutinesRefresh.Updated
            onUpdate = { snapshot(items = emptyList()) }
        }
        val viewModel = viewModel(gateway)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.empty)
        assertNull(viewModel.uiState.value.problem)
    }

    @Test
    fun `la lista no personalizada se expone con su contexto faltante y sin verificar restricciones (CA3)`() = runTest {
        val gateway = FakeGateway().apply {
            responses += NutritionRoutinesRefresh.Updated
            onUpdate = {
                snapshot(
                    items = listOf(routine("a", reasons = emptyList())),
                    personalized = false,
                    missing = listOf("nutrition_habits"),
                    notices = listOf("RESTRICTIONS_NOT_VERIFIED", "NOT_MEDICAL_ADVICE"),
                )
            }
        }
        val viewModel = viewModel(gateway)
        advanceUntilIdle()

        val shown = checkNotNull(viewModel.uiState.value.snapshot)
        assertFalse(shown.personalized)
        assertTrue(shown.restrictionsNotVerified)
        assertEquals(listOf("nutrition_habits"), shown.missingContext)
    }

    @Test
    fun `sin red y sin cache informa que nunca se sincronizo`() = runTest {
        val viewModel = viewModel(FakeGateway())
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.neverSynced)
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test
    fun `sin red conserva la ultima lista con su antiguedad y la marca STALE a las 24 horas`() = runTest {
        val old = snapshot(syncedAt = now.minus(Duration.ofHours(25)))
        val viewModel = viewModel(FakeGateway(old))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(NutritionRoutinesProblem.NO_CONNECTION, state.problem)
        assertEquals(old, state.snapshot)
        assertTrue(state.stale)
        assertFalse(state.neverSynced)
    }

    @Test
    fun `una lista de menos de 24 horas no es STALE`() = runTest {
        val viewModel = viewModel(FakeGateway(snapshot(syncedAt = now.minus(Duration.ofHours(23)))))
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.stale)
    }

    @Test
    fun `un error del BFF conserva la cache y reintentar la actualiza`() = runTest {
        val gateway = FakeGateway(snapshot()).apply {
            responses += NutritionRoutinesRefresh.Unavailable("corr")
            responses += NutritionRoutinesRefresh.Updated
            onUpdate = { snapshot(items = listOf(routine("c"))) }
        }
        val viewModel = viewModel(gateway)
        advanceUntilIdle()
        assertEquals(NutritionRoutinesProblem.UNAVAILABLE, viewModel.uiState.value.problem)
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
        val viewModel = viewModel(gateway)

        viewModel.retry()
        viewModel.retry()
        gateway.gate?.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, gateway.calls)
    }

    @Test
    fun `sin la capacidad o sin permiso no muestra datos guardados`() = runTest {
        val gateway = FakeGateway(snapshot()).apply { responses += NutritionRoutinesRefresh.CapabilityRequired }
        val viewModel = viewModel(gateway)
        advanceUntilIdle()
        assertEquals(NutritionRoutinesProblem.CAPABILITY_REQUIRED, viewModel.uiState.value.problem)
        assertNull(viewModel.uiState.value.snapshot)

        gateway.responses += NutritionRoutinesRefresh.PermissionRequired
        viewModel.retry()
        advanceUntilIdle()
        assertEquals(NutritionRoutinesProblem.PERMISSION_REQUIRED, viewModel.uiState.value.problem)
        assertNull(viewModel.uiState.value.snapshot)
    }

    @Test
    fun `cada falla se traduce a su problema de pantalla`() = runTest {
        val cases = mapOf(
            NutritionRoutinesRefresh.InvalidRequest to NutritionRoutinesProblem.INVALID_REQUEST,
            NutritionRoutinesRefresh.InvalidResponse to NutritionRoutinesProblem.INVALID_RESPONSE,
            NutritionRoutinesRefresh.StorageFailed to NutritionRoutinesProblem.STORAGE,
            NutritionRoutinesRefresh.Unavailable(null) to NutritionRoutinesProblem.UNAVAILABLE,
        )
        cases.forEach { (refresh, problem) ->
            val viewModel = viewModel(FakeGateway().apply { responses += refresh })
            advanceUntilIdle()
            assertEquals(refresh.toString(), problem, viewModel.uiState.value.problem)
        }
    }

    @Test
    fun `una sesion vencida pide volver al acceso una sola vez`() = runTest {
        val viewModel = viewModel(FakeGateway().apply { responses += NutritionRoutinesRefresh.SignedOut })
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.sessionExpired)

        viewModel.onSessionExpiredHandled()
        assertFalse(viewModel.uiState.value.sessionExpired)
    }

    @Test
    fun `abre y cierra el detalle de una rutina (CA4)`() = runTest {
        val viewModel = viewModel(FakeGateway(snapshot()))
        advanceUntilIdle()

        viewModel.openDetail("b")
        assertEquals("b", viewModel.uiState.value.selected?.templateId)

        viewModel.closeDetail()
        assertNull(viewModel.uiState.value.selected)
    }

    @Test
    fun `una rutina que deja de sugerirse cierra su detalle (CA2)`() = runTest {
        val gateway = FakeGateway(snapshot())
        val viewModel = viewModel(gateway)
        advanceUntilIdle()
        viewModel.openDetail("a")

        gateway.cache.value = snapshot(items = listOf(routine("b")))
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.selected)
        gateway.cache.value = snapshot()
        advanceUntilIdle()
        assertNull("no reabre el detalle por sí solo", viewModel.uiState.value.selected)
    }

    @Test
    fun `una cache ilegible o ausente no muestra rutinas`() = runTest {
        val viewModel = viewModel(FakeGateway(null))
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.snapshot)
        viewModel.openDetail("a")
        assertNull(viewModel.uiState.value.selected)
    }

    // ----------------------------------------------------------------- nombres de alimentos (catálogo local de HU043)

    @Test
    fun `los alimentos se nombran con el catalogo ya guardado de HU043`() = runTest {
        val catalog = MutableStateFlow<FoodCatalog?>(null)
        val viewModel = viewModel(FakeGateway(snapshot()), catalog)
        advanceUntilIdle()
        assertTrue("sin catálogo guardado se mostrará el food_code", viewModel.uiState.value.foodLabelKeys.isEmpty())

        catalog.value = FoodCatalog(
            catalogVersion = "food-catalog-2026-10-13.v1",
            items = listOf(FoodCatalogItem("OATS", "food.oats", FoodCategory.GRAIN, FoodUnit.G, listOf(FoodUnit.G))),
            etag = null,
            syncedAt = now,
        )
        advanceUntilIdle()

        assertEquals(mapOf("OATS" to "food.oats"), viewModel.uiState.value.foodLabelKeys)
    }

    @Test
    fun `un catalogo ilegible no impide mostrar las rutinas`() = runTest {
        val broken = flow<FoodCatalog?> { throw IllegalStateException("caché ilegible") }
        val viewModel = viewModel(FakeGateway(snapshot()), broken)
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.snapshot?.items?.size)
        assertTrue(viewModel.uiState.value.foodLabelKeys.isEmpty())
    }
}
