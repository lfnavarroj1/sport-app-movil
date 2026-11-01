package com.lfnj.sport_app.navigation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lfnj.sport_app.R
import com.lfnj.sport_app.core.config.AppConfig
import com.lfnj.sport_app.features.sensors.ble.BleAvailability
import com.lfnj.sport_app.features.sensors.model.DeviceIdHasher
import com.lfnj.sport_app.testing.InMemoryTokenStore
import com.lfnj.sport_app.testing.SimulatedHeartRateSource
import com.lfnj.sport_app.testing.TestAppContainer
import com.lfnj.sport_app.ui.theme.SportappTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * HU037 en la aplicación (menú, guarda por capacidad, ViewModel, Room y cifrado reales) con la
 * fuente BLE **SIMULADA marcada como prueba** (ADR-044 §4, R-20) y un `bff-mobile` simulado con
 * MockWebServer según la copia 0.21.0. Prueba de componente con contrato simulado, **no** un E2E ni
 * evidencia de un monitor real.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DevicesFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val server = MockWebServer()
    private lateinit var container: TestAppContainer
    private lateinit var navController: NavHostController
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Respuestas por «MÉTODO ruta»; sin respuesta preparada se corta la conexión (falta de red). */
    private val responses = ConcurrentHashMap<String, ArrayDeque<MockResponse>>()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    private val deviceId = DeviceIdHasher.deviceId(SimulatedHeartRateSource.DEFAULT_ADDRESS, USER_ID)

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val key = "${request.method} ${request.requestUrl?.encodedPath}"
                return synchronized(responses) { responses[key]?.removeFirstOrNull() }
                    ?: MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            }
        }
        server.start()
        val config = AppConfig(server.url("/").toString(), "test", "0.21.0", "0.1.0")
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

    private fun respond(method: String, path: String, response: MockResponse) = synchronized(responses) {
        responses.getOrPut("$method $path") { ArrayDeque() }.addLast(response)
    }

    private fun requestsTo(method: String, path: String) = requests.filter { it.method == method && it.requestUrl?.encodedPath == path }

    private fun login(capabilities: List<String> = INTERMEDIATE, plan: String = "INTERMEDIATE") {
        respond("POST", "/v1/sessions", json(SESSION))
        respond("GET", "/v1/me/capabilities", json(capabilitiesBody(capabilities, plan)))
        composeRule.waitUntilExactlyOneExists(hasTestTag("login_email"), TIMEOUT)
        composeRule.onNodeWithTag("login_email").performTextInput("ana@example.com")
        composeRule.onNodeWithTag("login_password").performTextInput("Secreta-123")
        composeRule.onNodeWithTag("login_submit").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), TIMEOUT)
        composeRule.waitUntilExactlyOneExists(hasText("Plan $plan activo."), TIMEOUT)
    }

    private fun openFromMenu() {
        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("athlete_menu").performScrollToNode(hasTestTag(MENU_TAG))
        composeRule.onNodeWithTag(MENU_TAG).assertTextContains(context.getString(R.string.menu_athlete_devices))
        composeRule.onNodeWithTag(MENU_TAG).performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("devices_screen"), TIMEOUT)
    }

    private fun device(status: String = "ACTIVE", version: Int = 1, name: String = "Banda de Ana"): String {
        val revokedAt = if (status == "REVOKED") "\"2026-10-27T15:05:00Z\"" else "null"
        return """{"device_id":"$deviceId","athlete_id":"$USER_ID","display_name":"$name","device_type":"HEART_RATE_MONITOR",""" +
            """"protocol":"BLE_HRS","status":"$status","version":$version,"created_at":"2026-10-27T15:00:00Z",""" +
            """"updated_at":"2026-10-27T15:0${version - 1}:00Z","revoked_at":$revokedAt}"""
    }

    private fun search() {
        composeRule.onNodeWithTag("devices_search").performScrollTo().performClick()
    }

    @Test
    fun intermedioVeLaOpcionEnElMenuYAbreLaPantallaConLaFuenteSimuladaDeclarada() {
        login()
        respond("GET", "/v1/me/devices", json("""{"items":[]}"""))
        openFromMenu()

        composeRule.onNodeWithTag("devices_simulated_source").performScrollTo().assertIsDisplayed()
            .assertTextEquals(context.getString(R.string.devices_simulated_source))
        composeRule.waitUntilExactlyOneExists(hasTestTag("devices_empty"), TIMEOUT)
        composeRule.onNodeWithTag("devices_transparency").performScrollTo().assertIsDisplayed()
        assertEquals(1, requestsTo("GET", "/v1/me/devices").size)
    }

    @Test
    fun sinElPlanIntermedioLaOpcionNoAparece() {
        login(capabilities = FREE, plan = "FREE")
        composeRule.onNodeWithTag("open_menu").performClick()
        assertEquals(0, composeRule.onAllNodes(hasTestTag(MENU_TAG)).fetchSemanticsNodes().size)
        // Tampoco por ruta directa: la guarda devuelve al inicio sin consultar los dispositivos.
        composeRule.runOnIdle { navController.navigate(Destinations.DEVICES) }
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), TIMEOUT)
        assertTrue(requestsTo("GET", "/v1/me/devices").isEmpty())
    }

    @Test
    fun vinculaConLaFuenteSimuladaMuestraLaFrecuenciaYRevoca() {
        login()
        respond("GET", "/v1/me/devices", json("""{"items":[]}"""))
        respond("POST", "/v1/me/devices", json(device(), 201))
        respond("DELETE", "/v1/me/devices/$deviceId", json(device(status = "REVOKED", version = 2)))
        openFromMenu()
        composeRule.waitUntilExactlyOneExists(hasTestTag("devices_empty"), TIMEOUT)

        search()
        composeRule.waitUntilExactlyOneExists(hasTestTag("devices_found"), TIMEOUT)
        composeRule.onNodeWithTag("devices_found").performScrollTo().assertTextContains(SimulatedHeartRateSource.DEFAULT_NAME, substring = true)
        composeRule.onNodeWithTag("devices_found").performClick()

        // Transparencia visible antes de confirmar la vinculación.
        composeRule.waitUntilExactlyOneExists(hasTestTag("devices_link_section"), TIMEOUT)
        composeRule.onNodeWithTag("devices_link_transparency").performScrollTo().assertIsDisplayed()
        assertTrue("aún no se vinculó nada", requestsTo("POST", "/v1/me/devices").isEmpty())
        composeRule.onNodeWithTag("devices_name_input").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("devices_name_input").performTextInput("Banda de Ana")
        composeRule.onNodeWithTag("devices_link_confirm").performScrollTo().performClick()

        composeRule.waitUntilExactlyOneExists(hasTestTag("devices_notice_linked"), TIMEOUT)
        val post = requestsTo("POST", "/v1/me/devices").single()
        val key = checkNotNull(post.getHeader("Idempotency-Key")) { "Falta Idempotency-Key" }
        assertTrue("Idempotency-Key UUID", UUID_PATTERN.matches(key))
        assertEquals("Bearer access-sintetico", post.getHeader("Authorization"))
        val body = Json.parseToJsonElement(post.body.readUtf8()).jsonObject
        assertEquals(deviceId, body["device_id"]?.jsonPrimitive?.content)
        assertEquals("Banda de Ana", body["display_name"]?.jsonPrimitive?.content)
        assertEquals("HEART_RATE_MONITOR", body["device_type"]?.jsonPrimitive?.content)
        assertEquals("BLE_HRS", body["protocol"]?.jsonPrimitive?.content)
        assertFalse("la dirección BLE no viaja al BFF", body.toString().contains(SimulatedHeartRateSource.DEFAULT_ADDRESS))
        assertEquals(setOf("device_id", "display_name", "device_type", "protocol"), body.keys)

        // Conexión con la fuente simulada: estado, batería y frecuencia a 1 Hz.
        composeRule.waitUntilExactlyOneExists(hasTestTag("devices_connected"), TIMEOUT)
        composeRule.waitUntil(TIMEOUT) {
            composeRule.onAllNodes(hasTestTag("devices_heart_rate") and hasText("lpm", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("devices_connection_state").performScrollTo()
            .assertTextEquals(context.getString(R.string.devices_connection_connected))
        composeRule.onNodeWithTag("devices_battery").performScrollTo()
            .assertTextEquals(context.getString(R.string.devices_battery, SimulatedHeartRateSource.DEFAULT_BATTERY))
        composeRule.onNodeWithTag("devices_simulated_source").performScrollTo().assertIsDisplayed()

        // Revocar: solo se confirma con la respuesta del backend.
        composeRule.onNodeWithTag("devices_revoke").performScrollTo().performClick()
        composeRule.onNodeWithTag("devices_revoke_confirm").performScrollTo().performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("devices_notice_revoked"), TIMEOUT)
        assertEquals(1, requestsTo("DELETE", "/v1/me/devices/$deviceId").size)
        composeRule.onNodeWithTag("devices_item_status").performScrollTo()
            .assertTextEquals(context.getString(R.string.devices_status_revoked))
        composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodes(hasTestTag("devices_connected")).fetchSemanticsNodes().isEmpty() }
        assertEquals(0, composeRule.onAllNodes(hasTestTag("devices_revoke")).fetchSemanticsNodes().size)
    }

    @Test
    fun sinMonitoresCompatiblesExplicaYNoVincula() {
        container.heartRateSource.monitors = emptyList()
        login()
        respond("GET", "/v1/me/devices", json("""{"items":[]}"""))
        openFromMenu()
        composeRule.waitUntilExactlyOneExists(hasTestTag("devices_empty"), TIMEOUT)

        search()
        composeRule.waitUntilExactlyOneExists(hasTestTag("devices_none_compatible"), TIMEOUT)
        composeRule.onNodeWithTag("devices_none_compatible").performScrollTo().assertIsDisplayed()
        assertTrue(requestsTo("POST", "/v1/me/devices").isEmpty())
    }

    @Test
    fun monitorSinServicioDeFrecuenciaCardiacaNoSeVincula() {
        container.heartRateSource.outcome = SimulatedHeartRateSource.Outcome.MISSING_HEART_RATE_SERVICE
        login()
        respond("GET", "/v1/me/devices", json("""{"items":[]}"""))
        openFromMenu()
        composeRule.waitUntilExactlyOneExists(hasTestTag("devices_empty"), TIMEOUT)

        search()
        composeRule.waitUntilExactlyOneExists(hasTestTag("devices_found"), TIMEOUT)
        composeRule.onNodeWithTag("devices_found").performScrollTo().performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("devices_link_section"), TIMEOUT)
        composeRule.onNodeWithTag("devices_link_confirm").performScrollTo().performClick()

        composeRule.waitUntilExactlyOneExists(hasTestTag("devices_link_problem"), TIMEOUT)
        composeRule.onNodeWithTag("devices_link_problem").performScrollTo()
            .assertTextEquals(context.getString(R.string.devices_link_missing_service))
        assertTrue("sin servicio 0x180D no se crea vinculación", requestsTo("POST", "/v1/me/devices").isEmpty())
    }

    @Test
    fun permisoDenegadoNoCreaVinculacion() {
        container.heartRateSource.availability = BleAvailability.PERMISSION_REQUIRED
        login()
        respond("GET", "/v1/me/devices", json("""{"items":[]}"""))
        openFromMenu()

        composeRule.waitUntilExactlyOneExists(hasTestTag("devices_permission_required"), TIMEOUT)
        composeRule.onNodeWithTag("devices_permission_required").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("devices_grant_permissions").performScrollTo().assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodes(hasTestTag("devices_search")).fetchSemanticsNodes().size)
        assertEquals(0, container.heartRateSource.connectAttempts.get())
        assertTrue("sin permiso no se crea vinculación", requests.none { it.method == "POST" && it.requestUrl?.encodedPath == "/v1/me/devices" })
    }

    private companion object {
        const val TIMEOUT = 10_000L
        const val MENU_TAG = "menu_devices.measurement.connect"
        const val USER_ID = "7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b"
        val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        val INTERMEDIATE = listOf("training.session.record", "training.realtime.monitor", "devices.measurement.connect")
        val FREE = listOf("training.session.record")
        const val SESSION =
            """{"token_type":"Bearer","access_token":"access-sintetico","expires_in":900,""" +
                """"refresh_token":"refresh-token-sintetico-000000000001","refresh_expires_in":604800,""" +
                """"user":{"user_id":"$USER_ID","full_name":"Ana Deportista",""" +
                """"email":"ana@example.com","actor_type":"athlete"}}"""

        fun capabilitiesBody(capabilities: List<String>, plan: String) =
            """{"actor_type":"athlete","subscription_status":"ACTIVE","plan_code":"$plan","plan_version":1,""" +
                """"catalog_version":"plans-2026-10-11.v1","authorization_policy_version":"actor-permissions.v1",""" +
                """"permissions":[],"capabilities":${capabilities.joinToString(",", "[", "]") { "\"$it\"" }},""" +
                """"evaluated_at":"2026-10-12T12:00:00Z"}"""
    }
}
