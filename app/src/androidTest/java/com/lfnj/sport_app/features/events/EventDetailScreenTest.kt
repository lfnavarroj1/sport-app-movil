package com.lfnj.sport_app.features.events

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lfnj.sport_app.features.events.model.EventDetail
import com.lfnj.sport_app.features.events.model.EventPlace
import com.lfnj.sport_app.features.events.model.EventPrice
import com.lfnj.sport_app.features.events.model.EventRegistration
import com.lfnj.sport_app.features.events.model.MyRegistration
import com.lfnj.sport_app.features.events.model.RegisteredEvent
import com.lfnj.sport_app.ui.theme.SportappTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** HU041: estados del detalle, confirmación, resultado, errores propios, sin red y accesibilidad. */
@RunWith(AndroidJUnit4::class)
class EventDetailScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val bogota = ZoneId.of("America/Bogota")
    private val events = mutableListOf<String>()
    private val locale: Locale = Locale.getDefault()
    private val startsAt = Instant.parse("2026-11-15T12:00:00Z")

    private fun detail(fee: EventPrice? = null, myRegistration: MyRegistration? = null) = EventDetail(
        eventId = EVENT_ID, name = "Carrera 10K Bogotá", description = "Recorrido urbano de 10 km.", discipline = "Running",
        location = EventPlace("Parque Metropolitano Simón Bolívar", "Bogotá", "CO"),
        startsAt = startsAt, endsAt = Instant.parse("2026-11-15T15:00:00Z"), timeZone = "America/Bogota",
        capacity = 500, version = 2, fee = fee, myRegistration = myRegistration,
    )

    private val confirmed = EventRegistration(
        REGISTRATION_ID, EVENT_ID, Instant.parse("2026-10-12T22:30:00Z"), null, 1,
        RegisteredEvent("Carrera 10K Bogotá", startsAt, Instant.parse("2026-11-15T15:00:00Z"), "America/Bogota", EventPlace("Parque", "Bogotá", "CO")),
    )

    private fun state(
        detail: EventDetail? = detail(),
        loading: Boolean = false,
        problem: EventDetailProblem? = null,
        online: Boolean = true,
        started: Boolean = false,
        confirming: Boolean = false,
        submitting: Boolean = false,
        confirmedRegistration: EventRegistration? = null,
        failure: RegistrationFailure? = null,
    ) = EventDetailUiState(
        eventId = EVENT_ID, zone = bogota, loading = loading, detail = detail, problem = problem, online = online,
        started = started, confirming = confirming, submitting = submitting, confirmed = confirmedRegistration, failure = failure,
    )

    private fun show(state: EventDetailUiState) = composeRule.setContent {
        SportappTheme {
            EventDetailScreen(
                state = state,
                onRegister = { events += "register" },
                onConfirmRegister = { events += "confirm" },
                onCancelRegister = { events += "cancel" },
                onDismissFailure = { events += "dismiss" },
                onRetry = { events += "retry" },
                onOpenRegistrations = { events += "registrations" },
                onBack = { events += "back" },
            )
        }
    }

    @Test
    fun cargaMuestraProgreso() {
        show(state(detail = null, loading = true))

        composeRule.onNodeWithTag("event_loading").assertIsDisplayed()
    }

    @Test
    fun elDetalleMuestraFechaEnLaZonaDelDispositivoUbicacionCupoYTarifaInformativa() {
        show(state(detail = detail(fee = EventPrice("85000.00", "COP"))))

        composeRule.onNodeWithTag("event_name").assertIsDisplayed()
        val (start, end) = EventFormat.range(startsAt, Instant.parse("2026-11-15T15:00:00Z"), bogota, locale)
        composeRule.onNodeWithText("$start – $end").assertIsDisplayed()
        composeRule.onNodeWithText("Zona horaria del evento: America/Bogota").assertIsDisplayed()
        composeRule.onNodeWithText("Parque Metropolitano Simón Bolívar, Bogotá (CO)").assertIsDisplayed()
        composeRule.onNodeWithText("Cupo declarado: 500 participantes").assertIsDisplayed()
        composeRule.onNodeWithText("Tarifa informativa: 85000.00 COP").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("event_fee_notice").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("El pago no se procesa en SportApp.").assertIsDisplayed()
    }

    @Test
    fun sinTarifaNoSeMuestraElAviso() {
        show(state())

        composeRule.onNodeWithTag("event_fee").assertDoesNotExist()
    }

    @Test
    fun inscribirmeAbreLaConfirmacion() {
        show(state())

        composeRule.onNodeWithTag("event_register").performScrollTo().assertIsEnabled().assertHasClickAction().performClick()
        assertEquals(listOf("register"), events)
    }

    @Test
    fun laConfirmacionExplicitaPermiteConfirmarOCancelar() {
        show(state(confirming = true))

        composeRule.onNodeWithTag("event_confirm_message").assertIsDisplayed()
        composeRule.onNodeWithTag("event_confirm_cancel").performClick()
        composeRule.onNodeWithTag("event_confirm").performClick()
        assertEquals(listOf("cancel", "confirm"), events)
    }

    @Test
    fun duranteElEnvioElBotonQuedaDeshabilitadoYSeMuestraProgreso() {
        show(state(submitting = true))

        composeRule.onNodeWithTag("event_submitting").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("event_register").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("event_register").performClick()
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun elExitoMuestraLaInscripcionConfirmadaYElAccesoAMisInscripciones() {
        show(state(confirmedRegistration = confirmed))

        composeRule.onNodeWithTag("event_registration_confirmed").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Inscripción confirmada").assertIsDisplayed()
        composeRule.onNodeWithTag("event_register").assertDoesNotExist()
        composeRule.onNodeWithTag("event_open_registrations").performScrollTo().performClick()
        assertEquals(listOf("registrations"), events)
    }

    @Test
    fun conMyRegistrationSeIndicaYaInscritoSinBoton() {
        show(state(detail = detail(myRegistration = MyRegistration(REGISTRATION_ID, Instant.EPOCH))))

        composeRule.onNodeWithTag("event_registered").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Ya estás inscrito en este evento.").assertIsDisplayed()
        composeRule.onNodeWithTag("event_register").assertDoesNotExist()
        composeRule.onNodeWithTag("event_open_registrations").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun unEventoIniciadoDeshabilitaElBotonConExplicacion() {
        show(state(started = true))

        composeRule.onNodeWithTag("event_register").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("El evento ya inició: la inscripción está cerrada.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun sinConexionElBotonQuedaDeshabilitadoConExplicacion() {
        show(state(online = false))

        composeRule.onNodeWithTag("event_register").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("event_register_block").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Sin conexión. La inscripción requiere conexión a internet para confirmarse.").assertIsDisplayed()
    }

    @Test
    fun sinRedAlAbrirElDetalleLaInscripcionQuedaDeshabilitadaYSePuedeVerLaCache() {
        show(state(detail = null, problem = EventDetailProblem.NO_CONNECTION, online = false))

        composeRule.onNodeWithTag("event_error").assertIsDisplayed()
        composeRule.onNodeWithTag("event_register").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("event_register_block").assertIsDisplayed()
        composeRule.onNodeWithTag("event_retry").performClick()
        composeRule.onNodeWithTag("event_open_registrations").performScrollTo().performClick()
        assertEquals(listOf("retry", "registrations"), events)
    }

    @Test
    fun unEventoInexistenteNoOfreceReintentoNiRevelaUnBorrador() {
        show(state(detail = null, problem = EventDetailProblem.NOT_FOUND))

        composeRule.onNodeWithText("Este evento no existe o ya no está publicado.").assertIsDisplayed()
        composeRule.onNodeWithTag("event_retry").assertDoesNotExist()
        composeRule.onNodeWithTag("event_register").assertDoesNotExist()
    }

    @Test
    fun yaInscritoSinCupoYCerradoTienenMensajesPropios() {
        val cases = mapOf(
            RegistrationFailure.ALREADY_REGISTERED to "Ya estás inscrito en este evento.",
            RegistrationFailure.FULL to "El evento no tiene cupos disponibles.",
            RegistrationFailure.CLOSED to "El evento ya inició: la inscripción está cerrada.",
        )
        var current by mutableStateOf(RegistrationFailure.FULL)
        composeRule.setContent {
            SportappTheme {
                EventDetailScreen(
                    state = state(failure = current), onRegister = {}, onConfirmRegister = {}, onCancelRegister = {},
                    onDismissFailure = { events += "dismiss" }, onRetry = {}, onOpenRegistrations = {}, onBack = {},
                )
            }
        }
        cases.forEach { (failure, message) ->
            current = failure
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("event_failure_${failure.name.lowercase()}").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText(message, useUnmergedTree = true).assertExists()
        }
        composeRule.onNodeWithTag("event_failure_dismiss").performScrollTo().performClick()
        assertEquals(listOf("dismiss"), events)
    }

    @Test
    fun yaInscritoPorConflictoOfreceVerLaInscripcion() {
        show(state(failure = RegistrationFailure.ALREADY_REGISTERED))

        composeRule.onNodeWithTag("event_open_registrations").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf("registrations"), events)
    }

    @Test
    fun volverTieneDescripcionAccesible() {
        show(state())

        composeRule.onNodeWithTag("event_back").assertHasClickAction().performClick()
        assertEquals(listOf("back"), events)
    }

    private companion object {
        const val EVENT_ID = "683f4e38-6bb3-4c47-a6dc-9bb1588c67ef"
        const val REGISTRATION_ID = "6d2f0c8e-4a1b-4f3e-9c7d-2b8a5e1f0d43"
    }
}
