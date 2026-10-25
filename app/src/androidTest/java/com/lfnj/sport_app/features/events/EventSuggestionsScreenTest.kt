package com.lfnj.sport_app.features.events

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
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
import com.lfnj.sport_app.features.events.model.EventPlace
import com.lfnj.sport_app.features.events.model.EventPrice
import com.lfnj.sport_app.features.events.model.EventSuggestion
import com.lfnj.sport_app.features.events.model.EventSuggestionsSnapshot
import com.lfnj.sport_app.features.events.model.MyRegistration
import com.lfnj.sport_app.features.recommendations.model.ContextSourceStatus
import com.lfnj.sport_app.ui.theme.SportappTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** HU027: estados de «Eventos sugeridos», personalización, datos de cada evento, «Ya inscrito», apertura del detalle y accesibilidad. */
@RunWith(AndroidJUnit4::class)
class EventSuggestionsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val bogota = ZoneId.of("America/Bogota")

    /** Las fechas siguen el idioma del dispositivo: se formatean igual que la pantalla. */
    private val locale: Locale = Locale.getDefault()
    private val events = mutableListOf<String>()

    private val match = EventSuggestion(
        eventId = "a", title = "Media maratón de Bogotá", summary = "Recorrido urbano de 21 km.", discipline = "Atletismo",
        location = EventPlace("Parque Simón Bolívar", "Bogotá", "CO"), startsAt = Instant.parse("2026-11-15T12:00:00Z"),
        endsAt = Instant.parse("2026-11-15T17:00:00Z"), timeZone = "America/Bogota", capacity = 500,
        fee = EventPrice("45000.00", "COP"), myRegistration = null, reasons = listOf("SPORT_MATCH", "CITY_MATCH"),
    )
    private val noFee = match.copy(eventId = "b", title = "Gran fondo La Calera", discipline = "Ciclismo", fee = null, reasons = listOf("SPORT_MATCH"))
    private val registered = match.copy(
        eventId = "c", title = "Carrera 10K Usaquén", fee = EventPrice("0.00", "COP"),
        myRegistration = MyRegistration("r-1", Instant.parse("2026-10-21T10:00:00Z")),
    )

    private fun snapshot(
        items: List<EventSuggestion> = listOf(match, noFee, registered),
        personalized: Boolean = true,
        missing: List<String> = emptyList(),
        users: ContextSourceStatus = ContextSourceStatus.OK,
    ) = EventSuggestionsSnapshot(items, personalized, missing, Instant.EPOCH, users, Instant.parse("2026-10-24T13:30:00Z"))

    private fun show(state: EventSuggestionsUiState) = composeRule.setContent {
        SportappTheme {
            EventSuggestionsScreen(
                state = state,
                onOpenEvent = { events += "open:$it" },
                onRetry = { events += "retry" },
                onBack = { events += "back" },
            )
        }
    }

    @Test
    fun muestraCargaSinInventarDatos() {
        show(EventSuggestionsUiState(zone = bogota, refreshing = true))

        composeRule.onNodeWithTag("event_suggestions_loading").assertIsDisplayed()
        composeRule.onNodeWithTag("event_suggestions_empty").assertDoesNotExist()
    }

    @Test
    fun cadaEventoMuestraMotivoFechaZonaLugarTarifaYCupo() {
        show(EventSuggestionsUiState(zone = bogota, snapshot = snapshot()))

        composeRule.onNodeWithTag("event_suggestions_personalized").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Sugeridos según tus deportes y tu ciudad", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("event_suggestion_a").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("event_suggestion_reason_a_SPORT_MATCH", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("event_suggestion_reason_a_CITY_MATCH", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("En tu ciudad", substring = true, useUnmergedTree = true).assertExists()
        val (start, end) = EventFormat.range(match.startsAt, match.endsAt, bogota, locale)
        composeRule.onNodeWithText("$start – $end", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Hora de tu teléfono. Zona del evento: America/Bogota", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Parque Simón Bolívar · Bogotá (CO)", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Tarifa informativa: 45000.00 COP", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("event_suggestion_capacity_a", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Cupo declarado: 500 participantes", substring = true, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("event_suggestion_registered_a", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag("event_suggestions_synced_at").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("event_suggestions_notice").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun sinTarifaLoDiceYUnaTarifaCeroEsGratuita() {
        show(EventSuggestionsUiState(zone = bogota, snapshot = snapshot()))

        composeRule.onNodeWithTag("event_suggestion_b").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Sin tarifa", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("event_suggestion_c").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Tarifa informativa: gratuito", useUnmergedTree = true).assertExists()
    }

    @Test
    fun elEventoInscritoSeMarcaComoYaInscrito() {
        show(EventSuggestionsUiState(zone = bogota, snapshot = snapshot()))

        composeRule.onNodeWithTag("event_suggestion_c").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("event_suggestion_registered_c", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Ya inscrito", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("event_suggestion_registered_b", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun tocarUnEventoAbreSuDetalleConEtiquetaAccesible() {
        show(EventSuggestionsUiState(zone = bogota, snapshot = snapshot()))

        composeRule.onNode(hasTestTag("event_suggestion_a") and hasClickAction())
            .assert(SemanticsMatcher("etiqueta de acción") { node ->
                node.config.getOrNull(SemanticsActions.OnClick)?.label == "Abrir el detalle de Media maratón de Bogotá"
            })
            .performScrollTo()
            .performClick()

        assertEquals(listOf("open:a"), events)
    }

    @Test
    fun laListaNoPersonalizadaLoDiceYExplicaQueFalta() {
        show(
            EventSuggestionsUiState(
                zone = bogota,
                snapshot = snapshot(items = listOf(match.copy(reasons = listOf("UPCOMING"))), personalized = false, missing = listOf("sports", "city")),
            )
        )

        composeRule.onNodeWithTag("event_suggestions_not_personalized").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("event_suggestions_personalized").assertDoesNotExist()
        composeRule.onNodeWithText("Próximos eventos publicados: esta lista no está personalizada", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("event_suggestions_missing_sports", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("event_suggestions_missing_city", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("event_suggestion_reason_a_UPCOMING", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("event_suggestion_reason_a_SPORT_MATCH", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun conUsuariosCaidoIndicaQueNoSePudoConsultarElPerfil() {
        show(
            EventSuggestionsUiState(
                zone = bogota,
                snapshot = snapshot(items = listOf(match.copy(reasons = listOf("UPCOMING"))), personalized = false, missing = listOf("sports", "city"), users = ContextSourceStatus.UNAVAILABLE),
            )
        )

        composeRule.onNodeWithTag("event_suggestions_context_unavailable", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("event_suggestions_missing_sports", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun unaListaConfirmadaVaciaMuestraElEstadoVacio() {
        show(EventSuggestionsUiState(zone = bogota, snapshot = snapshot(items = emptyList())))

        composeRule.onNodeWithTag("event_suggestions_empty").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("No hay eventos publicados en los próximos 90 días para sugerirte.").assertExists()
    }

    @Test
    fun sinRedConservaLaListaConSuAntiguedadYAvisaSiEstaDesactualizada() {
        show(EventSuggestionsUiState(zone = bogota, snapshot = snapshot(), stale = true, problem = EventSuggestionsProblem.NO_CONNECTION))

        composeRule.onNodeWithTag("event_suggestions_stale").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("event_suggestions_problem").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Sin conexión: se muestra la última lista confirmada.").assertExists()
        composeRule.onNodeWithTag("event_suggestions_synced_at").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("event_suggestion_a").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun sinRedYSinCacheInformaQueAunNoSeSincronizoYPermiteReintentar() {
        show(EventSuggestionsUiState(zone = bogota, problem = EventSuggestionsProblem.NO_CONNECTION))

        composeRule.onNodeWithTag("event_suggestions_never_synced").assertIsDisplayed()
        composeRule.onNodeWithTag("event_suggestions_empty").assertDoesNotExist()
        composeRule.onNodeWithTag("event_suggestions_retry").performClick()
        assertEquals(listOf("retry"), events)
    }

    @Test
    fun unErrorConCacheOfreceReintentarYDeshabilitaElBotonDuranteLaConsulta() {
        show(EventSuggestionsUiState(zone = bogota, snapshot = snapshot(), problem = EventSuggestionsProblem.UNAVAILABLE, refreshing = true))

        // Con el indicador indeterminado activo no se desplaza: la pantalla no queda inactiva y la acción no termina.
        composeRule.onNodeWithTag("event_suggestions_refreshing").assertIsDisplayed()
        composeRule.onNodeWithTag("event_suggestions_problem").assertIsDisplayed()
        composeRule.onNodeWithTag("event_suggestions_retry").assertIsNotEnabled()
    }

    @Test
    fun sinCapacidadMuestraElMensajeSinDatosGuardados() {
        show(EventSuggestionsUiState(zone = bogota, problem = EventSuggestionsProblem.CAPABILITY_REQUIRED))

        composeRule.onNodeWithText("Tu plan actual no incluye eventos sugeridos.").assertIsDisplayed()
        composeRule.onNodeWithTag("event_suggestion_a").assertDoesNotExist()
    }

    @Test
    fun volverNotificaLaNavegacion() {
        show(EventSuggestionsUiState(zone = bogota, snapshot = snapshot()))

        composeRule.onNodeWithTag("event_suggestions_back").performClick()
        assertEquals(listOf("back"), events)
    }
}
