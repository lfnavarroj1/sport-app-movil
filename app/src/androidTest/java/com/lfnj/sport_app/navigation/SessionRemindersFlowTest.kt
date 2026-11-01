package com.lfnj.sport_app.navigation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
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
import com.lfnj.sport_app.features.notifications.model.SystemNotificationsStatus
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
 * HU038 en la aplicación (menú 230, guarda, ViewModel, Room y cifrado reales) contra un
 * `bff-mobile` simulado con MockWebServer según la copia 0.18.0. Prueba de componente con contrato
 * simulado, **no** un E2E.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SessionRemindersFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val server = MockWebServer()
    private lateinit var container: TestAppContainer
    private lateinit var navController: NavHostController

    @Before
    fun setUp() {
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

    private fun login(capabilities: String = INTERMEDIATE) {
        server.enqueue(json(SESSION))
        server.enqueue(json(capabilities))
        composeRule.waitUntilExactlyOneExists(hasTestTag("login_email"), 5_000)
        composeRule.onNodeWithTag("login_email").performTextInput("ana@example.com")
        composeRule.onNodeWithTag("login_password").performTextInput("Secreta-123")
        composeRule.onNodeWithTag("login_submit").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), 5_000)
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Plan ", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        server.takeRequest(1, TimeUnit.SECONDS)
        server.takeRequest(1, TimeUnit.SECONDS)
    }

    private fun openReminders() {
        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("athlete_menu").performScrollToNode(hasTestTag("menu_nutrition.session.reminders"))
        composeRule.onNodeWithTag("menu_nutrition.session.reminders").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("reminders_screen"), 5_000)
    }

    private fun takeRequest() = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "No llegó la solicitud esperada" }

    @Test
    fun descargaLasReglasGuardaLasPreferenciasYLasConservaSinRed() {
        login()
        server.enqueue(json(RULES))
        server.enqueue(json(PREFERENCES_V0))
        openReminders()

        assertEquals("/v1/session-reminder-rules", takeRequest().requestUrl?.encodedPath)
        assertEquals("/v1/me/session-reminder-preferences", takeRequest().requestUrl?.encodedPath)
        composeRule.waitUntilExactlyOneExists(hasTestTag("reminders_card_hydration"), 5_000)
        composeRule.onNodeWithTag("reminders_message_hydration").assertTextContains("Hidrátate: bebe agua o bebida isotónica")

        composeRule.onNodeWithTag("reminders_decrease_hydration").performClick()
        composeRule.onNodeWithTag("reminders_interval_hydration").assertTextContains("Cada 10 minutos")
        server.enqueue(json(PREFERENCES_V1))
        composeRule.onNodeWithTag("reminders_save").performScrollTo().performClick()

        val put = takeRequest()
        assertEquals("PUT", put.method)
        assertEquals(
            """{"hydration":{"enabled":true,"interval_minutes":10},"nutrition":{"enabled":true,"interval_minutes":45},"expected_version":0}""",
            put.body.readUtf8(),
        )
        composeRule.waitUntilExactlyOneExists(hasTestTag("reminders_save_outcome"), 5_000)
        composeRule.onNodeWithTag("reminders_save_outcome").assertTextContains("Preferencias guardadas.")

        // Sin red: la configuración confirmada sigue disponible desde el almacenamiento cifrado.
        composeRule.onNodeWithTag("reminders_back").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), 5_000)
        openReminders()
        composeRule.waitUntilExactlyOneExists(hasTestTag("reminders_problem"), 5_000)
        composeRule.onNodeWithTag("reminders_interval_hydration").assertTextContains("Cada 10 minutos")
    }

    @Test
    fun sinRedYSinDescargaPreviaDeclaraInformacionInsuficiente() {
        login()
        openReminders()
        composeRule.waitUntilExactlyOneExists(hasTestTag("reminders_insufficient_information"), 5_000)
        composeRule.onNodeWithTag("reminders_insufficient_information").assertIsDisplayed()
    }

    @Test
    fun conLasNotificacionesDelSistemaDeshabilitadasLoDeclara() {
        container.recordingReminderNotifier.status = SystemNotificationsStatus.DISABLED
        login()
        server.enqueue(json(RULES))
        server.enqueue(json(PREFERENCES_V0))
        openReminders()
        composeRule.waitUntilExactlyOneExists(hasTestTag("reminders_system_disabled"), 5_000)
    }

    @Test
    fun unaVersionObsoletaRecargaLasPreferenciasVigentes() {
        login()
        server.enqueue(json(RULES))
        server.enqueue(json(PREFERENCES_V0))
        openReminders()
        composeRule.waitUntilExactlyOneExists(hasTestTag("reminders_card_hydration"), 5_000)
        takeRequest()
        takeRequest()

        composeRule.onNodeWithTag("reminders_increase_hydration").performClick()
        server.enqueue(json(CONFLICT, 409))
        server.enqueue(json(RULES))
        server.enqueue(json(PREFERENCES_V4))
        composeRule.onNodeWithTag("reminders_save").performScrollTo().performClick()

        composeRule.waitUntilExactlyOneExists(hasTestTag("reminders_save_outcome"), 5_000)
        composeRule.onNodeWithTag("reminders_save_outcome").assertTextContains("cambiaron en otro lugar", substring = true)
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasTestTag("reminders_interval_hydration") and hasText("Cada 30 minutos")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun sinElPlanIntermedioLaOpcionNoAparece() {
        login(FREE)
        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onAllNodes(hasTestTag("menu_nutrition.session.reminders")).fetchSemanticsNodes().let { assertEquals(0, it.size) }
    }

    private companion object {
        const val USER_ID = "7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b"
        const val DISCLAIMER = "Recordatorio general: no es un diagnóstico médico ni prescribe cantidades."
        const val SESSION =
            """{"token_type":"Bearer","access_token":"access-sintetico","expires_in":900,""" +
                """"refresh_token":"refresh-token-sintetico-000000000001","refresh_expires_in":604800,""" +
                """"user":{"user_id":"$USER_ID","full_name":"Ana Deportista",""" +
                """"email":"ana@example.com","actor_type":"athlete"}}"""

        fun capabilities(plan: String, capabilities: String) =
            """{"actor_type":"athlete","subscription_status":"ACTIVE","plan_code":"$plan","plan_version":1,""" +
                """"catalog_version":"plans-2026-10-11.v1","authorization_policy_version":"actor-permissions.v1",""" +
                """"permissions":[],"capabilities":[$capabilities],"evaluated_at":"2026-10-12T12:00:00Z"}"""

        val INTERMEDIATE = capabilities("INTERMEDIATE", """"training.session.record","nutrition.session.reminders"""")
        val FREE = capabilities("FREE", """"training.session.record"""")

        const val RULES =
            """{"ruleset_version":"session-reminders-2026-10-13.v1","rules":[""" +
                """{"type":"HYDRATION","default_interval_minutes":15,"min":10,"max":60,"applies_after_minutes":0,""" +
                """"message":"Hidrátate: bebe agua o bebida isotónica","disclaimer":"$DISCLAIMER"},""" +
                """{"type":"NUTRITION","default_interval_minutes":45,"min":30,"max":120,"applies_after_minutes":60,""" +
                """"message":"Consume un alimento ligero según tu plan","disclaimer":"$DISCLAIMER"}]}"""

        fun preferences(hydration: Int, version: Int) =
            """{"hydration":{"enabled":true,"interval_minutes":$hydration},"nutrition":{"enabled":true,"interval_minutes":45},"version":$version}"""

        val PREFERENCES_V0 = preferences(15, 0)
        val PREFERENCES_V1 = preferences(10, 1)
        val PREFERENCES_V4 = preferences(30, 4)
        const val CONFLICT =
            """{"code":"version_conflict","message":"mensaje","correlation_id":"8c1f2d3e-0000-4000-8000-000000000038",""" +
                """"details":[{"field":"expected_version","issue":"4"}]}"""
    }
}
