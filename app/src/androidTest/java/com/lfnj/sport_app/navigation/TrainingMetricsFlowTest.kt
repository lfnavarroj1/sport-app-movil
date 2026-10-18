package com.lfnj.sport_app.navigation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lfnj.sport_app.core.config.AppConfig
import com.lfnj.sport_app.features.training.metrics.TrainingMetricsLocalStorageTest
import com.lfnj.sport_app.features.training.session.model.TrainingOwner
import com.lfnj.sport_app.features.training.session.sync.SyncRun
import com.lfnj.sport_app.testing.InMemoryTokenStore
import com.lfnj.sport_app.testing.TestAppContainer
import com.lfnj.sport_app.ui.theme.SportappTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * HU034 en la aplicación (navegación, guarda, ViewModel, Room y cifrado reales) contra un
 * `bff-mobile` simulado con MockWebServer según ADR-030. Prueba de componente con contrato
 * simulado, **no** un E2E.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TrainingMetricsFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val server = MockWebServer()
    private lateinit var container: TestAppContainer
    private lateinit var navController: NavHostController
    private val owner = TrainingOwner.keyOf("7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b")

    @Before
    fun setUp() {
        // Sin respuesta preparada, el servidor corta la conexión: así se simula la falta de red.
        server.dispatcher = QueueDispatcher().apply { setFailFast(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) }
        server.start()
        val config = AppConfig(server.url("/").toString(), "test", "0.8.0", "0.1.0")
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
        server.enqueue(json(TrainingMetricsLocalStorageTest.SESSION_BODY))
        server.enqueue(json(capabilities))
        composeRule.waitUntilExactlyOneExists(hasTestTag("login_email"), 5_000)
        composeRule.onNodeWithTag("login_email").performTextInput("ana@example.com")
        composeRule.onNodeWithTag("login_password").performTextInput("Secreta-123")
        composeRule.onNodeWithTag("login_submit").performClick()
        composeRule.waitUntilExactlyOneExists(hasText("Plan FREE activo."), 5_000)
        repeat(2) { server.takeRequest(1, TimeUnit.SECONDS) }
    }

    private fun openMetricsFromMenu() {
        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("athlete_menu").performScrollToNode(hasTestTag("menu_training.metrics.ftp_vo2max"))
        composeRule.onNodeWithTag("menu_training.metrics.ftp_vo2max").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("training_metrics"), 5_000)
    }

    @Test
    fun ca1Ca6Ca8CapturaPendienteYResultadoConfirmadoPorElBackend() {
        login()
        server.enqueue(json(TrainingMetricsLocalStorageTest.CATALOG).setHeader("ETag", "\"training-metrics-2026-10-12.v1\""))
        server.enqueue(json("""{"items":[],"next_cursor":null}"""))
        openMetricsFromMenu()
        assertEquals("/v1/training-metrics/catalog", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        assertEquals("/v1/training-metrics/calculations?limit=20", server.takeRequest(5, TimeUnit.SECONDS)?.path)

        composeRule.waitUntilExactlyOneExists(hasTestTag("metrics_method_FTP_20_MIN_95_V1"), 5_000)
        composeRule.onNodeWithTag("metrics_method_FTP_20_MIN_95_V1").performClick()
        composeRule.onNodeWithTag("metrics_input_mean_power_watts").performScrollTo().performTextInput("250")
        composeRule.onNodeWithTag("metrics_submit").performScrollTo().performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("metrics_confirm_disclaimer"), 5_000)
        composeRule.onNodeWithTag("metrics_confirm").performClick()

        // Encolado sin red ni resultado local: queda pendiente y se programa la sincronización.
        composeRule.waitUntilExactlyOneExists(hasText("Pendiente de sincronizar: aún no tiene resultado"), 5_000)
        assertEquals(listOf(owner), container.metricsScheduler.scheduled)
        assertEquals("La captura no llama al BFF: solo login, capacidades, catálogo e historial", 4, server.requestCount)

        // El trabajo durable envía el cálculo y el resultado lo da el backend (238 W).
        val pendingId = runBlocking { container.trainingMetricsRepository.observe(owner).first().pending.single().calculationId }
        server.enqueue(json(RESULT.replace("CALC_ID", pendingId), status = 201))
        assertEquals(SyncRun.COMPLETED, runBlocking { container.metricsSyncEngine.run(owner) })
        val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/v1/training-metrics/calculations", sent.path)
        assertTrue(sent.body.readUtf8().contains("\"value\":250"))

        composeRule.waitUntilExactlyOneExists(hasTestTag("metrics_result_value_$pendingId"), 5_000)
        composeRule.onNodeWithTag("metrics_result_value_$pendingId").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("238 W").assertIsDisplayed()
        composeRule.onNodeWithTag("metrics_disclaimer_$pendingId").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Pendiente de sincronizar: aún no tiene resultado").assertDoesNotExist()
    }

    @Test
    fun ca9SinRedYSinCatalogoNoSeOfreceNingunMetodo() {
        login()
        openMetricsFromMenu()

        composeRule.waitUntilExactlyOneExists(hasTestTag("metrics_catalog_missing"), 5_000)
        composeRule.onNodeWithTag("metrics_notice").assertIsDisplayed()
        composeRule.onNodeWithTag("metrics_method_FTP_20_MIN_95_V1").assertDoesNotExist()
    }

    @Test
    fun sinLaCapacidadLaOpcionNoApareceYLaRutaDirectaSeRechaza() {
        login(CAPABILITIES.replace(""","training.metrics.ftp_vo2max"""", ""))

        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("menu_training.metrics.ftp_vo2max").assertDoesNotExist()
        composeRule.runOnIdle { navController.navigate(Destinations.TRAINING_METRICS) }

        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), 5_000)
        composeRule.onNodeWithTag("training_metrics").assertDoesNotExist()
    }

    private companion object {
        const val CAPABILITIES =
            """{"actor_type":"athlete","subscription_status":"ACTIVE","plan_code":"FREE","plan_version":1,""" +
                """"catalog_version":"plans-2026-10-11.v1","authorization_policy_version":"actor-permissions.v1",""" +
                """"permissions":[],"capabilities":["training.session.record","training.metrics.ftp_vo2max"],""" +
                """"evaluated_at":"2026-10-12T12:00:00Z"}"""

        const val RESULT =
            """{"calculation_id":"CALC_ID","athlete_id":"7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b",""" +
                """"metric_code":"FTP_ESTIMATED","method_code":"FTP_20_MIN_95_V1","method_version":1,"measured_at":"2026-10-12T15:30:00Z",""" +
                """"inputs":[{"code":"mean_power_watts","value":250,"unit":"W"}],"result":{"value":238,"unit":"W","scale":0},""" +
                """"status":"CONFIRMED","calculated_at":"2026-10-12T15:30:02Z","disclaimer_key":"estimated_not_diagnostic","version":1}"""
    }
}
