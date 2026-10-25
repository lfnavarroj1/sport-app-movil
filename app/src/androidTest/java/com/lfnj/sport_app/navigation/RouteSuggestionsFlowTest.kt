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
 * HU026 en la aplicación (menú, guarda, ViewModel, Room y cifrado reales) contra un `bff-mobile`
 * simulado con MockWebServer según la copia 0.15.0. Prueba de componente con contrato simulado,
 * **no** un E2E.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class RouteSuggestionsFlowTest {
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

    /** [response]: respuesta de `GET /v1/me/suggestions/routes`; sin ella, falta de red. */
    private fun openRoutesFromMenu(response: MockResponse? = null) {
        response?.let(server::enqueue)
        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("athlete_menu").performScrollToNode(hasTestTag("menu_training.routes.suggest"))
        composeRule.onNodeWithTag("menu_training.routes.suggest").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("route_suggestions_screen"), 5_000)
    }

    @Test
    fun muestraLasRutasAbreElDetalleYLasConservaSinRed() {
        login()
        openRoutesFromMenu(json(SUGGESTIONS))

        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/v1/me/suggestions/routes", request.requestUrl?.encodedPath)
        assertEquals("20", request.requestUrl?.queryParameter("limit"))
        assertEquals("Bearer access-sintetico", request.getHeader("Authorization"))

        composeRule.waitUntilExactlyOneExists(hasTestTag("route_suggestion_$ROUTE_ID"), 5_000)
        composeRule.onNodeWithTag("route_suggestions_personalized").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("route_suggestions_safety").performScrollTo().assertIsDisplayed()
        composeRule.onNode(hasTestTag("route_suggestion_$ROUTE_ID") and hasClickAction()).performScrollTo().performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("route_suggestion_detail"), 5_000)
        composeRule.onNodeWithTag("route_suggestion_start_point").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("route_suggestion_open_map").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("route_suggestion_waypoint_0").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("route_suggestions_back").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("route_suggestion_$ROUTE_ID"), 5_000)

        // Sin red: la misma lista sigue visible desde la caché cifrada, con su antigüedad.
        composeRule.onNodeWithTag("route_suggestions_back").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), 5_000)
        openRoutesFromMenu()
        composeRule.waitUntilExactlyOneExists(hasTestTag("route_suggestions_problem"), 5_000)
        composeRule.onNodeWithTag("route_suggestions_synced_at").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("route_suggestion_$ROUTE_ID").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun laListaNoPersonalizadaNoSePresentaComoParaTi() {
        login()
        val body = SUGGESTIONS
            .replace("\"personalized\":true", "\"personalized\":false")
            .replace("\"missing_context\":[]", "\"missing_context\":[\"sports\",\"city\"]")
            .replace("\"reasons\":[\"SPORT_MATCH\",\"CITY_MATCH\"]", "\"reasons\":[]")
        openRoutesFromMenu(json(body))

        composeRule.waitUntilExactlyOneExists(hasTestTag("route_suggestions_not_personalized"), 5_000)
        composeRule.onNodeWithTag("route_suggestions_personalized").assertDoesNotExist()
        composeRule.onNodeWithTag("route_suggestions_missing_sports", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("route_suggestions_missing_city", useUnmergedTree = true).assertExists()
    }

    @Test
    fun sinRedYSinCacheInformaQueAunNoSeSincronizo() {
        login()
        openRoutesFromMenu()

        composeRule.waitUntilExactlyOneExists(hasTestTag("route_suggestions_never_synced"), 5_000)
        composeRule.onNodeWithTag("route_suggestions_empty").assertDoesNotExist()
    }

    @Test
    fun sinLaCapacidadLaOpcionNoApareceYLaRutaDirectaSeRechaza() {
        login(CAPABILITIES.replace(""","training.routes.suggest"""", ""))

        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("menu_training.routes.suggest").assertDoesNotExist()

        composeRule.runOnIdle { navController.navigate(Destinations.ROUTE_SUGGESTIONS) }
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), 5_000)
        composeRule.onNodeWithTag("route_suggestions_screen").assertDoesNotExist()
        assertEquals("solo inicio de sesión y capacidades", 2, server.requestCount)
    }

    @Test
    fun unaRespuesta403CapabilityRequiredSeInforma() {
        login()
        openRoutesFromMenu(json("""{"code":"capability_required","message":"m","correlation_id":"c-1"}""", status = 403))

        composeRule.waitUntilExactlyOneExists(hasText("Tu plan actual no incluye rutas sugeridas."), 5_000)
    }

    private companion object {
        const val USER_ID = "7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b"
        const val ROUTE_ID = "6b1d2c3e-4f5a-4b6c-8d7e-9f0a1b2c3d4e"
        const val SESSION =
            """{"token_type":"Bearer","access_token":"access-sintetico","expires_in":900,""" +
                """"refresh_token":"refresh-token-sintetico-000000000001","refresh_expires_in":604800,""" +
                """"user":{"user_id":"$USER_ID","full_name":"Ana Deportista",""" +
                """"email":"ana@example.com","actor_type":"athlete"}}"""
        const val CAPABILITIES =
            """{"actor_type":"athlete","subscription_status":"ACTIVE","plan_code":"FREE","plan_version":1,""" +
                """"catalog_version":"plans-2026-10-11.v1","authorization_policy_version":"actor-permissions.v1",""" +
                """"permissions":[],"capabilities":["training.session.record","training.routes.suggest"],""" +
                """"evaluated_at":"2026-10-12T12:00:00Z"}"""
        const val SUGGESTIONS =
            """{"items":[{"reference":{"type":"route","id":"$ROUTE_ID"},"title":"Circuito Parque Simón Bolívar",""" +
                """"summary":"Recorrido plano alrededor del lago.","reasons":["SPORT_MATCH","CITY_MATCH"],""" +
                """"route_id":"$ROUTE_ID","sport":"running","distance_km":"5.0","difficulty":"LOW","city":"Bogotá",""" +
                """"country_code":"CO","start_point":{"lat":4.6097,"lng":-74.0817},""" +
                """"waypoints":[{"lat":4.6584,"lng":-74.0937}],"source":"ORGANIZER",""" +
                """"published_at":"2026-10-20T14:00:00Z","author_name":"Carlos Ruiz"}],""" +
                """"personalized":true,"missing_context":[],"generated_at":"2026-10-25T13:00:00Z",""" +
                """"sources":[{"source":"users","status":"OK"}]}"""
    }
}
