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
import com.lfnj.sport_app.features.training.dashboard.model.DashboardPeriod
import com.lfnj.sport_app.features.training.dashboard.model.DashboardRange
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
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * HU033 en la aplicación (navegación, guarda, ViewModel, Room y cifrado reales) contra un
 * `bff-mobile` simulado con MockWebServer según ADR-043. Prueba de componente con contrato
 * simulado, **no** un E2E.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TrainingDashboardFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val server = MockWebServer()
    private lateinit var container: TestAppContainer
    private lateinit var navController: NavHostController
    private val period = DashboardPeriod.preset(DashboardRange.LAST_30, LocalDate.now(ZoneId.systemDefault()), ZoneId.systemDefault())

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

    /** Dos sesiones en dos semanas y una FTP, con la forma de `TrainingDashboardResponse`. */
    private fun dashboardBody(hasData: Boolean = true): String {
        val data = if (hasData) {
            """"totals":{"sessions_count":2,"total_duration_minutes":95,"avg_duration_minutes":48,"distance_meters":null},""" +
                """"by_sport":[{"sport":"running","sessions_count":1,"total_duration_minutes":45,"distance_meters":null},""" +
                """{"sport":"cycling","sessions_count":1,"total_duration_minutes":50,"distance_meters":null}],""" +
                """"by_week":[{"week_start":"2026-10-12","sessions_count":1,"total_duration_minutes":45},""" +
                """{"week_start":"2026-10-19","sessions_count":1,"total_duration_minutes":50}],""" +
                """"by_source":{"SPORTAPP":{"sessions_count":2,"total_duration_minutes":95},"STRAVA":{"sessions_count":0,"total_duration_minutes":0}},""" +
                """"latest_metrics":[{"indicator":"FTP_ESTIMATED","value":238,"unit":"W","method_code":"FTP_20_MIN_95_V1",""" +
                """"method_version":1,"measured_at":"2026-10-20T12:30:00Z","disclaimer_key":"estimated_not_diagnostic"}],"""
        } else {
            """"totals":{"sessions_count":0,"total_duration_minutes":0,"avg_duration_minutes":0,"distance_meters":null},""" +
                """"by_sport":[],"by_week":[],""" +
                """"by_source":{"SPORTAPP":{"sessions_count":0,"total_duration_minutes":0},"STRAVA":{"sessions_count":0,"total_duration_minutes":0}},""" +
                """"latest_metrics":[],"""
        }
        return """{"period":{"from":"${period.fromParam}","to":"${period.toParam}"},"has_data":$hasData,$data""" +
            """"sources":[{"source":"training_sessions","status":"OK"},{"source":"training_metrics","status":"OK"}]}"""
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

    /** [response]: respuesta de `GET /v1/me/training-dashboard`; sin ella, falta de red. */
    private fun openDashboardFromMenu(response: MockResponse? = null) {
        response?.let(server::enqueue)
        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("athlete_menu").performScrollToNode(hasTestTag("menu_training.dashboard.view"))
        composeRule.onNodeWithTag("menu_training.dashboard.view").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("dashboard_screen"), 5_000)
    }

    @Test
    fun muestraTotalesBarrasYMetricaYLosConservaSinRed() {
        login()
        openDashboardFromMenu(json(dashboardBody()))

        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/v1/me/training-dashboard", request.requestUrl?.encodedPath)
        assertEquals(period.fromParam, request.requestUrl?.queryParameter("from"))
        assertEquals(period.toParam, request.requestUrl?.queryParameter("to"))
        assertEquals("Bearer access-sintetico", request.getHeader("Authorization"))

        composeRule.waitUntilExactlyOneExists(hasTestTag("dashboard_totals"), 5_000)
        composeRule.onNodeWithTag("dashboard_week_2026-10-12").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("dashboard_week_2026-10-19").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("dashboard_metric_FTP_ESTIMATED").performScrollTo().assertIsDisplayed()

        // Sin red: el mismo tablero sigue visible desde la caché cifrada, con su antigüedad.
        composeRule.onNodeWithTag("dashboard_back").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), 5_000)
        openDashboardFromMenu()
        composeRule.waitUntilExactlyOneExists(hasTestTag("dashboard_problem"), 5_000)
        composeRule.onNodeWithTag("dashboard_synced_at").assertIsDisplayed()
        composeRule.onNodeWithTag("dashboard_totals").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun sinDatosOfreceRegistrarEntrenamiento() {
        login()
        openDashboardFromMenu(json(dashboardBody(hasData = false)))

        composeRule.waitUntilExactlyOneExists(hasTestTag("dashboard_register_training"), 5_000)
        composeRule.onNodeWithTag("dashboard_weeks").assertDoesNotExist()
        composeRule.onNodeWithTag("dashboard_register_training").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("training_session"), 5_000)
    }

    @Test
    fun sinRedYSinCacheLoInforma() {
        login()
        openDashboardFromMenu()

        composeRule.waitUntilExactlyOneExists(hasTestTag("dashboard_never_synced"), 5_000)
        composeRule.onNodeWithTag("dashboard_empty").assertDoesNotExist()
    }

    @Test
    fun sinLaCapacidadLaOpcionNoApareceYLaRutaDirectaSeRechaza() {
        login(CAPABILITIES.replace(""","training.dashboard.view"""", ""))

        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("menu_training.dashboard.view").assertDoesNotExist()

        composeRule.runOnIdle { navController.navigate(Destinations.TRAINING_DASHBOARD) }
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), 5_000)
        composeRule.onNodeWithTag("dashboard_screen").assertDoesNotExist()
        assertEquals("solo inicio de sesión y capacidades", 2, server.requestCount)
    }

    @Test
    fun unaRespuesta403CapabilityRequiredSeInforma() {
        login()
        openDashboardFromMenu(json("""{"code":"capability_required","message":"m","correlation_id":"c-1"}""", status = 403))

        composeRule.waitUntilExactlyOneExists(hasText("Tu plan actual no incluye el tablero de entrenamiento."), 5_000)
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
                """"permissions":[],"capabilities":["training.session.record","training.dashboard.view"],""" +
                """"evaluated_at":"2026-10-12T12:00:00Z"}"""
    }
}
