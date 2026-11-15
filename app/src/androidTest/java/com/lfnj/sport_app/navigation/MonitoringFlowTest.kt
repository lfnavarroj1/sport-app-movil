package com.lfnj.sport_app.navigation

import android.content.Intent
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
import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.features.sensors.data.local.SensorDeviceEntity
import com.lfnj.sport_app.features.sensors.data.local.SensorsCodec
import com.lfnj.sport_app.features.sensors.data.local.StoredDevice
import com.lfnj.sport_app.features.sensors.model.DeviceIdHasher
import com.lfnj.sport_app.features.sensors.model.DeviceStatus
import com.lfnj.sport_app.features.training.session.data.StartResult
import com.lfnj.sport_app.features.training.session.foreground.ActiveSessionDependencies
import com.lfnj.sport_app.features.training.session.foreground.ActiveSessionService
import com.lfnj.sport_app.features.training.session.model.SyncState
import com.lfnj.sport_app.features.training.session.model.TrainingOwner
import com.lfnj.sport_app.features.training.session.model.TrainingSport
import com.lfnj.sport_app.testing.InMemoryTokenStore
import com.lfnj.sport_app.testing.SimulatedHeartRateSource
import com.lfnj.sport_app.testing.TestAppContainer
import com.lfnj.sport_app.ui.theme.SportappTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * HU036 en la aplicación (menú 210, pantalla, ViewModel, servicio en primer plano real de HU032/HU038,
 * tarea de ingestión de HU037, Room y Keystore reales, cola de HU032) con la fuente BLE **SIMULADA
 * marcada como prueba** (ADR-044 §4, R-20) y un `bff-mobile` simulado con MockWebServer según la copia
 * 0.21.0, que responde la evaluación de cada lote como describe el contrato. Prueba de componente con
 * contrato simulado: **no** es un E2E contra el backend ni evidencia de un monitor real (R-20).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MonitoringFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val defaultDependencies = ActiveSessionService.dependencies
    private lateinit var container: TestAppContainer
    private lateinit var navController: NavHostController

    private val responses = ConcurrentHashMap<String, ArrayDeque<MockResponse>>()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    /** Umbral vigente en el BFF simulado; las muestras sobre él producen `RISK` con la misma alerta. */
    @Volatile private var threshold = 171
    @Volatile private var measurementsOnline = true

    private val owner = TrainingOwner.keyOf(USER_ID)
    private val deviceId = DeviceIdHasher.deviceId(SimulatedHeartRateSource.DEFAULT_ADDRESS, USER_ID)
    private val alertId = UUID.nameUUIDFromBytes("hu036-flujo-alerta".toByteArray()).toString()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.requestUrl?.encodedPath.orEmpty()
                if (request.method == "POST" && path.endsWith("/measurements")) return measurements(request, path)
                if (request.method == "PUT" && path == SETTINGS) {
                    threshold = Regex("\\d+").find(request.body.peek().readUtf8())!!.value.toInt()
                    return json(settings(threshold, "CUSTOM", "\"2026-10-24T12:00:00Z\""))
                }
                val key = "${request.method} $path"
                return synchronized(responses) { responses[key]?.removeFirstOrNull() }
                    ?: MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            }
        }
        server.start()
        val config = AppConfig(server.url("/").toString(), "test", "0.21.0", "0.1.0")
        container = TestAppContainer(config, InMemoryTokenStore(), ApplicationProvider.getApplicationContext())
        // El servicio real usa el seguimiento y las tareas del contenedor de prueba (fuente simulada, bases en memoria).
        ActiveSessionService.dependencies = { ActiveSessionDependencies(container.activeSessionTracker, container.activeSessionTasks) }
        composeRule.setContent {
            navController = rememberNavController()
            SportappTheme { AppNavHost(container, navController = navController) }
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        context.stopService(Intent(context, ActiveSessionService::class.java))
        ActiveSessionService.dependencies = defaultDependencies
        server.shutdown()
        container.close()
    }

    /** Respuesta del contrato: todas las muestras aceptadas y la evaluación según el umbral vigente. */
    private fun measurements(request: RecordedRequest, path: String): MockResponse {
        if (!measurementsOnline) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        val body = Json.parseToJsonElement(request.body.peek().readUtf8()).jsonObject
        val values = body.getValue("samples").jsonArray.map { it.jsonObject.getValue("value").toString().toInt() }
        val sessionId = path.removePrefix("/v1/training-sessions/").removeSuffix("/measurements")
        val risk = values.isNotEmpty() && values.all { it > threshold }
        val evaluation = if (risk) {
            """{"status":"RISK","alerts":[{"alert_id":"$alertId","rule":"HR_ABOVE_THRESHOLD_V1","severity":"HIGH","message":"$MESSAGE",""" +
                """"instruction":"$INSTRUCTION","detected_at":"${DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS))}"}]}"""
        } else {
            """{"status":"OK","alerts":[]}"""
        }
        return json(
            """{"session_id":"$sessionId","device_id":"$deviceId","received":${values.size},"accepted":${values.size},""" +
                """"duplicates":0,"rejected":[],"evaluation":$evaluation}"""
        )
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

    /** Monitor activo vinculado en este teléfono (HU037), cifrado con la clave de prueba. */
    private fun linkedMonitor() = runBlocking {
        val codec = SensorsCodec(container.sensorsCipher, BffMobileClient.json)
        val stored = StoredDevice(deviceId, "Banda de Ana", DeviceStatus.ACTIVE, 1, Instant.EPOCH, Instant.EPOCH, null, SimulatedHeartRateSource.DEFAULT_ADDRESS)
        container.sensors.sensorsDao().upsertDevice(SensorDeviceEntity(owner, deviceId, codec.encodeDevice(stored)))
    }

    /** Sesión de HU032 iniciada y confirmada por el BFF simulado; el servicio en primer plano la acompaña. */
    private fun activeSession(): String = runBlocking {
        val started = container.trainingSessionRepository.start(owner, TrainingSport.RUNNING) as StartResult.Started
        respond("POST", "/v1/training-sessions", json(sessionBody(started.sessionId), 201))
        container.trainingSyncEngine.run(owner)
        container.activeSessionTracker.start(scope)
        started.sessionId
    }

    private fun openMonitoring() {
        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("athlete_menu").performScrollToNode(hasTestTag(MENU_TAG))
        composeRule.onNodeWithTag(MENU_TAG).assertTextContains(context.getString(R.string.menu_athlete_realtime))
        composeRule.onNodeWithTag(MENU_TAG).performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("monitoring_screen"), TIMEOUT)
    }

    private fun sessionBody(id: String, finished: Boolean = false): String {
        val end = if (finished) "\"2026-10-24T12:40:00Z\"" else "null"
        val duration = if (finished) "600" else "null"
        val reason = if (finished) "\"RISK_STOP\"" else "null"
        return """{"session_id":"$id","athlete_id":"$USER_ID","plan_id":null,"sport":"running","started_at":"2026-10-24T12:30:00Z",""" +
            """"ended_at":$end,"duration_seconds":$duration,"status":"${if (finished) "FINISHED" else "ACTIVE"}","version":${if (finished) 2 else 1},""" +
            """"created_at":"2026-10-24T12:30:00Z","updated_at":"2026-10-24T12:30:00Z","end_reason":$reason}"""
    }

    private fun settings(bpm: Int, source: String, updatedAt: String = "null") =
        """{"max_heart_rate_alert_bpm":$bpm,"source":"$source","updated_at":$updatedAt,"disclaimer":"$DISCLAIMER"}"""

    @Test
    fun alertaConUmbralBajoYDetenerFinalizaLaSesionUnaVezConRiskStop() {
        login()
        linkedMonitor()
        respond("GET", SETTINGS, json(settings(171, "FIXED_DEFAULT")))
        val sessionId = activeSession()
        openMonitoring()

        // CA1: valor en vivo de la fuente simulada declarada, conexión y lotes aceptados con evaluación OK.
        composeRule.onNodeWithTag("monitoring_simulated_source").assertIsDisplayed()
        composeRule.waitUntilExactlyOneExists(hasTestTag("monitoring_live_value"), TIMEOUT)
        composeRule.onNodeWithTag("monitoring_live_value").assertTextContains("lpm", substring = true)
        composeRule.onNodeWithTag("monitoring_connection")
            .assertTextEquals(context.getString(R.string.monitoring_connection, context.getString(R.string.devices_connection_connected)))
        composeRule.waitUntil(BATCH_TIMEOUT) {
            composeRule.onAllNodes(hasTestTag("monitoring_remote") and hasText("sin riesgo detectado", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("monitoring_threshold_current").performScrollTo()
            .assertTextEquals(context.getString(R.string.monitoring_threshold_current, 171))
        composeRule.onNodeWithTag("monitoring_threshold_source").assertTextEquals(context.getString(R.string.monitoring_threshold_source_fixed))
        composeRule.onNodeWithTag("monitoring_disclaimer").assertTextEquals(DISCLAIMER)

        // Umbral bajo (100 lpm < 128 lpm simulados) confirmado por el BFF.
        composeRule.onNodeWithTag("monitoring_threshold_input").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("monitoring_threshold_input").performTextInput("100")
        composeRule.onNodeWithTag("monitoring_threshold_save").performScrollTo().performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("monitoring_threshold_saved"), TIMEOUT)
        assertEquals("""{"max_heart_rate_alert_bpm":100}""", requestsTo("PUT", SETTINGS).single().body.readUtf8())

        // CA2: la alerta del lote siguiente se muestra con instrucción y aviso, y se notifica una sola vez.
        composeRule.waitUntilExactlyOneExists(hasTestTag("monitoring_alert"), BATCH_TIMEOUT)
        composeRule.onNodeWithTag("monitoring_alert_message").assertTextContains("no es un diagnóstico médico", substring = true)
        composeRule.onNodeWithTag("monitoring_alert_instruction").assertTextEquals(INSTRUCTION)
        composeRule.onNodeWithTag("monitoring_remote").assertTextContains("riesgo detectado", substring = true)
        val batchesAtAlert = requests.count { it.requestUrl?.encodedPath?.endsWith("/measurements") == true }
        composeRule.waitUntil(BATCH_TIMEOUT) { requests.count { it.requestUrl?.encodedPath?.endsWith("/measurements") == true } > batchesAtAlert }
        assertEquals("el mismo alert_id no se notifica dos veces", 1, container.recordingRiskAlertNotifier.posted.size)
        assertEquals(alertId, container.recordingRiskAlertNotifier.posted.single().alertId)

        // CA4: detener la práctica una sola vez.
        composeRule.onNodeWithTag("monitoring_stop").performScrollTo().performClick()
        composeRule.onNodeWithTag("monitoring_stop_confirm").performScrollTo().performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("monitoring_stopped"), TIMEOUT)
        composeRule.waitUntil(TIMEOUT) { container.activeSessionTracker.active.value == null }

        val finishPath = "/v1/training-sessions/$sessionId/finish"
        respond("POST", finishPath, json(sessionBody(sessionId, finished = true)))
        runBlocking { container.trainingSyncEngine.run(owner) }
        val finish = requestsTo("POST", finishPath).single()
        val body = Json.parseToJsonElement(finish.body.readUtf8()).jsonObject
        assertEquals(setOf("ended_at", "reason"), body.keys)
        assertEquals("\"RISK_STOP\"", body.getValue("reason").toString())
        val local = runBlocking { container.trainingSessionRepository.observe(owner).first() }.single { it.sessionId == sessionId }
        assertEquals(SyncState.SYNCED, local.syncState)
        assertTrue(local.details?.finished == true)
    }

    @Test
    fun sinRedSigueMostrandoDatosLocalesYDeclaraSinEvaluacionRemota() {
        login()
        linkedMonitor()
        respond("GET", SETTINGS, json(settings(171, "FIXED_DEFAULT")))
        activeSession()
        measurementsOnline = false
        container.online.value = false
        openMonitoring()

        composeRule.waitUntilExactlyOneExists(hasTestTag("monitoring_live_value"), TIMEOUT)
        composeRule.onNodeWithTag("monitoring_offline").assertIsDisplayed()
        composeRule.waitUntil(BATCH_TIMEOUT) {
            composeRule.onAllNodes(hasTestTag("monitoring_remote") and hasText(context.getString(R.string.monitoring_remote_unavailable))).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("monitoring_live_value").assertTextContains("lpm", substring = true)

        // Recuperada la red, los lotes guardados se reenvían y vuelve la evaluación remota.
        measurementsOnline = true
        container.online.value = true
        composeRule.waitUntil(BATCH_TIMEOUT) {
            composeRule.onAllNodes(hasTestTag("monitoring_remote") and hasText("sin riesgo detectado", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun sinDatosDelMonitorDuranteMasDe30sInformaPerdidaDeMonitoreo() {
        login()
        linkedMonitor()
        respond("GET", SETTINGS, json(settings(171, "FIXED_DEFAULT")))
        activeSession()
        openMonitoring()
        composeRule.waitUntilExactlyOneExists(hasTestTag("monitoring_live_value"), TIMEOUT)

        // El monitor simulado deja de responder: no vuelve a conectar ni a emitir.
        container.heartRateSource.outcome = SimulatedHeartRateSource.Outcome.NO_RESPONSE
        container.heartRateSource.forceDisconnect()

        composeRule.waitUntilExactlyOneExists(hasTestTag("monitoring_no_data"), NO_DATA_TIMEOUT)
        assertEquals(0, composeRule.onAllNodes(hasTestTag("monitoring_live_value")).fetchSemanticsNodes().size)
    }

    @Test
    fun sinElPlanIntermedioLaOpcionNoApareceYLaRutaExplicaElPlan() {
        login(capabilities = FREE, plan = "FREE")
        composeRule.onNodeWithTag("open_menu").performClick()
        assertEquals(0, composeRule.onAllNodes(hasTestTag(MENU_TAG)).fetchSemanticsNodes().size)

        composeRule.runOnIdle { navController.navigate(Destinations.REALTIME_MONITORING) }
        composeRule.waitUntilExactlyOneExists(hasTestTag("monitoring_capability_required"), TIMEOUT)
        composeRule.onNodeWithTag("monitoring_capability_required").assertTextEquals(context.getString(R.string.monitoring_capability_required))
        assertTrue(requestsTo("GET", SETTINGS).isEmpty())
    }

    private companion object {
        const val TIMEOUT = 10_000L
        const val BATCH_TIMEOUT = 20_000L
        const val NO_DATA_TIMEOUT = 45_000L
        const val SETTINGS = "/v1/me/monitoring-settings"
        const val MENU_TAG = "menu_training.realtime.monitor"
        const val USER_ID = "7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b"
        const val MESSAGE = "Tu frecuencia cardiaca supera el límite que configuraste. Reduce la intensidad o detén la práctica. " +
            "Esto no es un diagnóstico médico."
        const val INSTRUCTION = "Reduce la intensidad o detén la práctica."
        const val DISCLAIMER = "El umbral no es un criterio clínico ni un diagnóstico médico."
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
