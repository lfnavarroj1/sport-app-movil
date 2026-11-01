package com.lfnj.sport_app.features.calendar

import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lfnj.sport_app.features.calendar.model.CalendarDays
import com.lfnj.sport_app.features.calendar.model.CalendarItem
import com.lfnj.sport_app.features.calendar.model.CalendarSnapshot
import com.lfnj.sport_app.features.calendar.model.CalendarSourceState
import com.lfnj.sport_app.features.calendar.model.EventLocation
import com.lfnj.sport_app.features.calendar.model.SourceStatus
import com.lfnj.sport_app.ui.theme.SportappTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

/** HU035: estados de la pantalla `/calendario`, fuentes diferenciadas, zona del dispositivo y accesibilidad. */
@RunWith(AndroidJUnit4::class)
class CalendarScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val bogota = ZoneId.of("America/Bogota")
    private val november = YearMonth.of(2026, 11)
    private val today = LocalDate.of(2026, 11, 10)
    private val events = mutableListOf<String>()
    private val locale: Locale = Locale.getDefault()

    private val event = CalendarItem.Event(
        "EVENT_PUBLISHED:1", LocalDate.of(2026, 11, 15), "Carrera 10K Bogotá", "Running",
        Instant.parse("2026-11-15T12:00:00Z"), Instant.parse("2026-11-15T15:00:00Z"), "America/Bogota",
        EventLocation("Parque Metropolitano Simón Bolívar", "Bogotá", "CO"),
    )
    private val session = CalendarItem.PlannedSession("TRAINING_PLAN_SESSION:1", LocalDate.of(2026, 11, 16), "Base aeróbica noviembre", "running", 45, 4)

    private fun snapshot(
        items: List<CalendarItem> = listOf(event, session),
        training: SourceStatus = SourceStatus.OK,
        eventsTruncated: Boolean = false,
    ) = CalendarSnapshot(
        november, Instant.EPOCH, Instant.EPOCH, items,
        listOf(CalendarSourceState("events", SourceStatus.OK, eventsTruncated), CalendarSourceState("training", training, false)),
        Instant.EPOCH, Instant.parse("2026-11-09T13:30:00Z"),
    )

    private fun state(
        snapshot: CalendarSnapshot? = snapshot(),
        zone: ZoneId = bogota,
        month: YearMonth = november,
        mode: CalendarMode = CalendarMode.MONTH,
        selectedDay: LocalDate? = null,
        problem: CalendarProblem? = null,
        stale: Boolean = false,
        syncedMonth: YearMonth? = null,
        refreshing: Boolean = false,
    ) = CalendarUiState(
        month = month, zone = zone, today = today, mode = mode, selectedDay = selectedDay, refreshing = refreshing,
        snapshot = snapshot, days = snapshot?.let { CalendarDays.group(it.items, month, zone) }.orEmpty(),
        stale = stale, problem = problem, syncedMonth = syncedMonth,
    )

    private fun show(state: CalendarUiState) = composeRule.setContent {
        SportappTheme {
            CalendarScreen(
                state = state,
                onPreviousMonth = { events += "previous" },
                onNextMonth = { events += "next" },
                onCurrentMonth = { events += "current" },
                onModeChange = { events += "mode:${it.name}" },
                onSelectDay = { events += "day:$it" },
                onRetry = { events += "retry" },
                onShowSyncedPeriod = { events += "synced" },
                onBack = { events += "back" },
            )
        }
    }

    @Test
    fun cargaMuestraProgreso() {
        show(state(snapshot = null, refreshing = true))

        composeRule.onNodeWithTag("calendar_loading").assertIsDisplayed()
    }

    @Test
    fun laVistaDeMesMarcaLosDiasConActividadesYMuestraElDiaElegido() {
        show(state(selectedDay = LocalDate.of(2026, 11, 15)))

        composeRule.onNodeWithTag("calendar_month_title").assertIsDisplayed()
        composeRule.onNodeWithTag("calendar_day_2026-11-15")
            .assertContentDescriptionContains("1 evento", substring = true)
            .assertIsSelected()
        composeRule.onNodeWithTag("calendar_day_2026-11-16").assertContentDescriptionContains("1 sesión planificada", substring = true)
        composeRule.onNodeWithTag("calendar_day_2026-11-17").assertContentDescriptionContains("sin actividades", substring = true)
        composeRule.onNodeWithTag("calendar_item_EVENT_PUBLISHED:1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Carrera 10K Bogotá").assertIsDisplayed()
        composeRule.onNodeWithText("Evento").assertIsDisplayed()
        composeRule.onNodeWithText("Zona horaria del evento: America/Bogota").assertIsDisplayed()

        composeRule.onNodeWithTag("calendar_day_2026-11-16").performClick()
        assertEquals(listOf("day:2026-11-16"), events)
    }

    @Test
    fun laListaDistingueEventosYSesionesPlanificadas() {
        show(state(mode = CalendarMode.LIST))

        composeRule.onNodeWithTag("calendar_mode_list").assertIsSelected()
        composeRule.onNodeWithTag("calendar_item_EVENT_PUBLISHED:1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("calendar_item_TRAINING_PLAN_SESSION:1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Sesión planificada").assertIsDisplayed()
        composeRule.onNodeWithText("Sin hora definida").assertIsDisplayed()
        composeRule.onNodeWithText("45 min").assertIsDisplayed()
        composeRule.onNodeWithText("Esfuerzo percibido (RPE): 4").assertIsDisplayed()
        composeRule.onNodeWithText(CalendarFormat.day(LocalDate.of(2026, 11, 16), locale)).assertIsDisplayed()
    }

    @Test
    fun lasHorasSePresentanEnLaZonaDelDispositivo() {
        val madrid = ZoneId.of("Europe/Madrid")
        show(state(zone = madrid, mode = CalendarMode.LIST))

        val (start, end) = CalendarFormat.eventRange(event, madrid, locale)
        composeRule.onNodeWithText("$start – $end").performScrollTo().assertIsDisplayed()
        // 12:00 UTC es 13:00 en Madrid; el instante y la zona declarada no cambian.
        assertEquals("13:00", Instant.parse("2026-11-15T12:00:00Z").atZone(madrid).toLocalTime().toString())
        composeRule.onNodeWithText("Zona horaria del evento: America/Bogota").assertIsDisplayed()
    }

    @Test
    fun sinElementosConFuentesConfirmadasMuestraElEstadoVacio() {
        show(state(snapshot = snapshot(items = emptyList())))

        composeRule.onNodeWithTag("calendar_empty").assertIsDisplayed()
        composeRule.onNodeWithText("No tienes eventos ni sesiones planificadas este mes.").assertIsDisplayed()
    }

    @Test
    fun unaFuenteIncompletaSeInformaYNoSeAfirmaQueNoHayActividades() {
        show(state(snapshot = snapshot(items = emptyList(), training = SourceStatus.UNAVAILABLE)))

        composeRule.onNodeWithTag("calendar_incomplete_training").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Información incompleta: no pudimos consultar las sesiones planificadas. Puede haber actividades que no se muestran."
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("calendar_empty").assertDoesNotExist()
    }

    @Test
    fun unResultadoTruncadoSugiereAcotarElPeriodo() {
        show(state(snapshot = snapshot(eventsTruncated = true)))

        composeRule.onNodeWithText("Hay más eventos de los que se muestran en este periodo. Acota el periodo para verlos todos.")
            .assertIsDisplayed()
    }

    @Test
    fun sinRedMuestraElPeriodoSincronizadoConSuAntiguedad() {
        show(state(problem = CalendarProblem.NO_CONNECTION, stale = true))

        composeRule.onNodeWithText("Sin conexión. Mostramos el calendario sincronizado por última vez.").assertIsDisplayed()
        composeRule.onNodeWithTag("calendar_stale").assertIsDisplayed()
        composeRule.onNodeWithText("Sincronizado: ${CalendarFormat.dateTime(Instant.parse("2026-11-09T13:30:00Z"), bogota, locale)}").assertIsDisplayed()
        composeRule.onNodeWithTag("calendar_day_2026-11-15").assertIsDisplayed()
    }

    @Test
    fun sinRedYSinCacheInformaQueAunNoSeSincronizo() {
        show(state(snapshot = null, problem = CalendarProblem.NO_CONNECTION))

        composeRule.onNodeWithText("Aún no has sincronizado tu calendario. Conéctate a internet para consultarlo.").assertIsDisplayed()
        composeRule.onNodeWithTag("calendar_empty").assertDoesNotExist()
        composeRule.onNodeWithTag("calendar_retry").performClick()
        assertEquals(listOf("retry"), events)
    }

    @Test
    fun sinRedEnOtroMesOfreceElPeriodoSincronizado() {
        show(state(snapshot = null, month = november.plusMonths(1), problem = CalendarProblem.NO_CONNECTION, syncedMonth = november))

        composeRule.onNodeWithTag("calendar_offline_other_period").assertIsDisplayed()
        composeRule.onNode(hasText(CalendarFormat.month(november, locale), substring = true)).assertIsDisplayed()
        composeRule.onNodeWithTag("calendar_show_synced").performClick()
        assertEquals(listOf("synced"), events)
    }

    @Test
    fun losErroresSonAccionablesYSinDetallesTecnicos() {
        show(state(snapshot = null, problem = CalendarProblem.CAPABILITY_REQUIRED))

        composeRule.onNodeWithText("Tu plan actual no incluye el calendario deportivo.").assertIsDisplayed()
        composeRule.onNodeWithTag("calendar_retry").assertIsDisplayed()
    }

    @Test
    fun laNavegacionEntreMesesYModosEsAccesible() {
        show(state(month = november.plusMonths(2)))

        composeRule.onNodeWithContentDescription("Mes anterior").performClick()
        composeRule.onNodeWithContentDescription("Mes siguiente").performClick()
        composeRule.onNodeWithTag("calendar_current").performClick()
        composeRule.onNodeWithTag("calendar_mode_list").performClick()
        composeRule.onNodeWithContentDescription("Volver").performClick()

        assertEquals(listOf("previous", "next", "current", "mode:LIST", "back"), events)
    }

    @Test
    fun enElMesActualNoSeOfreceIrAlMesActual() {
        show(state())

        composeRule.onNodeWithTag("calendar_current").assertDoesNotExist()
    }

    // ------------------------------------------------------------------ HU041: punto de entrada

    private fun showWithEntry(state: CalendarUiState) = composeRule.setContent {
        SportappTheme {
            CalendarScreen(
                state = state,
                onPreviousMonth = {}, onNextMonth = {}, onCurrentMonth = {}, onModeChange = {}, onSelectDay = {},
                onRetry = {}, onShowSyncedPeriod = {}, onBack = {},
                onOpenEvent = { events += "event:$it" },
                onOpenRegistrations = { events += "registrations" },
            )
        }
    }

    @Test
    fun unEventoConEventIdAbreSuDetalleYLaSesionNo() {
        val withId = event.copy(eventId = EVENT_ID)
        showWithEntry(state(snapshot = snapshot(items = listOf(withId, session)), mode = CalendarMode.LIST))

        composeRule.onNodeWithTag("calendar_item_EVENT_PUBLISHED:1").performScrollTo().assertHasClickAction().performClick()
        composeRule.onNodeWithTag("calendar_item_TRAINING_PLAN_SESSION:1").performScrollTo().assertHasNoClickAction()
        assertEquals(listOf("event:$EVENT_ID"), events)
    }

    @Test
    fun unEventoDeUnaCacheAnteriorSinEventIdNoEsNavegable() {
        showWithEntry(state(mode = CalendarMode.LIST))

        composeRule.onNodeWithTag("calendar_item_EVENT_PUBLISHED:1").performScrollTo().assertHasNoClickAction()
    }

    @Test
    fun laBarraAbreMisInscripciones() {
        showWithEntry(state())

        composeRule.onNodeWithTag("calendar_open_registrations").assertIsDisplayed().performClick()
        assertEquals(listOf("registrations"), events)
    }

    @Test
    fun sinElPuntoDeEntradaNoSeMuestraLaAccion() {
        show(state())

        composeRule.onNodeWithTag("calendar_open_registrations").assertDoesNotExist()
    }

    private companion object {
        const val EVENT_ID = "683f4e38-6bb3-4c47-a6dc-9bb1588c67ef"
    }
}
