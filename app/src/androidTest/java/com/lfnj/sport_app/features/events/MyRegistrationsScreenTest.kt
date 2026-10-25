package com.lfnj.sport_app.features.events

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lfnj.sport_app.features.events.model.EventPlace
import com.lfnj.sport_app.features.events.model.EventPrice
import com.lfnj.sport_app.features.events.model.EventRegistration
import com.lfnj.sport_app.features.events.model.RegisteredEvent
import com.lfnj.sport_app.features.events.model.RegistrationsSnapshot
import com.lfnj.sport_app.ui.theme.SportappTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** HU041: «Mis inscripciones» con lista, vacío, sin red con caché, `STALE`, errores y páginas. */
@RunWith(AndroidJUnit4::class)
class MyRegistrationsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val bogota = ZoneId.of("America/Bogota")
    private val events = mutableListOf<String>()
    private val locale: Locale = Locale.getDefault()
    private val syncedAt = Instant.parse("2026-11-09T13:30:00Z")

    private fun registration(id: String, name: String, startsAt: Instant, fee: EventPrice? = null) = EventRegistration(
        id, "683f4e38-6bb3-4c47-a6dc-9bb1588c67ef", Instant.parse("2026-10-12T22:30:00Z"), fee, 1,
        RegisteredEvent(name, startsAt, startsAt.plusSeconds(3 * 3600), "America/Bogota", EventPlace("Parque", "Bogotá", "CO")),
    )

    private val first = registration("6d2f0c8e-4a1b-4f3e-9c7d-2b8a5e1f0d43", "Carrera 10K Bogotá", Instant.parse("2026-11-15T12:00:00Z"), EventPrice("85000.00", "COP"))
    private val second = registration("9e8d7c6b-5a49-4382-9170-6f5e4d3c2b1a", "Media maratón", Instant.parse("2026-12-01T12:00:00Z"))

    private fun state(
        snapshot: RegistrationsSnapshot? = RegistrationsSnapshot(listOf(second, first), null, syncedAt),
        problem: RegistrationsProblem? = null,
        stale: Boolean = false,
        refreshing: Boolean = false,
        loadingMore: Boolean = false,
    ) = MyRegistrationsUiState(
        zone = bogota, refreshing = refreshing, loadingMore = loadingMore, snapshot = snapshot,
        items = snapshot?.ordered.orEmpty(), stale = stale, problem = problem,
    )

    private fun show(state: MyRegistrationsUiState) = composeRule.setContent {
        SportappTheme {
            MyRegistrationsScreen(
                state = state,
                onRetry = { events += "retry" },
                onLoadMore = { events += "more" },
                onOpenEvent = { events += "event:$it" },
                onBack = { events += "back" },
            )
        }
    }

    @Test
    fun cargaMuestraProgreso() {
        show(state(snapshot = null, refreshing = true))

        composeRule.onNodeWithTag("registrations_loading").assertIsDisplayed()
    }

    @Test
    fun laListaMuestraLasInscripcionesConfirmadasOrdenadasYSuSincronizacion() {
        show(state())

        composeRule.onNodeWithTag("registrations_synced_at").assertIsDisplayed()
        composeRule.onNodeWithText("Sincronizado: ${EventFormat.dateTime(syncedAt, bogota, locale)}").assertIsDisplayed()
        val firstTop = composeRule.onNodeWithTag("registration_${first.registrationId}").performScrollTo().fetchSemanticsNode().boundsInRoot.top
        val secondTop = composeRule.onNodeWithTag("registration_${second.registrationId}").performScrollTo().fetchSemanticsNode().boundsInRoot.top
        assertTrue("ordenada por fecha del evento", firstTop < secondTop)
        composeRule.onNodeWithText("Tarifa informativa: 85000.00 COP", useUnmergedTree = true).assertExists()
    }

    @Test
    fun tocarUnaInscripcionAbreElEvento() {
        show(state())

        composeRule.onNodeWithTag("registration_${first.registrationId}").performScrollTo().assertHasClickAction().performClick()
        assertEquals(listOf("event:${first.eventId}"), events)
    }

    @Test
    fun sinInscripcionesMuestraElEstadoVacio() {
        show(state(snapshot = RegistrationsSnapshot(emptyList(), null, syncedAt)))

        composeRule.onNodeWithTag("registrations_empty").assertIsDisplayed()
    }

    @Test
    fun sinRedMuestraLaCacheConSuAntiguedadYAvisoStale() {
        show(state(problem = RegistrationsProblem.NO_CONNECTION, stale = true))

        composeRule.onNodeWithTag("registrations_problem").assertIsDisplayed()
        composeRule.onNodeWithTag("registrations_stale").assertIsDisplayed()
        composeRule.onNodeWithTag("registration_${first.registrationId}").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun sinRedYSinCacheIndicaQueNuncaSeSincronizo() {
        show(state(snapshot = null, problem = RegistrationsProblem.NO_CONNECTION))

        composeRule.onNodeWithTag("registrations_never_synced").assertIsDisplayed()
        composeRule.onNodeWithTag("registrations_retry").performClick()
        assertEquals(listOf("retry"), events)
    }

    @Test
    fun unErrorConCacheOfreceReintentar() {
        show(state(problem = RegistrationsProblem.UNAVAILABLE))

        composeRule.onNodeWithTag("registrations_problem").assertIsDisplayed()
        composeRule.onNodeWithTag("registrations_retry").performClick()
        assertEquals(listOf("retry"), events)
    }

    @Test
    fun sinPermisoNoOfreceReintentar() {
        show(state(snapshot = null, problem = RegistrationsProblem.PERMISSION_REQUIRED))

        composeRule.onNodeWithTag("registrations_error").assertIsDisplayed()
        composeRule.onNodeWithTag("registrations_retry").assertDoesNotExist()
    }

    @Test
    fun cargarMasSoloConCursorYSinDobleEnvio() {
        show(state(snapshot = RegistrationsSnapshot(listOf(first), "c2", syncedAt)))

        composeRule.onNodeWithTag("registrations_load_more").performScrollTo().performClick()
        assertEquals(listOf("more"), events)
    }

    @Test
    fun mientrasCargaMasElBotonQuedaDeshabilitado() {
        show(state(snapshot = RegistrationsSnapshot(listOf(first), "c2", syncedAt), loadingMore = true))

        composeRule.onNodeWithTag("registrations_load_more").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun sinCursorNoSeOfreceCargarMas() {
        show(state())

        composeRule.onNodeWithTag("registrations_load_more").assertDoesNotExist()
    }
}
