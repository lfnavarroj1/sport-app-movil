package com.lfnj.sport_app.navigation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
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
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * HU035 en la aplicación (navegación, guarda, ViewModel, Room y cifrado reales) contra un
 * `bff-mobile` simulado con MockWebServer según ADR-031. Prueba de componente con contrato
 * simulado, **no** un E2E.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class CalendarFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val server = MockWebServer()
    private lateinit var container: TestAppContainer
    private lateinit var navController: NavHostController
    private val month = YearMonth.now(ZoneId.systemDefault())

    @Before
    fun setUp() {
        // Sin respuesta preparada, el servidor corta la conexión: así se simula la falta de red.
        server.dispatcher = QueueDispatcher().apply { setFailFast(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) }
        server.start()
        val config = AppConfig(server.url("/").toString(), "test", "0.3.0", "0.1.0")
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

    /** Evento y sesión del mes en curso del dispositivo, con la forma del ejemplo de ADR-031. */
    private fun calendarBody(): String {
        val day15 = month.atDay(15)
        return """{"from":"${month.atDay(1)}T00:00:00Z","to":"${month.atEndOfMonth()}T23:59:59Z","items":[""" +
            """{"item_id":"EVENT_PUBLISHED:683f4e38-6bb3-4c47-a6dc-9bb1588c67ef","source":"EVENT_PUBLISHED","occurs_on":"$day15",""" +
            """"title":"Carrera 10K Bogotá","sport":"Running","starts_at":"${day15}T12:00:00Z","ends_at":"${day15}T15:00:00Z",""" +
            """"time_zone":"America/Bogota","location":{"name":"Parque Metropolitano Simón Bolívar","city":"Bogotá","country_code":"CO"},""" +
            """"reference":{"event_id":"683f4e38-6bb3-4c47-a6dc-9bb1588c67ef"}},""" +
            """{"item_id":"TRAINING_PLAN_SESSION:0d1c3e4a-8f1b-4a0e-9a6f-2b7c1d9e5f10","source":"TRAINING_PLAN_SESSION",""" +
            """"occurs_on":"${month.atDay(16)}","title":"Base aeróbica","sport":"running","starts_at":null,"ends_at":null,""" +
            """"time_zone":null,"duration_minutes":45,"rpe":4,"reference":{"plan_id":"f2a9c1d0-3b4e-4c5f-8d6e-7a8b9c0d1e2f",""" +
            """"session_id":"0d1c3e4a-8f1b-4a0e-9a6f-2b7c1d9e5f10"}}],""" +
            """"sources":[{"name":"events","status":"OK","truncated":false},{"name":"training","status":"OK","truncated":false}],""" +
            """"evaluated_at":"2026-10-12T22:00:00Z"}"""
    }

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

    /** [response]: respuesta de `GET /v1/me/calendar`; sin ella, falta de red. */
    private fun openCalendarFromMenu(response: MockResponse? = null) {
        response?.let(server::enqueue)
        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("athlete_menu").performScrollToNode(hasTestTag("menu_calendar.sport.view"))
        composeRule.onNodeWithTag("menu_calendar.sport.view").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("calendar_screen"), 5_000)
    }

    private fun showList() {
        composeRule.onNodeWithTag("calendar_mode_list").performClick()
    }

    @Test
    fun muestraEventosYSesionesDelMesYLosConservaSinRed() {
        login()
        openCalendarFromMenu(json(calendarBody()))

        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/v1/me/calendar", request.requestUrl?.encodedPath)
        assertNotNull(request.requestUrl?.queryParameter("from"))
        assertNotNull(request.requestUrl?.queryParameter("to"))
        assertEquals("Bearer access-sintetico", request.getHeader("Authorization"))

        showList()
        composeRule.waitUntilExactlyOneExists(hasTestTag("calendar_item_EVENT_PUBLISHED:683f4e38-6bb3-4c47-a6dc-9bb1588c67ef"), 5_000)
        composeRule.onNodeWithTag("calendar_item_TRAINING_PLAN_SESSION:0d1c3e4a-8f1b-4a0e-9a6f-2b7c1d9e5f10").performScrollTo().assertIsDisplayed()

        // Sin red: el mismo periodo sigue visible desde la caché cifrada, con su antigüedad.
        composeRule.onNodeWithTag("calendar_back").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), 5_000)
        openCalendarFromMenu()
        composeRule.waitUntilExactlyOneExists(hasTestTag("calendar_problem"), 5_000)
        composeRule.onNodeWithTag("calendar_synced_at").assertIsDisplayed()
        showList()
        composeRule.onNodeWithTag("calendar_item_EVENT_PUBLISHED:683f4e38-6bb3-4c47-a6dc-9bb1588c67ef").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun sinRedYSinCacheInformaQueAunNoSeSincronizo() {
        login()
        openCalendarFromMenu()

        composeRule.waitUntilExactlyOneExists(hasTestTag("calendar_never_synced"), 5_000)
        composeRule.onNodeWithTag("calendar_empty").assertDoesNotExist()
    }

    @Test
    fun unaFuenteCaidaSeDeclaraIncompleta() {
        login()
        val partial = calendarBody().replace(
            """{"name":"training","status":"OK","truncated":false}""",
            """{"name":"training","status":"UNAVAILABLE","truncated":false}""",
        )
        openCalendarFromMenu(json(partial))

        composeRule.waitUntilExactlyOneExists(hasTestTag("calendar_incomplete_training"), 5_000)
    }

    @Test
    fun sinLaCapacidadLaOpcionNoApareceYLaRutaDirectaSeRechaza() {
        login(CAPABILITIES.replace(""","calendar.sport.view"""", ""))

        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("menu_calendar.sport.view").assertDoesNotExist()

        composeRule.runOnIdle { navController.navigate(Destinations.CALENDAR) }
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), 5_000)
        composeRule.onNodeWithTag("calendar_screen").assertDoesNotExist()
        assertEquals("solo inicio de sesión y capacidades", 2, server.requestCount)
    }

    @Test
    fun unaRespuesta403CapabilityRequiredSeInforma() {
        login()
        openCalendarFromMenu(
            json("""{"code":"capability_required","message":"m","correlation_id":"c-1"}""", status = 403)
        )

        composeRule.waitUntilExactlyOneExists(hasText("Tu plan actual no incluye el calendario deportivo."), 5_000)
    }

    private companion object {
        const val USER_ID = "7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b"
        const val SESSION =
            """{"token_type":"Bearer","access_token":"access-sintetico","expires_in":900,""" +
                """"refresh_token":"refresh-token-sintetico-000000000001","refresh_expires_in":604800,""" +
                """"user":{"user_id":"$USER_ID","full_name":"Ana Deportista",""" +
                """"email":"ana@example.com","actor_type":"athlete"}}"""
        const val CAPABILITIES =
            """{"actor_type":"athlete","subscription_status":"ACTIVE","plan_code":"FREE","plan_version":1,""" +
                """"catalog_version":"plans-2026-10-11.v1","authorization_policy_version":"actor-permissions.v1",""" +
                """"permissions":[],"capabilities":["training.session.record","calendar.sport.view"],""" +
                """"evaluated_at":"2026-10-12T12:00:00Z"}"""
    }
}
