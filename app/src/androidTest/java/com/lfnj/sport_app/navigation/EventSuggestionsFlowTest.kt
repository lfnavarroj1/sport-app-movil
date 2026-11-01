package com.lfnj.sport_app.navigation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lfnj.sport_app.core.config.AppConfig
import com.lfnj.sport_app.testing.InMemoryTokenStore
import com.lfnj.sport_app.testing.TestAppContainer
import com.lfnj.sport_app.ui.theme.SportappTheme
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * HU027 en la aplicación (menú, guarda, ViewModel, Room y cifrado reales) contra un `bff-mobile`
 * simulado con MockWebServer según la copia 0.15.0, hasta abrir el detalle de HU041. Prueba de
 * componente con contrato simulado, **no** un E2E.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class EventSuggestionsFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val server = MockWebServer()
    private lateinit var container: TestAppContainer
    private lateinit var navController: NavHostController

    /** Inicio futuro: el detalle de HU041 no lo considera cerrado. */
    private val startsAt = Instant.now().plus(20, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
    private val endsAt = startsAt.plus(3, ChronoUnit.HOURS)

    @Before
    fun setUp() {
        // Sin respuesta preparada, el servidor corta la conexión: así se simula la falta de red.
        server.dispatcher = QueueDispatcher().apply { setFailFast(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) }
        server.start()
        val config = AppConfig(server.url("/").toString(), "test", "0.15.0", "0.1.0")
        container = TestAppContainer(config, InMemoryTokenStore(), ApplicationProvider.getApplicationContext())
        composeRule.setContent {
            navController = rememberNavController()
            SportappTheme { AppNavHost(container, navController = navController) }
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
        container.close()
    }

    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setBody(body)

    private fun takeRequest(): RecordedRequest = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "No llegó la solicitud esperada" }

    private fun login(capabilities: String = CAPABILITIES) {
        server.enqueue(json(SESSION))
        server.enqueue(json(capabilities))
        composeRule.waitUntilExactlyOneExists(hasTestTag("login_email"), 5_000)
        composeRule.onNodeWithTag("login_email").performTextInput("ana@example.com")
        composeRule.onNodeWithTag("login_password").performTextInput("Secreta-123")
        composeRule.onNodeWithTag("login_submit").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), 5_000)
        composeRule.waitUntilExactlyOneExists(hasText("Plan FREE activo."), 5_000)
        takeRequest()
        takeRequest()
    }

    /** [response]: respuesta de `GET /v1/me/suggestions/events`; sin ella, falta de red. */
    private fun openSuggestionsFromMenu(response: MockResponse? = null) {
        response?.let(server::enqueue)
        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("athlete_menu").performScrollToNode(hasTestTag("menu_events.suggest"))
        composeRule.onNodeWithTag("menu_events.suggest").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("event_suggestions_screen"), 5_000)
    }

    private fun item(eventId: String, title: String, reasons: String, myRegistration: String = "null") =
        """{"reference":{"type":"event","id":"$eventId"},"title":"$title","summary":"Recorrido urbano.","reasons":$reasons,""" +
            """"event_id":"$eventId","discipline":"Atletismo","location":{"name":"Parque Simón Bolívar","city":"Bogotá","country_code":"CO"},""" +
            """"starts_at":"$startsAt","ends_at":"$endsAt","time_zone":"America/Bogota","capacity":500,""" +
            """"fee":{"amount":"45000.00","currency":"COP"},"my_registration":$myRegistration}"""

    private fun suggestions(personalized: Boolean = true, missing: String = "[]", reasons: String = """["SPORT_MATCH","CITY_MATCH"]""") =
        """{"items":[${item(EVENT_ID, "Media maratón de Bogotá", reasons)},""" +
            """${item(REGISTERED_EVENT_ID, "Carrera 10K Usaquén", reasons, REGISTRATION)}],""" +
            """"personalized":$personalized,"missing_context":$missing,"generated_at":"2026-10-25T13:00:00Z",""" +
            """"sources":[{"source":"users","status":"OK"}]}"""

    private fun detailBody() =
        """{"event_id":"$EVENT_ID","name":"Media maratón de Bogotá","description":"Recorrido urbano.",""" +
            """"discipline":"Atletismo","location":{"name":"Parque Simón Bolívar","city":"Bogotá","country_code":"CO"},""" +
            """"starts_at":"$startsAt","ends_at":"$endsAt","time_zone":"America/Bogota","capacity":500,""" +
            """"status":"PUBLISHED","version":1,"fee":{"amount":"45000.00","currency":"COP"},"my_registration":null}"""

    @Test
    fun muestraLasSugerenciasAbreElDetalleDeHu041YLasConservaSinRed() {
        login()
        openSuggestionsFromMenu(json(suggestions()))

        val request = takeRequest()
        assertEquals("/v1/me/suggestions/events", request.requestUrl?.encodedPath)
        assertEquals("20", request.requestUrl?.queryParameter("limit"))
        assertEquals("Bearer access-sintetico", request.getHeader("Authorization"))

        composeRule.waitUntilExactlyOneExists(hasTestTag("event_suggestion_$EVENT_ID"), 5_000)
        composeRule.onNodeWithTag("event_suggestions_personalized").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("event_suggestion_$REGISTERED_EVENT_ID").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("event_suggestion_registered_$REGISTERED_EVENT_ID", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("event_suggestion_registered_$EVENT_ID", useUnmergedTree = true).assertDoesNotExist()

        // Tocar la sugerencia abre el detalle de HU041 con el mismo event_id.
        server.enqueue(json(detailBody()))
        composeRule.onNode(hasTestTag("event_suggestion_$EVENT_ID") and hasClickAction()).performScrollTo().performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("event_detail_screen"), 5_000)
        assertEquals("/v1/events/$EVENT_ID", takeRequest().requestUrl?.encodedPath)
        composeRule.waitUntilExactlyOneExists(hasTestTag("event_name"), 5_000)
        composeRule.runOnIdle { assertEquals(Destinations.EVENT_DETAIL, navController.currentDestination?.route) }

        // Sin red: la misma lista sigue visible desde la caché cifrada, con su antigüedad.
        composeRule.runOnIdle { navController.popBackStack() }
        composeRule.waitUntilExactlyOneExists(hasTestTag("event_suggestions_screen"), 5_000)
        composeRule.onNodeWithTag("event_suggestions_back").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), 5_000)
        openSuggestionsFromMenu()
        composeRule.waitUntilExactlyOneExists(hasTestTag("event_suggestions_problem"), 5_000)
        composeRule.onNodeWithTag("event_suggestions_synced_at").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("event_suggestion_$EVENT_ID").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun laListaNoPersonalizadaNoSePresentaComoParaTi() {
        login()
        openSuggestionsFromMenu(json(suggestions(personalized = false, missing = """["sports","city"]""", reasons = """["UPCOMING"]""")))

        composeRule.waitUntilExactlyOneExists(hasTestTag("event_suggestions_not_personalized"), 5_000)
        composeRule.onNodeWithTag("event_suggestions_personalized").assertDoesNotExist()
        composeRule.onNodeWithTag("event_suggestions_missing_sports", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("event_suggestions_missing_city", useUnmergedTree = true).assertExists()
    }

    @Test
    fun sinRedYSinCacheInformaQueAunNoSeSincronizo() {
        login()
        openSuggestionsFromMenu()

        composeRule.waitUntilExactlyOneExists(hasTestTag("event_suggestions_never_synced"), 5_000)
        composeRule.onNodeWithTag("event_suggestions_empty").assertDoesNotExist()
    }

    @Test
    fun sinLaCapacidadLaOpcionNoApareceYLaRutaDirectaSeRechaza() {
        login(CAPABILITIES.replace(""","events.suggest"""", ""))

        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("menu_events.suggest").assertDoesNotExist()

        composeRule.runOnIdle { navController.navigate(Destinations.EVENT_SUGGESTIONS) }
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), 5_000)
        composeRule.onNodeWithTag("event_suggestions_screen").assertDoesNotExist()
        assertEquals("solo inicio de sesión y capacidades", 2, server.requestCount)
    }

    @Test
    fun conEventosCaidoSinCacheInformaQueNoEstaDisponible() {
        login()
        openSuggestionsFromMenu(json("""{"code":"upstream_unavailable","message":"m","correlation_id":"c-1"}""", status = 503))

        composeRule.waitUntilExactlyOneExists(hasTestTag("event_suggestions_error"), 5_000)
        composeRule.onNodeWithTag("event_suggestions_retry").assertIsDisplayed()
    }

    @Test
    fun unaRespuesta403CapabilityRequiredSeInforma() {
        login()
        openSuggestionsFromMenu(json("""{"code":"capability_required","message":"m","correlation_id":"c-1"}""", status = 403))

        composeRule.waitUntilExactlyOneExists(hasText("Tu plan actual no incluye eventos sugeridos."), 5_000)
    }

    private companion object {
        const val USER_ID = "7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b"
        const val EVENT_ID = "3f2a1b0c-9d8e-4f7a-8b6c-5d4e3f2a1b0c"
        const val REGISTERED_EVENT_ID = "0c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f"
        const val REGISTRATION =
            """{"registration_id":"5e6f7a8b-9c0d-4e1f-a2b3-c4d5e6f7a8b9","status":"REGISTERED","registered_at":"2026-10-21T10:00:00Z"}"""
        const val SESSION =
            """{"token_type":"Bearer","access_token":"access-sintetico","expires_in":900,""" +
                """"refresh_token":"refresh-token-sintetico-000000000001","refresh_expires_in":604800,""" +
                """"user":{"user_id":"$USER_ID","full_name":"Ana Deportista",""" +
                """"email":"ana@example.com","actor_type":"athlete"}}"""
        const val CAPABILITIES =
            """{"actor_type":"athlete","subscription_status":"ACTIVE","plan_code":"FREE","plan_version":1,""" +
                """"catalog_version":"plans-2026-10-11.v1","authorization_policy_version":"actor-permissions.v1",""" +
                """"permissions":[],"capabilities":["training.session.record","events.suggest"],""" +
                """"evaluated_at":"2026-10-12T12:00:00Z"}"""
    }
}
