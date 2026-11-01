package com.lfnj.sport_app.features.nutrition.intake

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.features.nutrition.intake.data.CatalogRefresh
import com.lfnj.sport_app.features.nutrition.intake.data.FoodCatalogDto
import com.lfnj.sport_app.features.nutrition.intake.data.FoodIntakeDraftItem
import com.lfnj.sport_app.features.nutrition.intake.data.FoodIntakeGateway
import com.lfnj.sport_app.features.nutrition.intake.data.FoodLocalState
import com.lfnj.sport_app.features.nutrition.intake.data.FoodSubmitResult
import com.lfnj.sport_app.features.nutrition.intake.model.ConsumedAtError
import com.lfnj.sport_app.features.nutrition.intake.model.FoodCatalog
import com.lfnj.sport_app.features.nutrition.intake.model.FoodIntakeDetails
import com.lfnj.sport_app.features.nutrition.intake.model.FoodIntakeLine
import com.lfnj.sport_app.features.nutrition.intake.model.FoodIntakeValidation
import com.lfnj.sport_app.features.nutrition.intake.model.FoodSyncState
import com.lfnj.sport_app.features.nutrition.intake.model.FoodUnit
import com.lfnj.sport_app.features.nutrition.intake.model.LocalFoodIntake
import com.lfnj.sport_app.features.nutrition.intake.model.MealType
import com.lfnj.sport_app.features.nutrition.intake.model.QuantityError
import com.lfnj.sport_app.testing.FoodClock
import com.lfnj.sport_app.testing.FoodFixtures
import com.lfnj.sport_app.testing.MainDispatcherRule
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
import java.time.ZoneOffset

/** HU043: estados de «Mi alimentación» (CA5, CA6) sobre una pasarela simulada. */
@OptIn(ExperimentalCoroutinesApi::class)
class FoodIntakeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = FoodClock()
    private val names = mapOf("food.oats" to "Avena", "food.egg" to "Huevo", "food.milk" to "Leche", "food.water" to "Agua")

    private fun catalog(syncedAt: Instant = clock.instant()): FoodCatalog {
        val dto = BffMobileClient.json.decodeFromString(FoodCatalogDto.serializer(), FoodFixtures.CATALOG)
        return FoodCatalog(dto.catalogVersion, dto.items.mapNotNull { it.toModel() }, FoodFixtures.ETAG, syncedAt)
    }

    private class FakeGateway(initial: FoodLocalState) : FoodIntakeGateway {
        val local = MutableStateFlow(initial)
        var refresh: CatalogRefresh = CatalogRefresh.Updated
        var refreshes = 0
        val submitted = mutableListOf<Triple<MealType, Instant, List<FoodIntakeDraftItem>>>()
        var submitGate: CompletableDeferred<FoodSubmitResult>? = null
        val dismissed = mutableListOf<String>()

        override fun observe(owner: String): Flow<FoodLocalState> = local
        override suspend fun refreshCatalog(owner: String): CatalogRefresh {
            refreshes++
            return refresh
        }
        override suspend fun submit(owner: String, mealType: MealType, consumedAt: Instant, items: List<FoodIntakeDraftItem>): FoodSubmitResult {
            submitted += Triple(mealType, consumedAt, items)
            return submitGate?.await() ?: FoodSubmitResult.Queued("id-${submitted.size}")
        }
        override suspend fun dismiss(owner: String, intakeId: String): Boolean {
            dismissed += intakeId
            return true
        }
    }

    private fun gateway(catalog: FoodCatalog? = catalog(), intakes: List<LocalFoodIntake> = emptyList()) = FakeGateway(FoodLocalState(catalog, intakes))

    private fun vm(gateway: FakeGateway, online: Flow<Boolean> = MutableStateFlow(true)) =
        FoodIntakeViewModel("owner", gateway, online, { names[it.labelKey] ?: it.foodCode }, clock, ZoneOffset.UTC)

    private fun intake(id: String, state: FoodSyncState, consumedAt: Instant) = LocalFoodIntake(
        id, state, null, null, null, consumedAt,
        FoodIntakeDetails(MealType.LUNCH, consumedAt, listOf(FoodIntakeLine("OATS", "food.oats", "50.0", FoodUnit.G))),
        registeredAt = null,
    )

    @Test
    fun `al abrir consulta el catalogo y ofrece solo alimentos de la cache`() = runTest(mainDispatcherRule.dispatcher) {
        val g = gateway()
        val vm = vm(g)
        advanceUntilIdle()

        assertEquals(1, g.refreshes)
        assertFalse(vm.uiState.value.loading)
        vm.onQueryChange("agu")
        assertEquals(listOf("WATER"), vm.uiState.value.results.map { it.foodCode })
        vm.onQueryChange("pizza")
        assertTrue(vm.uiState.value.results.isEmpty())
        vm.addFood("PIZZA")
        assertTrue("No se inventan alimentos", vm.uiState.value.lines.isEmpty())
    }

    @Test
    fun `CA5 sin catalogo en cache y sin red se pide conexion y no hay formulario`() = runTest(mainDispatcherRule.dispatcher) {
        val g = gateway(catalog = null).apply { refresh = CatalogRefresh.NoConnection }
        val vm = vm(g, MutableStateFlow(false))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertNull(state.catalog)
        assertFalse(state.online)
        assertEquals(FoodCatalogNotice.NO_CONNECTION, state.notice)
        vm.addFood("OATS")
        vm.submit()
        assertTrue(g.submitted.isEmpty())
    }

    @Test
    fun `CA6 un catalogo con mas de 24 horas se muestra STALE y sigue limitado a la cache`() = runTest(mainDispatcherRule.dispatcher) {
        val g = gateway(catalog(syncedAt = clock.instant().minus(Duration.ofHours(25)))).apply { refresh = CatalogRefresh.NoConnection }
        val vm = vm(g)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.catalogStale)
        vm.addFood("OATS")
        assertEquals(1, vm.uiState.value.lines.size)
    }

    @Test
    fun `CA5 un consumo valido se encola con cantidades normalizadas y el formulario se limpia`() = runTest(mainDispatcherRule.dispatcher) {
        val g = gateway()
        val vm = vm(g, MutableStateFlow(false))
        advanceUntilIdle()

        vm.selectMeal(MealType.BREAKFAST)
        vm.addFood("OATS")
        vm.onQuantityChange(0, "50")
        vm.selectUnit(0, FoodUnit.CUP)
        vm.addFood("MILK")
        vm.onQuantityChange(1, "250,5")
        vm.submit()
        advanceUntilIdle()

        val (meal, at, items) = g.submitted.single()
        assertEquals(MealType.BREAKFAST, meal)
        assertEquals(FoodFixtures.NOW, at)
        assertEquals(listOf(FoodIntakeDraftItem("OATS", "50.0", FoodUnit.CUP), FoodIntakeDraftItem("MILK", "250.5", FoodUnit.ML)), items)
        val state = vm.uiState.value
        assertEquals(FoodSubmitNotice.QUEUED, state.submitNotice)
        assertTrue(state.lines.isEmpty())
        assertNull(state.mealType)
    }

    @Test
    fun `solo se aceptan unidades permitidas por el alimento`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = vm(gateway())
        advanceUntilIdle()

        vm.addFood("MILK")
        assertEquals(FoodUnit.ML, vm.uiState.value.lines.single().unit)
        vm.selectUnit(0, FoodUnit.G)
        assertEquals(FoodUnit.ML, vm.uiState.value.lines.single().unit)
        assertEquals(listOf(FoodUnit.ML, FoodUnit.CUP), vm.uiState.value.lines.single().allowedUnits)
    }

    @Test
    fun `los errores de validacion se muestran sin encolar`() = runTest(mainDispatcherRule.dispatcher) {
        val g = gateway()
        val vm = vm(g)
        advanceUntilIdle()

        vm.submit()
        assertTrue(vm.uiState.value.mealMissing)
        assertTrue(vm.uiState.value.itemsMissing)

        vm.selectMeal(MealType.SNACK)
        vm.addFood("OATS")
        vm.onQuantityChange(0, "6000")
        vm.setConsumedAt(clock.instant().plus(Duration.ofHours(1)))
        vm.submit()

        assertEquals(QuantityError.OUT_OF_RANGE, vm.uiState.value.lines.single().error)
        assertEquals(ConsumedAtError.IN_FUTURE, vm.uiState.value.consumedAtError)
        assertTrue(g.submitted.isEmpty())
    }

    @Test
    fun `no se agregan mas de 20 alimentos`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = vm(gateway())
        advanceUntilIdle()

        repeat(FoodIntakeValidation.MAX_ITEMS + 3) { vm.addFood("WATER") }

        assertEquals(FoodIntakeValidation.MAX_ITEMS, vm.uiState.value.lines.size)
        assertFalse(vm.uiState.value.canAddMore)
    }

    @Test
    fun `no se encola dos veces mientras se guarda`() = runTest(mainDispatcherRule.dispatcher) {
        val g = gateway().apply { submitGate = CompletableDeferred() }
        val vm = vm(g)
        advanceUntilIdle()
        vm.selectMeal(MealType.HYDRATION)
        vm.addFood("WATER")
        vm.onQuantityChange(0, "500")

        vm.submit()
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.busy)
        g.submitGate?.complete(FoodSubmitResult.StorageFailed)
        advanceUntilIdle()

        assertEquals(1, g.submitted.size)
        assertEquals(FoodSubmitNotice.STORAGE, vm.uiState.value.submitNotice)
        assertEquals("Una falla conserva el formulario", 1, vm.uiState.value.lines.size)
    }

    @Test
    fun `un alimento que sale del catalogo confirmado sale del formulario`() = runTest(mainDispatcherRule.dispatcher) {
        val g = gateway()
        val vm = vm(g)
        advanceUntilIdle()
        vm.addFood("EGG")

        val current = catalog()
        g.local.value = FoodLocalState(current.copy(items = current.items.filter { it.foodCode != "EGG" }), emptyList())
        advanceUntilIdle()

        assertTrue(vm.uiState.value.lines.isEmpty())
    }

    @Test
    fun `la lista muestra los consumos de hoy y cualquier pendiente o fallido`() = runTest(mainDispatcherRule.dispatcher) {
        val yesterday = clock.instant().minus(Duration.ofDays(1))
        val g = gateway(
            intakes = listOf(
                intake("hoy", FoodSyncState.SYNCED, clock.instant()),
                intake("ayer-confirmado", FoodSyncState.SYNCED, yesterday),
                intake("ayer-pendiente", FoodSyncState.PENDING_SYNC, yesterday),
                intake("ayer-fallido", FoodSyncState.SYNC_FAILED, yesterday),
            ),
        )
        val vm = vm(g)
        advanceUntilIdle()

        assertEquals(listOf("hoy", "ayer-pendiente", "ayer-fallido"), vm.uiState.value.intakes.map { it.intakeId })
        vm.dismiss("ayer-fallido")
        advanceUntilIdle()
        assertEquals(listOf("ayer-fallido"), g.dismissed)
    }

    @Test
    fun `una sesion vencida vuelve al acceso`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = vm(gateway().apply { refresh = CatalogRefresh.SignedOut })
        advanceUntilIdle()

        assertTrue(vm.uiState.value.sessionExpired)
        vm.onSessionExpiredHandled()
        assertFalse(vm.uiState.value.sessionExpired)
    }
}
