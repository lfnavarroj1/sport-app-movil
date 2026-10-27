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
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lfnj.sport_app.core.config.AppConfig
import com.lfnj.sport_app.features.health.model.HealthOwner
import com.lfnj.sport_app.features.training.metrics.TrainingMetricsLocalStorageTest
import com.lfnj.sport_app.features.training.session.sync.SyncRun
import com.lfnj.sport_app.testing.InMemoryTokenStore
import com.lfnj.sport_app.testing.TestAppContainer
import com.lfnj.sport_app.ui.theme.SportappTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * HU042 en la aplicación (menú, guarda, ViewModel, Room y cifrado reales) contra un `bff-mobile`
 * simulado con MockWebServer según la copia 0.21.0. Prueba de componente con contrato simulado,
 * **no** un E2E.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class HealthReportFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val server = MockWebServer()
    private lateinit var container: TestAppContainer
    private lateinit var navController: NavHostController
    private val owner = HealthOwner.keyOf("7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b")

    @Before
    fun setUp() {
        // Sin respuesta preparada, el servidor corta la conexión: así se simula la falta de red.
        server.dispatcher = QueueDispatcher().apply { setFailFast(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) }
        server.start()
        val config = AppConfig(server.url("/").toString(), "test", "0.21.0", "0.1.0", healthDataConsentVersion = "local-dev")
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

    private fun loginAndOpenHealth() {
        server.enqueue(json(TrainingMetricsLocalStorageTest.SESSION_BODY))
        server.enqueue(json(CAPABILITIES))
        composeRule.waitUntilExactlyOneExists(hasTestTag("login_email"), 5_000)
        composeRule.onNodeWithTag("login_email").performTextInput("ana@example.com")
        composeRule.onNodeWithTag("login_password").performTextInput("Secreta-123")
        composeRule.onNodeWithTag("login_submit").performClick()
        composeRule.waitUntilExactlyOneExists(hasText("Plan FREE activo."), 5_000)
        repeat(2) { server.takeRequest(1, TimeUnit.SECONDS) }
        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("menu_health").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("health_report"), 5_000)
    }

    @Test
    fun ca5Ca6SinRedSeGuardaPendienteYSoloEl201LoConfirma() {
        container.online.value = false
        loginAndOpenHealth()
        composeRule.onNodeWithTag("health_offline").assertIsDisplayed()

        composeRule.onNodeWithTag("health_type_INJURY").performScrollTo().performClick()
        composeRule.onNodeWithTag("health_area_KNEE").performScrollTo().performClick()
        composeRule.onNodeWithTag("health_severity_HIGH").performScrollTo().performClick()
        composeRule.onNodeWithTag("health_professional_advice").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("health_consent").performScrollTo().performClick()
        composeRule.onNodeWithTag("health_submit").performScrollTo().performClick()

        // Guardado cifrado y programado, sin llamar al BFF: queda pendiente, nunca confirmado (CA5).
        composeRule.waitUntilExactlyOneExists(hasText("Pendiente de sincronizar"), 5_000)
        assertEquals(listOf(owner), container.healthScheduler.scheduled)
        assertEquals("La captura no llama al BFF: solo login y capacidades", 2, server.requestCount)
        composeRule.onNodeWithText("Confirmado").assertDoesNotExist()

        val report = runBlocking { container.healthReportsRepository.observe(owner).first().single() }
        val details = checkNotNull(report.details)
        server.enqueue(
            json(
                """{"report_id":"${report.reportId}","athlete_id":"7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b","type":"INJURY",""" +
                    """"body_area":"KNEE","severity":"HIGH","occurred_at":"${details.occurredAt}","session_id":null,""" +
                    """"expected_recovery_days":null,"consent":{"version":"local-dev"},"status":"OPEN",""" +
                    """"reported_at":"2026-10-24T18:35:02Z","version":1}""",
                status = 201,
            )
        )
        assertEquals(SyncRun.COMPLETED, runBlocking { container.healthReportsSyncEngine.run(owner) })

        val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/v1/me/health-reports", sent.path)
        val body = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
        assertEquals("local-dev", body.getValue("consent").jsonObject.getValue("version").jsonPrimitive.content)
        assertFalse("athlete_id" in body)
        composeRule.waitUntilExactlyOneExists(hasText("Confirmado"), 5_000)
        composeRule.onNodeWithText("Pendiente de sincronizar").assertDoesNotExist()
        composeRule.onNodeWithTag("health_report_advice_${report.reportId}").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun miSaludEsOpcionBaseAunSinCapacidadesDelPlan() {
        server.enqueue(json(TrainingMetricsLocalStorageTest.SESSION_BODY))
        server.enqueue(json(CAPABILITIES.replace("\"training.session.record\"", "")))
        composeRule.waitUntilExactlyOneExists(hasTestTag("login_email"), 5_000)
        composeRule.onNodeWithTag("login_email").performTextInput("ana@example.com")
        composeRule.onNodeWithTag("login_password").performTextInput("Secreta-123")
        composeRule.onNodeWithTag("login_submit").performClick()
        composeRule.waitUntilExactlyOneExists(hasText("Plan FREE activo."), 5_000)

        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("menu_health").assertIsDisplayed().performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("health_report"), 5_000)
    }

    private companion object {
        const val CAPABILITIES =
            """{"actor_type":"athlete","subscription_status":"ACTIVE","plan_code":"FREE","plan_version":1,""" +
                """"catalog_version":"plans-2026-10-11.v1","authorization_policy_version":"actor-permissions.v1",""" +
                """"permissions":[],"capabilities":["training.session.record"],""" +
                """"evaluated_at":"2026-10-12T12:00:00Z"}"""
    }
}
