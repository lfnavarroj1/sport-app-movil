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
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * HU029 en la aplicación (menú, guarda, ViewModel, Room y cifrado reales) contra un `bff-mobile`
 * simulado con MockWebServer según la copia 0.18.0. Prueba de componente con contrato simulado,
 * **no** un E2E.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SupportSuggestionsFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val server = MockWebServer()
    private lateinit var container: TestAppContainer
    private lateinit var navController: NavHostController

    @Before
    fun setUp() {
        // Sin respuesta preparada, el servidor corta la conexión: así se simula la falta de red.
        server.dispatcher = QueueDispatcher().apply { setFailFast(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) }
        server.start()
        val config = AppConfig(server.url("/").toString(), "test", "0.18.0", "0.1.0")
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

    private fun login(capabilities: String = CAPABILITIES) {
        server.enqueue(json(SESSION))
        server.enqueue(json(capabilities))
        composeRule.waitUntilExactlyOneExists(hasTestTag("login_email"), 5_000)
        composeRule.onNodeWithTag("login_email").performTextInput("ana@example.com")
        composeRule.onNodeWithTag("login_password").performTextInput("Secreta-123")
        composeRule.onNodeWithTag("login_submit").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), 5_000)
        composeRule.waitUntilExactlyOneExists(hasText("Plan FREE activo."), 5_000)
        server.takeRequest(1, TimeUnit.SECONDS)
        server.takeRequest(1, TimeUnit.SECONDS)
    }

    /** [response]: respuesta de `GET /v1/me/suggestions/services`; sin ella, falta de red. */
    private fun openSuggestionsFromMenu(response: MockResponse? = null) {
        response?.let(server::enqueue)
        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("athlete_menu").performScrollToNode(hasTestTag("menu_services.support.suggest"))
        composeRule.onNodeWithTag("menu_services.support.suggest").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("support_suggestions_screen"), 5_000)
    }

    @Test
    fun muestraLosServiciosAbreElDetalleYLosConservaSinRed() {
        login()
        openSuggestionsFromMenu(json(SUGGESTIONS))

        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/v1/me/suggestions/services", request.requestUrl?.encodedPath)
        assertEquals("20", request.requestUrl?.queryParameter("limit"))
        assertEquals("Bearer access-sintetico", request.getHeader("Authorization"))

        composeRule.waitUntilExactlyOneExists(hasTestTag("support_suggestion_$SERVICE_ID"), 5_000)
        composeRule.onNodeWithTag("support_suggestions_personalized").assertIsDisplayed()
        composeRule.onNode(hasTestTag("support_suggestion_$SERVICE_ID") and hasClickAction()).performScrollTo().performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("support_suggestion_detail"), 5_000)
        composeRule.onNodeWithTag("support_suggestion_detail_interval").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("support_suggestion_hire_info").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("support_suggestions_back").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("support_suggestion_$SERVICE_ID"), 5_000)

        // Sin red: la misma lista sigue visible desde la caché cifrada, con su antigüedad.
        composeRule.onNodeWithTag("support_suggestions_back").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), 5_000)
        openSuggestionsFromMenu()
        composeRule.waitUntilExactlyOneExists(hasTestTag("support_suggestions_problem"), 5_000)
        composeRule.onNodeWithTag("support_suggestions_synced_at").assertIsDisplayed()
        composeRule.onNodeWithTag("support_suggestion_$SERVICE_ID").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun sinCoincidenciasMuestraLaListaVaciaExplicada() {
        login()
        openSuggestionsFromMenu(json(EMPTY_WITHOUT_REGISTRATIONS))

        composeRule.waitUntilExactlyOneExists(hasTestTag("support_suggestions_empty"), 5_000)
        composeRule.onNodeWithTag("support_suggestions_not_personalized").assertIsDisplayed()
        composeRule.onNodeWithTag("support_suggestions_missing_event_registrations", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun sinRedYSinCacheInformaQueAunNoSeSincronizo() {
        login()
        openSuggestionsFromMenu()

        composeRule.waitUntilExactlyOneExists(hasTestTag("support_suggestions_never_synced"), 5_000)
        composeRule.onNodeWithTag("support_suggestions_empty").assertDoesNotExist()
    }

    @Test
    fun conServiciosCaidoInformaElErrorYReintentarConsultaDeNuevo() {
        login()
        openSuggestionsFromMenu(json("""{"code":"upstream_unavailable","message":"m","correlation_id":"c-1"}""", status = 503))

        composeRule.waitUntilExactlyOneExists(hasTestTag("support_suggestions_error"), 5_000)
        server.takeRequest(1, TimeUnit.SECONDS)
        server.enqueue(json(SUGGESTIONS))
        composeRule.onNodeWithTag("support_suggestions_retry").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("support_suggestion_$SERVICE_ID"), 5_000)
    }

    @Test
    fun sinLaCapacidadLaOpcionNoApareceYLaRutaDirectaSeRechaza() {
        login(CAPABILITIES.replace(""","services.support.suggest"""", ""))

        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("menu_services.support.suggest").assertDoesNotExist()

        composeRule.runOnIdle { navController.navigate(Destinations.SUPPORT_SUGGESTIONS) }
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), 5_000)
        composeRule.onNodeWithTag("support_suggestions_screen").assertDoesNotExist()
        assertEquals("solo inicio de sesión y capacidades", 2, server.requestCount)
    }

    @Test
    fun unaRespuesta403CapabilityRequiredSeInforma() {
        login()
        openSuggestionsFromMenu(json("""{"code":"capability_required","message":"m","correlation_id":"c-1"}""", status = 403))

        composeRule.waitUntilExactlyOneExists(hasText("Tu plan actual no incluye las sugerencias de acompañamiento y logística."), 5_000)
    }

    private companion object {
        const val USER_ID = "7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b"
        const val SERVICE_ID = "4d3c2b1a-0f9e-4d8c-9b7a-6f5e4d3c2b1a"
        const val SESSION =
            """{"token_type":"Bearer","access_token":"access-sintetico","expires_in":900,""" +
                """"refresh_token":"refresh-token-sintetico-000000000001","refresh_expires_in":604800,""" +
                """"user":{"user_id":"$USER_ID","full_name":"Ana Deportista",""" +
                """"email":"ana@example.com","actor_type":"athlete"}}"""
        const val CAPABILITIES =
            """{"actor_type":"athlete","subscription_status":"ACTIVE","plan_code":"FREE","plan_version":1,""" +
                """"catalog_version":"plans-2026-10-11.v1","authorization_policy_version":"actor-permissions.v1",""" +
                """"permissions":[],"capabilities":["training.session.record","services.support.suggest"],""" +
                """"evaluated_at":"2026-10-12T12:00:00Z"}"""
        const val SUGGESTIONS =
            """{"items":[{"reference":{"type":"service","id":"$SERVICE_ID"},"title":"Transporte al Medio Maratón",""" +
                """"summary":"Recogida en el punto de encuentro y regreso después de la carrera.",""" +
                """"reasons":["EVENT_LOGISTICS"],"service_id":"$SERVICE_ID","category":"TRANSPORT","modality":"IN_PERSON",""" +
                """"fee":{"amount":"80000.00","currency":"COP","billing_unit":"SERVICE"},""" +
                """"next_interval":{"starts_at":"2026-11-08T10:00:00Z","ends_at":"2026-11-08T12:00:00Z","time_zone":"America/Bogota"},""" +
                """"author_name":"Carlos Rodríguez"}],""" +
                """"personalized":true,"missing_context":[],"generated_at":"2026-10-25T13:00:00Z",""" +
                """"sources":[{"source":"events","status":"OK"},{"source":"users","status":"OK"}]}"""
        const val EMPTY_WITHOUT_REGISTRATIONS =
            """{"items":[],"personalized":false,"missing_context":["event_registrations"],"generated_at":"2026-10-25T13:00:00Z",""" +
                """"sources":[{"source":"events","status":"OK"},{"source":"users","status":"OK"}]}"""
    }
}
