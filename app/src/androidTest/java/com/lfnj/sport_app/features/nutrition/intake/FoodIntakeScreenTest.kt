package com.lfnj.sport_app.features.nutrition.intake

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.features.nutrition.intake.data.FoodCatalogDto
import com.lfnj.sport_app.features.nutrition.intake.model.FoodCatalog
import com.lfnj.sport_app.features.nutrition.intake.model.FoodIntakeDetails
import com.lfnj.sport_app.features.nutrition.intake.model.FoodIntakeLine
import com.lfnj.sport_app.features.nutrition.intake.model.FoodSyncFailure
import com.lfnj.sport_app.features.nutrition.intake.model.FoodSyncState
import com.lfnj.sport_app.features.nutrition.intake.model.FoodUnit
import com.lfnj.sport_app.features.nutrition.intake.model.LocalFoodIntake
import com.lfnj.sport_app.features.nutrition.intake.model.MealType
import com.lfnj.sport_app.features.nutrition.intake.model.QuantityError
import com.lfnj.sport_app.ui.theme.SportappTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneOffset

/** HU043 en la pantalla: catálogo, formulario, sin red con y sin caché, `STALE`, pendiente, fallido y accesibilidad. */
@RunWith(AndroidJUnit4::class)
class FoodIntakeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val now = Instant.parse("2026-10-24T12:35:00Z")
    private val events = mutableListOf<String>()

    private fun catalog(syncedAt: Instant = now): FoodCatalog {
        val dto = BffMobileClient.json.decodeFromString(FoodCatalogDto.serializer(), FoodIntakeLocalStorageTest.CATALOG)
        return FoodCatalog(dto.catalogVersion, dto.items.mapNotNull { it.toModel() }, null, syncedAt)
    }

    private fun show(state: FoodIntakeUiState) = composeRule.setContent {
        SportappTheme {
            FoodIntakeScreen(
                state = state,
                onSelectMeal = { events += "meal:${it.wire}" },
                onConsumedAtChange = { events += "consumed" },
                onQueryChange = { events += "query:$it" },
                onAddFood = { events += "add:$it" },
                onRemoveLine = { events += "remove:$it" },
                onQuantityChange = { index, text -> events += "quantity:$index=$text" },
                onSelectUnit = { index, unit -> events += "unit:$index=${unit.wire}" },
                onSubmit = { events += "submit" },
                onRetry = { events += "retry" },
                onDismiss = { events += "dismiss:$it" },
                onBack = {},
                zone = ZoneOffset.UTC,
            )
        }
    }

    private fun intake(id: String, state: FoodSyncState, failure: FoodSyncFailure? = null) = LocalFoodIntake(
        id, state, failure, null, null, now,
        FoodIntakeDetails(MealType.BREAKFAST, Instant.parse("2026-10-24T12:30:00Z"), listOf(FoodIntakeLine("OATS", "food.oats", "50.0", FoodUnit.G))),
        registeredAt = if (state == FoodSyncState.SYNCED) Instant.parse("2026-10-24T12:35:02Z") else null,
    )

    @Test
    fun cargaMuestraProgreso() {
        show(FoodIntakeUiState(loading = true))

        composeRule.onNodeWithTag("food_loading").assertIsDisplayed()
    }

    @Test
    fun ca5SinRedYSinCatalogoSolicitaConexionYNoOfreceAlimentos() {
        show(FoodIntakeUiState(loading = false, online = false, notice = FoodCatalogNotice.NO_CONNECTION))

        composeRule.onNodeWithTag("food_offline").assertIsDisplayed()
        composeRule.onNodeWithTag("food_catalog_required").assertIsDisplayed()
        composeRule.onNodeWithTag("food_search").assertDoesNotExist()
        composeRule.onNodeWithTag("food_submit").assertDoesNotExist()
        composeRule.onNodeWithTag("food_catalog_notice").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        composeRule.onNodeWithTag("food_retry").performScrollTo().performClick()
        assertEquals(listOf("retry"), events)
    }

    @Test
    fun ca5SinRedConCatalogoEnCachePermiteRegistrar() {
        show(FoodIntakeUiState(loading = false, online = false, catalog = catalog(), consumedAt = now))

        composeRule.onNodeWithTag("food_offline").assertIsDisplayed()
        composeRule.onNodeWithTag("food_meal_HYDRATION").performScrollTo().performClick()
        composeRule.onNodeWithTag("food_submit").performScrollTo().performClick()
        assertEquals(listOf("meal:HYDRATION", "submit"), events)
    }

    @Test
    fun ca6UnCatalogoDeMasDe24HorasSeMuestraDesactualizado() {
        show(FoodIntakeUiState(loading = false, catalog = catalog(now.minusSeconds(90_000)), catalogStale = true, consumedAt = now))

        composeRule.onNodeWithTag("food_catalog_stale").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Catálogo desactualizado", substring = true).assertIsDisplayed()
    }

    @Test
    fun elBuscadorSoloMuestraAlimentosDelCatalogoConNombreTraducido() {
        val c = catalog()
        show(FoodIntakeUiState(loading = false, catalog = c, consumedAt = now, query = "a", results = c.items.filter { it.foodCode == "WATER" }))

        composeRule.onNodeWithText("Agua").performScrollTo().assertIsDisplayed()
        composeRule.onNode(hasContentDescription("Agregar Agua")).performScrollTo().performClick()
        assertEquals(listOf("add:WATER"), events)
    }

    @Test
    fun unaBusquedaSinCoincidenciasNoAdmiteTextoLibre() {
        show(FoodIntakeUiState(loading = false, catalog = catalog(), consumedAt = now, query = "pizza", results = emptyList()))

        composeRule.onNodeWithTag("food_search_empty").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun cadaAlimentoMuestraSoloSusUnidadesPermitidasYSusErrores() {
        val line = FoodDraftLine("MILK", "food.milk", listOf(FoodUnit.ML, FoodUnit.CUP), FoodUnit.ML, quantity = "6000", error = QuantityError.OUT_OF_RANGE)
        show(FoodIntakeUiState(loading = false, catalog = catalog(), consumedAt = now, lines = listOf(line)))

        composeRule.onNodeWithText("Leche").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("food_unit_0_ML").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("food_unit_0_CUP").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("food_unit_0_G").assertDoesNotExist()
        composeRule.onNodeWithTag("food_quantity_error_0", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("food_unit_0_CUP").performScrollTo().performClick()
        composeRule.onNodeWithTag("food_remove_0").performScrollTo().performClick()
        assertEquals(listOf("unit:0=CUP", "remove:0"), events)
    }

    @Test
    fun losCamposObligatoriosSeAnuncian() {
        show(FoodIntakeUiState(loading = false, catalog = catalog(), consumedAt = now, mealMissing = true, itemsMissing = true))

        composeRule.onNodeWithTag("food_meal_missing").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        composeRule.onNodeWithTag("food_items_missing").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun pendienteConfirmadoYFallidoSeDistinguenYSoloElFallidoSePuedeRetirar() {
        show(
            FoodIntakeUiState(
                loading = false, catalog = catalog(), consumedAt = now,
                intakes = listOf(
                    intake("p", FoodSyncState.PENDING_SYNC),
                    intake("s", FoodSyncState.SYNCED),
                    intake("f", FoodSyncState.SYNC_FAILED, FoodSyncFailure.CATALOG_CHANGED),
                ),
            ),
        )

        composeRule.onNodeWithTag("food_state_p").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Pendiente de sincronizar").assertIsDisplayed()
        composeRule.onNodeWithTag("food_state_s").performScrollTo()
        composeRule.onNodeWithText("Confirmado").assertIsDisplayed()
        composeRule.onNodeWithTag("food_state_f").performScrollTo().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        composeRule.onNodeWithText("El catálogo cambió", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("food_dismiss_p").assertDoesNotExist()
        composeRule.onNodeWithTag("food_dismiss_s").assertDoesNotExist()
        composeRule.onNodeWithTag("food_dismiss_f").performScrollTo().performClick()
        assertEquals(listOf("dismiss:f"), events)
    }

    @Test
    fun elConsumoSeMuestraSinCalorias() {
        show(FoodIntakeUiState(loading = false, catalog = catalog(), consumedAt = now, intakes = listOf(intake("s", FoodSyncState.SYNCED))))

        composeRule.onNodeWithText("Avena · 50.0 g").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("kcal", substring = true, ignoreCase = true).assertDoesNotExist()
    }
}
