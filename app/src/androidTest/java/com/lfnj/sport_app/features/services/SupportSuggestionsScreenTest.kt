package com.lfnj.sport_app.features.services

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lfnj.sport_app.features.services.model.ServiceFee
import com.lfnj.sport_app.features.services.model.SupportInterval
import com.lfnj.sport_app.features.services.model.SupportServiceSuggestion
import com.lfnj.sport_app.features.services.model.SupportSourceStatus
import com.lfnj.sport_app.features.services.model.SupportSuggestionsSnapshot
import com.lfnj.sport_app.ui.theme.SportappTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

/** HU029: estados de «Acompañamiento y logística», contexto evaluado, detalle informativo y accesibilidad. */
@RunWith(AndroidJUnit4::class)
class SupportSuggestionsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val bogota = ZoneId.of("America/Bogota")
    private val events = mutableListOf<String>()

    private val transport = SupportServiceSuggestion(
        serviceId = "a",
        title = "Transporte al Medio Maratón",
        summary = "Recogida en el punto de encuentro y regreso después de la carrera.",
        category = "TRANSPORT",
        modality = "IN_PERSON",
        fee = ServiceFee("80000.00", "COP", "SERVICE"),
        nextInterval = SupportInterval(Instant.parse("2026-11-08T10:00:00Z"), Instant.parse("2026-11-08T12:00:00Z"), ZoneId.of("America/Bogota")),
        authorName = "Carlos Rodríguez",
        reasons = listOf("EVENT_LOGISTICS"),
    )
    private val coaching = transport.copy(
        serviceId = "b",
        title = "Técnica de carrera",
        category = "COACHING",
        modality = "VIRTUAL",
        fee = ServiceFee("120000.00", "COP", "SESSION"),
        nextInterval = SupportInterval(Instant.parse("2026-10-28T13:00:00Z"), Instant.parse("2026-10-28T14:00:00Z"), ZoneId.of("Europe/Madrid")),
        authorName = null,
        reasons = listOf("UPCOMING_AVAILABILITY"),
    )

    private fun snapshot(
        items: List<SupportServiceSuggestion> = listOf(transport, coaching),
        personalized: Boolean = true,
        missing: List<String> = emptyList(),
        events: SupportSourceStatus = SupportSourceStatus.OK,
        users: SupportSourceStatus = SupportSourceStatus.OK,
    ) = SupportSuggestionsSnapshot(items, personalized, missing, Instant.EPOCH, events, users, Instant.parse("2026-10-24T13:30:00Z"))

    private fun show(state: SupportSuggestionsUiState) = composeRule.setContent {
        SportappTheme {
            SupportSuggestionsScreen(
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
        show(SupportSuggestionsUiState(zone = bogota, refreshing = true))

        composeRule.onNodeWithTag("support_suggestions_loading").assertIsDisplayed()
        composeRule.onNodeWithTag("support_suggestions_empty").assertDoesNotExist()
    }

    @Test
    fun cadaServicioMuestraProveedorCategoriaModalidadTarifaIntervaloYMotivo() {
        show(SupportSuggestionsUiState(zone = bogota, snapshot = snapshot()))

        composeRule.onNodeWithTag("support_suggestions_personalized").assertIsDisplayed()
        composeRule.onNodeWithTag("support_suggestions_fee_informative").assertIsDisplayed()
        composeRule.onNodeWithTag("support_suggestion_a").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Proveedor: Carlos Rodríguez", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Transporte · Presencial", useUnmergedTree = true).assertIsDisplayed()
        // Ambas tarjetas muestran su tarifa y su próximo intervalo (CA3).
        composeRule.onAllNodesWithText("Tarifa informativa:", substring = true, useUnmergedTree = true).assertCountEquals(2)
        composeRule.onNodeWithText("por servicio", substring = true, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onAllNodesWithTag("support_suggestion_next_interval", useUnmergedTree = true).assertCountEquals(2)
        composeRule.onNodeWithTag("support_suggestion_reason_EVENT_LOGISTICS", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Disponible el día de tu evento", useUnmergedTree = true).assertIsDisplayed()
        // Sin proveedor resuelto se dice así, sin inventar un nombre.
        composeRule.onNodeWithTag("support_suggestion_b").performScrollTo()
        composeRule.onNodeWithText("Proveedor no disponible", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Entrenamiento · Virtual", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Disponibilidad en los próximos 31 días", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("support_suggestions_synced_at").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun tocarUnServicioAbreSuDetalle() {
        show(SupportSuggestionsUiState(zone = bogota, snapshot = snapshot()))

        composeRule.onNode(hasTestTag("support_suggestion_a") and hasClickAction()).performScrollTo().performClick()

        assertEquals(listOf("open:a"), events)
    }

    @Test
    fun sinInscripcionesLoExplicaYNoAfirmaPrioridadPorEventos() {
        show(SupportSuggestionsUiState(zone = bogota, snapshot = snapshot(items = listOf(coaching), personalized = false, missing = listOf("event_registrations"))))

        composeRule.onNodeWithTag("support_suggestions_not_personalized").assertIsDisplayed()
        composeRule.onNodeWithTag("support_suggestions_personalized").assertDoesNotExist()
        composeRule.onNodeWithTag("support_suggestions_missing_event_registrations", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("support_suggestion_reason_EVENT_LOGISTICS", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun siEventosNoRespondioLoDeclara() {
        show(
            SupportSuggestionsUiState(
                zone = bogota,
                snapshot = snapshot(items = listOf(coaching), personalized = false, missing = listOf("event_registrations"), events = SupportSourceStatus.UNAVAILABLE),
            )
        )

        composeRule.onNodeWithTag("support_suggestions_events_unavailable", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("support_suggestions_missing_event_registrations", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun siUsuariosNoRespondioLoDeclara() {
        show(SupportSuggestionsUiState(zone = bogota, snapshot = snapshot(users = SupportSourceStatus.UNAVAILABLE)))

        composeRule.onNodeWithTag("support_suggestions_users_unavailable", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun unaListaVaciaSinInscripcionesSeExplica() {
        show(SupportSuggestionsUiState(zone = bogota, snapshot = snapshot(items = emptyList(), personalized = false, missing = listOf("event_registrations"))))

        composeRule.onNodeWithTag("support_suggestions_empty").assertIsDisplayed()
        composeRule.onNodeWithText(
            "No hay sugerencias: no tienes inscripciones a eventos vigentes y no hay entrenamientos con disponibilidad en los próximos 31 días.",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("support_suggestions_fee_informative").assertDoesNotExist()
    }

    @Test
    fun unaListaVaciaSinCoincidenciasSeExplica() {
        show(SupportSuggestionsUiState(zone = bogota, snapshot = snapshot(items = emptyList())))

        composeRule.onNodeWithText(
            "No hay servicios de entrenamiento, transporte o acompañamiento con disponibilidad que coincidan en los próximos 31 días.",
        ).assertIsDisplayed()
    }

    @Test
    fun sinRedConservaLaListaConSuAntiguedadYAvisaSiEstaDesactualizada() {
        show(SupportSuggestionsUiState(zone = bogota, snapshot = snapshot(), stale = true, problem = SupportSuggestionsProblem.NO_CONNECTION))

        composeRule.onNodeWithTag("support_suggestions_synced_at").assertIsDisplayed()
        composeRule.onNodeWithTag("support_suggestions_stale").assertIsDisplayed()
        composeRule.onNodeWithText("Sin conexión: se muestra la última lista confirmada.").assertIsDisplayed()
        composeRule.onNodeWithTag("support_suggestion_a").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun sinRedYSinCacheInformaQueAunNoSeSincronizoYPermiteReintentar() {
        show(SupportSuggestionsUiState(zone = bogota, problem = SupportSuggestionsProblem.NO_CONNECTION))

        composeRule.onNodeWithTag("support_suggestions_never_synced").assertIsDisplayed()
        composeRule.onNodeWithTag("support_suggestions_retry").performClick()
        assertEquals(listOf("retry"), events)
    }

    @Test
    fun unErrorConCacheOfreceReintentarYDeshabilitaElBotonDuranteLaConsulta() {
        show(SupportSuggestionsUiState(zone = bogota, snapshot = snapshot(), problem = SupportSuggestionsProblem.UNAVAILABLE, refreshing = true))

        composeRule.onNodeWithTag("support_suggestions_refreshing").assertIsDisplayed()
        composeRule.onNodeWithTag("support_suggestions_problem").assertIsDisplayed()
        composeRule.onNodeWithTag("support_suggestions_retry").assertIsNotEnabled()
    }

    @Test
    fun conServiciosCaidosYSinCacheMuestraElErrorYReintentar() {
        show(SupportSuggestionsUiState(zone = bogota, problem = SupportSuggestionsProblem.UNAVAILABLE))

        composeRule.onNodeWithTag("support_suggestions_error").assertIsDisplayed()
        composeRule.onNodeWithText("El servicio de sugerencias no está disponible en este momento. Inténtalo de nuevo más tarde.").assertIsDisplayed()
        composeRule.onNodeWithTag("support_suggestions_retry").performClick()
        assertEquals(listOf("retry"), events)
    }

    @Test
    fun sinLaCapacidadExplicaElPlanRequerido() {
        show(SupportSuggestionsUiState(zone = bogota, problem = SupportSuggestionsProblem.CAPABILITY_REQUIRED))

        composeRule.onNodeWithText("Tu plan actual no incluye las sugerencias de acompañamiento y logística.").assertIsDisplayed()
    }

    @Test
    fun elDetalleEsInformativoConIntervaloEnAmbasZonasYSinContratar() {
        show(SupportSuggestionsUiState(zone = bogota, snapshot = snapshot(), selected = coaching))

        composeRule.onNodeWithText("Detalle del servicio").assertIsDisplayed()
        composeRule.onNodeWithTag("support_suggestion_detail").assertIsDisplayed()
        composeRule.onNodeWithText("Categoría: Entrenamiento").assertIsDisplayed()
        composeRule.onNodeWithText("Modalidad: Virtual").assertIsDisplayed()
        composeRule.onNodeWithTag("support_suggestion_fee").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("support_suggestion_detail_interval").performScrollTo().assertIsDisplayed()
        // El proveedor está en otra zona: se muestra también su hora.
        composeRule.onNodeWithTag("support_suggestion_provider_zone").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Hora del proveedor (Europe/Madrid):", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("support_suggestion_description").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("support_suggestion_hire_info").performScrollTo().assertIsDisplayed()
        // Sugerir no contrata ni reserva: no hay botón de acción en el detalle.
        composeRule.onNode(hasClickAction() and hasTestTag("support_suggestion_detail")).assertDoesNotExist()
        composeRule.onNodeWithText("Reservar", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Pagar", substring = true).assertDoesNotExist()

        composeRule.onNodeWithTag("support_suggestions_back").performClick()
        assertEquals(listOf("close"), events)
    }

    @Test
    fun enLaListaVolverSaleDeLaPantalla() {
        show(SupportSuggestionsUiState(zone = bogota, snapshot = snapshot()))

        composeRule.onNodeWithTag("support_suggestions_back").performClick()

        assertEquals(listOf("back"), events)
    }
}
