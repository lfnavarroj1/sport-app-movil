package com.lfnj.sport_app.features.nutrition.routines

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lfnj.sport_app.features.nutrition.routines.model.NutritionRoutineSuggestion
import com.lfnj.sport_app.features.nutrition.routines.model.NutritionRoutinesSnapshot
import com.lfnj.sport_app.features.nutrition.routines.model.RoutineFood
import com.lfnj.sport_app.features.nutrition.routines.model.RoutineMeal
import com.lfnj.sport_app.features.nutrition.routines.model.RoutineSourceStatus
import com.lfnj.sport_app.ui.theme.SportappTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

/** HU030: estados de «Rutinas de alimentación», avisos de restricciones y médico, detalle con el día tipo y accesibilidad. */
@RunWith(AndroidJUnit4::class)
class NutritionRoutinesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val bogota = ZoneId.of("America/Bogota")
    private val events = mutableListOf<String>()

    private val goalRoutine = NutritionRoutineSuggestion(
        templateId = "a", professionalId = "p", title = "Desayunos para fondo", summary = "Comidas del día tipo para semanas de carga en carrera.",
        goal = "PERFORMANCE", sport = "running", dietaryPattern = "VEGETARIAN", containsAllergens = listOf("MILK", "TREE_NUTS"), durationDays = 14,
        dailyMeals = listOf(
            RoutineMeal("BREAKFAST", listOf(RoutineFood("OATS", 1), RoutineFood("BANANA", 2))),
            RoutineMeal("HYDRATION", listOf(RoutineFood("WATER", 4))),
        ),
        publishedAt = Instant.parse("2026-10-20T14:00:00Z"), authorName = "Marta Ruiz",
        reasons = listOf("RESTRICTION_COMPATIBLE", "GOAL_MATCH", "SPORT_MATCH"),
    )
    private val sportRoutine = goalRoutine.copy(
        templateId = "b", title = "Recuperación ciclista", goal = "HEALTH", sport = null, dietaryPattern = "VEGAN", containsAllergens = emptyList(),
        durationDays = 1, authorName = null, reasons = listOf("RESTRICTION_COMPATIBLE"),
        dailyMeals = listOf(RoutineMeal("LUNCH", listOf(RoutineFood("QUINOA", 1)))),
    )

    private fun snapshot(
        items: List<NutritionRoutineSuggestion> = listOf(goalRoutine, sportRoutine),
        personalized: Boolean = true,
        missing: List<String> = emptyList(),
        notices: List<String> = listOf("NOT_MEDICAL_ADVICE"),
        users: RoutineSourceStatus = RoutineSourceStatus.OK,
    ) = NutritionRoutinesSnapshot(items, personalized, missing, notices, Instant.EPOCH, users, Instant.parse("2026-10-26T13:30:00Z"))

    private fun show(state: NutritionRoutinesUiState) = composeRule.setContent {
        SportappTheme {
            NutritionRoutinesScreen(
                state = state,
                onOpenDetail = { events += "open:$it" },
                onCloseDetail = { events += "close" },
                onRetry = { events += "retry" },
                onBack = { events += "back" },
            )
        }
    }

    @Test
    fun muestraCargaSinInventarDatos() {
        show(NutritionRoutinesUiState(zone = bogota, refreshing = true))

        composeRule.onNodeWithTag("nutrition_routines_loading").assertIsDisplayed()
        composeRule.onNodeWithTag("nutrition_routines_empty").assertDoesNotExist()
        composeRule.onNodeWithTag("nutrition_routines_not_medical_advice").assertDoesNotExist()
    }

    @Test
    fun cadaRutinaMuestraAutorObjetivoPatronVigenciaComidasYMotivo() {
        show(NutritionRoutinesUiState(zone = bogota, snapshot = snapshot()))

        composeRule.onNodeWithTag("nutrition_routines_personalized").assertIsDisplayed()
        composeRule.onNodeWithTag("nutrition_routines_restrictions_not_verified").assertDoesNotExist()
        composeRule.onNodeWithTag("nutrition_routine_a").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Por Marta Ruiz", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Rendimiento · Vegetariano · 14 días", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("2 comidas al día", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        // Las dos rutinas son compatibles: el motivo aparece en ambas tarjetas.
        composeRule.onAllNodesWithTag("nutrition_routine_reason_RESTRICTION_COMPATIBLE", useUnmergedTree = true).assertCountEquals(2)
        composeRule.onNodeWithText("Coincide con tu objetivo", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        // Sin autor resuelto se dice así, sin inventar un nombre; los plurales respetan la cantidad.
        composeRule.onNodeWithTag("nutrition_routine_b").performScrollTo()
        composeRule.onNodeWithText("Autor no disponible", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Salud · Vegano · 1 día", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("1 comida al día", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("nutrition_routines_synced_at").performScrollTo().assertIsDisplayed()
        // El texto fijo de que no reemplaza una valoración médica está siempre presente.
        composeRule.onNodeWithTag("nutrition_routines_not_medical_advice").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun tocarUnaRutinaAbreSuDetalle() {
        show(NutritionRoutinesUiState(zone = bogota, snapshot = snapshot()))

        composeRule.onNode(hasTestTag("nutrition_routine_a") and hasClickAction()).performScrollTo().performClick()

        assertEquals(listOf("open:a"), events)
    }

    @Test
    fun sinHabitosAlimenticiosAvisaQueNoSeVerificaronRestriccionesYQueFalta() {
        show(
            NutritionRoutinesUiState(
                zone = bogota,
                snapshot = snapshot(
                    items = listOf(goalRoutine.copy(reasons = emptyList())),
                    personalized = false,
                    missing = listOf("nutrition_habits"),
                    notices = listOf("RESTRICTIONS_NOT_VERIFIED", "NOT_MEDICAL_ADVICE"),
                ),
            )
        )

        composeRule.onNodeWithTag("nutrition_routines_restrictions_not_verified").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            "No verificamos tus restricciones ni alergias alimentarias: revisa los alérgenos de cada rutina antes de seguirla.",
            useUnmergedTree = true,
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("nutrition_routines_not_personalized").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("nutrition_routines_personalized").assertDoesNotExist()
        composeRule.onNodeWithTag("nutrition_routines_missing_nutrition_habits", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("nutrition_routine_reason_RESTRICTION_COMPATIBLE", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag("nutrition_routines_not_medical_advice").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun conUsuariosCaidoIndicaQueNoSePudoConsultarElPerfil() {
        show(
            NutritionRoutinesUiState(
                zone = bogota,
                snapshot = snapshot(
                    personalized = false,
                    missing = listOf("nutrition_habits", "sports", "primary_goal"),
                    notices = listOf("RESTRICTIONS_NOT_VERIFIED", "NOT_MEDICAL_ADVICE"),
                    users = RoutineSourceStatus.UNAVAILABLE,
                ),
            )
        )

        composeRule.onNodeWithTag("nutrition_routines_context_unavailable", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("nutrition_routines_restrictions_not_verified").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun unaListaConfirmadaVaciaMuestraElEstadoVacio() {
        show(NutritionRoutinesUiState(zone = bogota, snapshot = snapshot(items = emptyList())))

        composeRule.onNodeWithTag("nutrition_routines_empty").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Por ahora no hay rutinas publicadas compatibles con tus restricciones y alergias.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun sinRedConservaLaListaConSuAntiguedadYAvisaSiEstaDesactualizada() {
        show(NutritionRoutinesUiState(zone = bogota, snapshot = snapshot(), stale = true, problem = NutritionRoutinesProblem.NO_CONNECTION))

        composeRule.onNodeWithTag("nutrition_routines_synced_at").assertIsDisplayed()
        composeRule.onNodeWithTag("nutrition_routines_stale").assertIsDisplayed()
        composeRule.onNodeWithText("Sin conexión: se muestra la última lista confirmada.").assertIsDisplayed()
        composeRule.onNodeWithTag("nutrition_routine_a").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun sinRedYSinCacheInformaQueAunNoSeSincronizoYPermiteReintentar() {
        show(NutritionRoutinesUiState(zone = bogota, problem = NutritionRoutinesProblem.NO_CONNECTION))

        composeRule.onNodeWithTag("nutrition_routines_never_synced").assertIsDisplayed()
        composeRule.onNodeWithTag("nutrition_routines_retry").performClick()
        assertEquals(listOf("retry"), events)
    }

    @Test
    fun unErrorConCacheOfreceReintentarYDeshabilitaElBotonDuranteLaConsulta() {
        show(NutritionRoutinesUiState(zone = bogota, snapshot = snapshot(), problem = NutritionRoutinesProblem.UNAVAILABLE, refreshing = true))

        composeRule.onNodeWithTag("nutrition_routines_refreshing").assertIsDisplayed()
        composeRule.onNodeWithTag("nutrition_routines_problem").assertIsDisplayed()
        composeRule.onNodeWithTag("nutrition_routines_retry").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun sinLaCapacidadExplicaElPlanRequerido() {
        show(NutritionRoutinesUiState(zone = bogota, problem = NutritionRoutinesProblem.CAPABILITY_REQUIRED))

        composeRule.onNodeWithText("Tu plan actual no incluye las rutinas de alimentación sugeridas.").assertIsDisplayed()
    }

    @Test
    fun elDetalleMuestraAlergenosComidasConNombresDelCatalogoYSinCompra() {
        show(
            NutritionRoutinesUiState(
                zone = bogota,
                snapshot = snapshot(),
                selected = goalRoutine,
                // Catálogo de HU043 guardado: OATS y WATER tienen nombre; BANANA no está y se muestra su código.
                foodLabelKeys = mapOf("OATS" to "food.oats", "WATER" to "food.water"),
            )
        )

        composeRule.onNodeWithText("Detalle de la rutina").assertIsDisplayed()
        composeRule.onNodeWithTag("nutrition_routine_detail").assertIsDisplayed()
        composeRule.onNodeWithText("Objetivo: Rendimiento").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Deporte: Carrera").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Patrón alimentario: Vegetariano").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("nutrition_routine_duration").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Vigencia: 14 días").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Alérgenos que contiene: Leche, Frutos secos de árbol").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("nutrition_routine_description").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("nutrition_routine_meal_0").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Desayuno", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Avena · 1 porción", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("BANANA · 2 porciones", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("nutrition_routine_meal_1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Hidratación", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("nutrition_routine_no_purchase").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("nutrition_routines_not_medical_advice").performScrollTo().assertIsDisplayed()
        // Sugerir no asigna ni vende: no hay botones de compra, pedido ni asignación (CA5).
        composeRule.onNodeWithText("Comprar", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Pedir", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Asignar", substring = true).assertDoesNotExist()

        composeRule.onNodeWithTag("nutrition_routines_back").performClick()
        assertEquals(listOf("close"), events)
    }

    @Test
    fun elDetalleDeUnaRutinaSinDeporteNiAlergenosLoDiceSinInventar() {
        show(NutritionRoutinesUiState(zone = bogota, snapshot = snapshot(), selected = sportRoutine))

        composeRule.onNodeWithText("Deporte: cualquiera").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("No declara alérgenos.").performScrollTo().assertIsDisplayed()
        // Sin catálogo guardado se muestra el food_code tal cual.
        composeRule.onNodeWithText("QUINOA · 1 porción", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun enElDetalleDeUnaListaNoVerificadaSeRepiteElAviso() {
        show(
            NutritionRoutinesUiState(
                zone = bogota,
                snapshot = snapshot(personalized = false, notices = listOf("RESTRICTIONS_NOT_VERIFIED", "NOT_MEDICAL_ADVICE")),
                selected = goalRoutine,
            )
        )

        composeRule.onNodeWithTag("nutrition_routines_restrictions_not_verified").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun enLaListaVolverSaleDeLaPantalla() {
        show(NutritionRoutinesUiState(zone = bogota, snapshot = snapshot()))

        composeRule.onNodeWithTag("nutrition_routines_back").performClick()

        assertEquals(listOf("back"), events)
    }
}
