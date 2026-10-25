package com.lfnj.sport_app.features.services

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lfnj.sport_app.R
import com.lfnj.sport_app.features.services.model.Appointment
import com.lfnj.sport_app.features.services.model.AppointmentStatus
import com.lfnj.sport_app.features.services.model.AppointmentsSnapshot
import com.lfnj.sport_app.features.services.model.AvailabilitySlot
import com.lfnj.sport_app.features.services.model.CatalogSnapshot
import com.lfnj.sport_app.features.services.model.CoachingService
import com.lfnj.sport_app.features.services.model.ServiceFee
import com.lfnj.sport_app.features.services.model.ServiceModality
import com.lfnj.sport_app.ui.theme.SportappTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** HU040: estados de «Sesiones virtuales», selección, confirmación, conflictos, sin plan, cancelación y accesibilidad. */
@RunWith(AndroidJUnit4::class)
class VirtualSessionsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)

    private val now = Instant.parse("2026-10-25T15:00:00Z")
    private val bogota = ZoneId.of("America/Bogota")
    private val events = mutableListOf<String>()
    private val fee = ServiceFee("120000.00", "COP", "SESSION")

    private val soon = AvailabilitySlot("pronto", now.plus(Duration.ofMinutes(30)), now.plus(Duration.ofMinutes(90)), bogota, 1)
    private val slot = AvailabilitySlot("slot-1", now.plus(Duration.ofDays(3)), now.plus(Duration.ofDays(3)).plus(Duration.ofHours(1)), ZoneId.of("Europe/Madrid"), 1)
    private val service = CoachingService("svc", "prov", "Asesoría de ritmo para 10K", "Revisión de objetivos.", ServiceModality.VIRTUAL, fee, listOf(soon, slot))

    private fun appointment(id: String, startsAt: Instant, status: AppointmentStatus = AppointmentStatus.BOOKED) = Appointment(
        appointmentId = id, serviceId = "svc", availabilityId = "slot-$id", providerId = "prov", serviceName = "Asesoría de ritmo para 10K",
        modality = "VIRTUAL", fee = fee, startsAt = startsAt, endsAt = startsAt.plus(Duration.ofHours(1)), timeZone = bogota, status = status,
        bookedAt = now.minus(Duration.ofDays(1)), cancelledAt = if (status == AppointmentStatus.BOOKED) null else now, version = 1,
    )

    private fun state(
        catalog: CatalogSnapshot? = CatalogSnapshot(listOf(service), false, now),
        appointments: AppointmentsSnapshot? = AppointmentsSnapshot(emptyList(), now),
        tab: VirtualSessionsTab = VirtualSessionsTab.COACHES,
        selected: CoachingService? = null,
        booking: BookingDraft? = null,
        cancelling: CancelDraft? = null,
        offline: Boolean = false,
        catalogProblem: VirtualSessionsProblem? = null,
        catalogStale: Boolean = false,
    ) = VirtualSessionsUiState(
        zone = bogota, now = now, tab = tab, catalog = catalog, catalogStale = catalogStale, catalogProblem = catalogProblem,
        appointments = appointments, selected = selected, booking = booking, cancelling = cancelling, offline = offline,
    )

    private fun show(state: VirtualSessionsUiState) = composeRule.setContent {
        SportappTheme {
            VirtualSessionsScreen(
                state = state,
                onSelectTab = { events += "tab:$it" },
                onOpenService = { events += "open:$it" },
                onCloseService = { events += "close" },
                onChooseSlot = { events += "slot:$it" },
                onConfirmBooking = { events += "confirm" },
                onDismissBooking = { events += "dismiss" },
                onRequestCancel = { events += "cancel:$it" },
                onConfirmCancel = { events += "confirmCancel" },
                onDismissCancel = { events += "keep" },
                onRetry = { events += "retry" },
                onBack = { events += "back" },
            )
        }
    }

    @Test
    fun muestraCargaMientrasNoHayDatos() {
        show(state(catalog = null))
        composeRule.onNodeWithTag("virtual_sessions_loading").assertIsDisplayed()
    }

    @Test
    fun sinRedYSinCacheExplicaQueNuncaSeSincronizoYPermiteReintentar() {
        show(state(catalog = null, catalogProblem = VirtualSessionsProblem.NO_CONNECTION, offline = true))
        composeRule.onNodeWithTag("virtual_sessions_never_synced").assertIsDisplayed()
        composeRule.onNodeWithTag("virtual_sessions_offline").assertIsDisplayed()
        composeRule.onNodeWithTag("virtual_sessions_retry").performClick()
        assertEquals(listOf("retry"), events)
    }

    @Test
    fun catalogoVacioNoEsError() {
        show(state(catalog = CatalogSnapshot(emptyList(), false, now)))
        composeRule.onNodeWithTag("virtual_sessions_empty_coaches").assertIsDisplayed()
        composeRule.onNodeWithTag("virtual_sessions_error").assertDoesNotExist()
    }

    @Test
    fun listaLosEntrenadoresConTarifaInformativaYAbreSuDetalle() {
        show(state(catalogStale = true))
        composeRule.onNodeWithTag("virtual_sessions_fee_informative").assertIsDisplayed()
        composeRule.onNodeWithTag("virtual_sessions_catalog_stale").assertIsDisplayed()
        composeRule.onNodeWithText("Asesoría de ritmo para 10K").assertIsDisplayed()
        composeRule.onNodeWithTag("virtual_service_svc").performClick()
        assertEquals(listOf("open:svc"), events)
    }

    @Test
    fun elDetalleMuestraLaZonaDelProfesionalYSoloOfreceIntervalosReservables() {
        show(state(selected = service))
        composeRule.onNodeWithTag("virtual_service_detail").assertIsDisplayed()
        composeRule.onNodeWithTag("virtual_service_meeting_link").performScrollTo().assertIsDisplayed()
        // Menos de 1 h de antelación: explicado y sin botón.
        composeRule.onNodeWithTag("virtual_slot_too_soon_pronto").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("virtual_slot_book_pronto").assertDoesNotExist()
        composeRule.onNodeWithText("Europe/Madrid", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("virtual_slot_book_slot-1").performScrollTo()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
            .performClick()
        assertEquals(listOf("slot:slot-1"), events)
    }

    @Test
    fun laConfirmacionExplicaEnlaceTarifaYVentanaDeCancelacion() {
        show(state(selected = service, booking = BookingDraft(service, slot, "k")))
        composeRule.onNodeWithTag("virtual_booking_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("virtual_booking_cancel_policy").assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.virtual_sessions_meeting_link)).assertExists()
        composeRule.onNodeWithTag("virtual_booking_confirm").assertIsEnabled().performClick()
        assertEquals(listOf("confirm"), events)
    }

    @Test
    fun sinConexionNoSePuedeConfirmar() {
        show(state(selected = service, booking = BookingDraft(service, slot, "k"), offline = true))
        composeRule.onNodeWithTag("virtual_booking_requires_connection").assertIsDisplayed()
        composeRule.onNodeWithTag("virtual_booking_confirm").assertIsNotEnabled()
    }

    @Test
    fun intervaloLlenoEsUnConflictoQuePideElegirOtroHorario() {
        show(state(selected = service, booking = BookingDraft(service, slot, "k", problem = BookingProblem.FULL)))
        composeRule.onNodeWithTag("virtual_booking_problem_full").assertIsDisplayed()
        composeRule.onNodeWithTag("virtual_booking_confirm").assertDoesNotExist()
        composeRule.onNodeWithTag("virtual_booking_dismiss").assertTextContains(text(R.string.virtual_sessions_choose_other)).performClick()
        assertEquals(listOf("dismiss"), events)
    }

    @Test
    fun sinPlanExplicaElPlanRequerido() {
        show(state(selected = service, booking = BookingDraft(service, slot, "k", problem = BookingProblem.CAPABILITY_REQUIRED)))
        composeRule.onNodeWithTag("virtual_booking_problem_capability_required")
            .assertTextContains(text(R.string.virtual_sessions_capability_required))
    }

    @Test
    fun citaDuplicadaTieneMensajePropio() {
        show(state(selected = service, booking = BookingDraft(service, slot, "k", problem = BookingProblem.ALREADY_BOOKED)))
        composeRule.onNodeWithTag("virtual_booking_problem_already_booked").assertTextContains(text(R.string.virtual_sessions_booking_exists))
    }

    @Test
    fun unResultadoDesconocidoSeReintentaAunqueNoHayaRed() {
        show(state(selected = service, booking = BookingDraft(service, slot, "k", problem = BookingProblem.UNKNOWN_OUTCOME), offline = true))
        composeRule.onNodeWithTag("virtual_booking_problem_unknown_outcome").assertIsDisplayed()
        composeRule.onNodeWithTag("virtual_booking_confirm").assertIsEnabled().assertTextContains(text(R.string.action_retry)).performClick()
        assertEquals(listOf("confirm"), events)
    }

    @Test
    fun misSesionesPermiteCancelarATiempoYExplicaLaVentanaCerrada() {
        val onTime = appointment("a", now.plus(Duration.ofDays(3)))
        val tooLate = appointment("b", now.plus(Duration.ofHours(2)))
        val cancelled = appointment("c", now.plus(Duration.ofDays(5)), AppointmentStatus.CANCELLED_BY_ATHLETE)
        show(state(tab = VirtualSessionsTab.MY_SESSIONS, appointments = AppointmentsSnapshot(listOf(tooLate, onTime, cancelled), now)))

        composeRule.onNodeWithTag("virtual_appointment_meeting_a").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("virtual_appointment_window_closed_b").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("virtual_appointment_cancel_b").assertDoesNotExist()
        composeRule.onNodeWithTag("virtual_appointment_status_c").performScrollTo()
            .assertTextContains(text(R.string.virtual_sessions_status_cancelled))
        composeRule.onNodeWithTag("virtual_appointment_cancel_a").performScrollTo().performClick()
        assertEquals(listOf("cancel:a"), events)
    }

    @Test
    fun misSesionesVaciaInvitaAReservar() {
        show(state(tab = VirtualSessionsTab.MY_SESSIONS))
        composeRule.onNodeWithTag("virtual_sessions_empty_mine").assertIsDisplayed()
    }

    @Test
    fun laCancelacionConfirmaYMuestraLaVentanaCerradaDelBackend() {
        val onTime = appointment("a", now.plus(Duration.ofDays(3)))
        show(
            state(
                tab = VirtualSessionsTab.MY_SESSIONS,
                appointments = AppointmentsSnapshot(listOf(onTime), now),
                cancelling = CancelDraft(onTime, problem = CancelProblem.WINDOW_CLOSED),
            )
        )
        composeRule.onNodeWithTag("virtual_cancel_problem_window_closed").assertIsDisplayed()
        composeRule.onNodeWithTag("virtual_cancel_confirm").assertDoesNotExist()
        composeRule.onNodeWithTag("virtual_cancel_dismiss").performClick()
        assertEquals(listOf("keep"), events)
    }

    @Test
    fun lasPestanasCambianDeVista() {
        show(state())
        composeRule.onNodeWithTag("virtual_sessions_tab_mine").performClick()
        composeRule.onNodeWithTag("virtual_sessions_back").performClick()
        assertEquals(listOf("tab:MY_SESSIONS", "back"), events)
    }
}
