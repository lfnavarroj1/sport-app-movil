package com.lfnj.sport_app.features.recommendations

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lfnj.sport_app.features.recommendations.model.ContextSourceStatus
import com.lfnj.sport_app.features.recommendations.model.PlanSuggestionsSnapshot
import com.lfnj.sport_app.features.recommendations.model.PlanTemplateSuggestion
import com.lfnj.sport_app.features.recommendations.model.WeeklySession
import com.lfnj.sport_app.ui.theme.SportappTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/** HU028: estados de «Entrenamientos sugeridos», personalización, detalle con sesiones semanales y accesibilidad. */
@RunWith(AndroidJUnit4::class)
class PlanSuggestionsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val bogota = ZoneId.of("America/Bogota")
    private val events = mutableListOf<String>()

    private val goalTemplate = PlanTemplateSuggestion(
        templateId = "a", professionalId = "p", title = "Base aeróbica 10K", summary = "Construir la base aeróbica para una carrera de 10 kilómetros.",
        sport = "running", goal = "PERFORMANCE", level = "BEGINNER", durationWeeks = 8,
        weeklySessions = listOf(WeeklySession(DayOfWeek.MONDAY, "Rodaje suave en zona 2", 45), WeeklySession(DayOfWeek.SATURDAY, "Fondo largo", 60)),
        publishedAt = Instant.parse("2026-10-20T14:00:00Z"), authorName = "Laura Gómez", reasons = listOf("SPORT_MATCH", "GOAL_MATCH"),
    )
    private val sportTemplate = goalTemplate.copy(
        templateId = "b", title = "Fuerza para corredores", level = "ADVANCED", durationWeeks = 1, authorName = null, reasons = listOf("SPORT_MATCH"),
        weeklySessions = listOf(WeeklySession(DayOfWeek.WEDNESDAY, "Circuito", 30)),
    )

    private fun snapshot(
        items: List<PlanTemplateSuggestion> = listOf(goalTemplate, sportTemplate),
        personalized: Boolean = true,
        missing: List<String> = emptyList(),
        users: ContextSourceStatus = ContextSourceStatus.OK,
    ) = PlanSuggestionsSnapshot(items, personalized, missing, Instant.EPOCH, users, Instant.parse("2026-10-24T13:30:00Z"))

    private fun show(state: PlanSuggestionsUiState) = composeRule.setContent {
        SportappTheme {
            PlanSuggestionsScreen(
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
        show(PlanSuggestionsUiState(zone = bogota, refreshing = true))

        composeRule.onNodeWithTag("plan_suggestions_loading").assertIsDisplayed()
        composeRule.onNodeWithTag("plan_suggestions_empty").assertDoesNotExist()
    }

    @Test
    fun cadaPlantillaMuestraAutorNivelDuracionDeporteYMotivo() {
        show(PlanSuggestionsUiState(zone = bogota, snapshot = snapshot()))

        composeRule.onNodeWithTag("plan_suggestions_personalized").assertIsDisplayed()
        composeRule.onNodeWithText("Para ti: plantillas de tus deportes, primero las de tu objetivo principal.").assertIsDisplayed()
        composeRule.onNodeWithTag("plan_suggestion_a").assertIsDisplayed()
        composeRule.onNodeWithText("Por Laura Gómez", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Carrera · Principiante · 8 semanas", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("2 sesiones por semana", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Coincide con tu objetivo principal", useUnmergedTree = true).assertIsDisplayed()
        // Sin autor resuelto se dice así, sin inventar un nombre; los plurales respetan la cantidad.
        composeRule.onNodeWithTag("plan_suggestion_b").performScrollTo()
        composeRule.onNodeWithText("Autor no disponible", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Carrera · Avanzado · 1 semana", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("1 sesión por semana", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("plan_suggestions_synced_at").assertIsDisplayed()
    }

    @Test
    fun tocarUnaPlantillaAbreSuDetalle() {
        show(PlanSuggestionsUiState(zone = bogota, snapshot = snapshot()))

        composeRule.onNode(hasTestTag("plan_suggestion_a") and hasClickAction()).performClick()

        assertEquals(listOf("open:a"), events)
    }

    @Test
    fun laListaNoPersonalizadaLoDiceYExplicaQueFalta() {
        show(PlanSuggestionsUiState(zone = bogota, snapshot = snapshot(items = listOf(goalTemplate.copy(reasons = emptyList())), personalized = false, missing = listOf("sports"))))

        composeRule.onNodeWithTag("plan_suggestions_not_personalized").assertIsDisplayed()
        composeRule.onNodeWithTag("plan_suggestions_personalized").assertDoesNotExist()
        composeRule.onNodeWithText("Lista no personalizada: mostramos las plantillas publicadas más recientes.", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("plan_suggestions_missing_sports", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("plan_suggestion_reason_SPORT_MATCH", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun conUsuariosCaidoIndicaQueNoSePudoConsultarElPerfil() {
        show(
            PlanSuggestionsUiState(
                zone = bogota,
                snapshot = snapshot(personalized = false, missing = listOf("sports", "primary_goal"), users = ContextSourceStatus.UNAVAILABLE),
            )
        )

        composeRule.onNodeWithTag("plan_suggestions_context_unavailable", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("plan_suggestions_not_personalized").assertIsDisplayed()
    }

    @Test
    fun sinObjetivoPrincipalLaListaSigueSiendoPorDeporteYLoExplica() {
        show(PlanSuggestionsUiState(zone = bogota, snapshot = snapshot(missing = listOf("primary_goal"))))

        composeRule.onNodeWithTag("plan_suggestions_personalized").assertIsDisplayed()
        composeRule.onNodeWithText("Para ti: plantillas de tus deportes. Sin tu objetivo principal no podemos priorizarlas.", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("plan_suggestions_missing_primary_goal", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun unaListaConfirmadaVaciaMuestraElEstadoVacio() {
        show(PlanSuggestionsUiState(zone = bogota, snapshot = snapshot(items = emptyList())))

        composeRule.onNodeWithTag("plan_suggestions_empty").assertIsDisplayed()
        composeRule.onNodeWithText("Por ahora no hay plantillas publicadas para tus deportes.").assertIsDisplayed()
    }

    @Test
    fun sinRedConservaLaListaConSuAntiguedadYAvisaSiEstaDesactualizada() {
        show(PlanSuggestionsUiState(zone = bogota, snapshot = snapshot(), stale = true, problem = PlanSuggestionsProblem.NO_CONNECTION))

        composeRule.onNodeWithTag("plan_suggestions_synced_at").assertIsDisplayed()
        composeRule.onNodeWithTag("plan_suggestions_stale").assertIsDisplayed()
        composeRule.onNodeWithText("Sin conexión. Mostramos las sugerencias sincronizadas por última vez.").assertIsDisplayed()
        composeRule.onNodeWithTag("plan_suggestion_a").assertIsDisplayed()
    }

    @Test
    fun sinRedYSinCacheInformaQueAunNoSeSincronizoYPermiteReintentar() {
        show(PlanSuggestionsUiState(zone = bogota, problem = PlanSuggestionsProblem.NO_CONNECTION))

        composeRule.onNodeWithTag("plan_suggestions_never_synced").assertIsDisplayed()
        composeRule.onNodeWithTag("plan_suggestions_retry").performClick()
        assertEquals(listOf("retry"), events)
    }

    @Test
    fun unErrorConCacheOfreceReintentarYDeshabilitaElBotonDuranteLaConsulta() {
        show(PlanSuggestionsUiState(zone = bogota, snapshot = snapshot(), problem = PlanSuggestionsProblem.UNAVAILABLE, refreshing = true))

        composeRule.onNodeWithTag("plan_suggestions_refreshing").assertIsDisplayed()
        composeRule.onNodeWithTag("plan_suggestions_problem").assertIsDisplayed()
        composeRule.onNodeWithTag("plan_suggestions_retry").assertIsNotEnabled()
    }

    @Test
    fun sinLaCapacidadExplicaElPlanRequerido() {
        show(PlanSuggestionsUiState(zone = bogota, problem = PlanSuggestionsProblem.CAPABILITY_REQUIRED))

        composeRule.onNodeWithText("Tu plan actual no incluye entrenamientos sugeridos.").assertIsDisplayed()
    }

    @Test
    fun elDetalleMuestraSesionesSemanalesMotivoYAutorizacionInformativaSinAsignar() {
        show(PlanSuggestionsUiState(zone = bogota, snapshot = snapshot(), selected = goalTemplate))

        composeRule.onNodeWithText("Detalle de la plantilla").assertIsDisplayed()
        composeRule.onNodeWithTag("plan_suggestion_detail").assertIsDisplayed()
        composeRule.onNodeWithText("Objetivo: Rendimiento").assertIsDisplayed()
        composeRule.onNodeWithText("Nivel: Principiante").assertIsDisplayed()
        composeRule.onNodeWithTag("plan_suggestion_duration").assertIsDisplayed()
        composeRule.onNodeWithTag("plan_suggestion_objectives").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("plan_suggestion_session_0").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("plan_suggestion_session_1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Fondo largo", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("plan_suggestion_authorize_info").performScrollTo().assertIsDisplayed()
        // Sugerir no asigna ni inicia el plan: no hay botón de acción en el detalle.
        composeRule.onNodeWithText("Asignar", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Iniciar", substring = true).assertDoesNotExist()

        composeRule.onNodeWithTag("plan_suggestions_back").performClick()
        assertEquals(listOf("close"), events)
    }

    @Test
    fun enLaListaVolverSaleDeLaPantalla() {
        show(PlanSuggestionsUiState(zone = bogota, snapshot = snapshot()))

        composeRule.onNodeWithTag("plan_suggestions_back").performClick()

        assertEquals(listOf("back"), events)
    }
}
