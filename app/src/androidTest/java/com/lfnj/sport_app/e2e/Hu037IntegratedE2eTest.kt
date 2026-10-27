package com.lfnj.sport_app.e2e

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
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
import androidx.test.platform.app.InstrumentationRegistry
import com.lfnj.sport_app.core.config.AppConfig
import com.lfnj.sport_app.core.network.ApiResult
import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.network.apiCall
import com.lfnj.sport_app.core.session.AuthorizedResult
import com.lfnj.sport_app.features.sensors.data.ConnectableDevice
import com.lfnj.sport_app.features.sensors.data.DevicesApi
import com.lfnj.sport_app.features.sensors.data.MeasurementBatchRequestDto
import com.lfnj.sport_app.features.sensors.data.MeasurementBatchResponseDto
import com.lfnj.sport_app.features.sensors.data.MeasurementSampleDto
import com.lfnj.sport_app.features.sensors.model.DeviceIdHasher
import com.lfnj.sport_app.features.sensors.model.DiscoveredMonitor
import com.lfnj.sport_app.features.sensors.model.IngestionState
import com.lfnj.sport_app.features.sensors.sync.FlushResult
import com.lfnj.sport_app.features.training.session.foreground.ActiveSession
import com.lfnj.sport_app.features.training.session.model.SyncState
import com.lfnj.sport_app.features.training.session.model.TrainingOwner
import com.lfnj.sport_app.navigation.AppNavHost
import com.lfnj.sport_app.testing.InMemoryTokenStore
import com.lfnj.sport_app.testing.TestAppContainer
import com.lfnj.sport_app.ui.theme.SportappTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * E2E de HU037 contra el **`bff-mobile` real** (≥ 0.21.0, composición local `:18081`) con la
 * aplicación real (navegación, guarda por capacidad, ViewModel, Room y Keystore, sincronización de
 * HU032, tarea de ingestión y cargador de lotes). La única pieza sustituida es la fuente BLE: se usa
 * la **fuente SIMULADA marcada como prueba** ([com.lfnj.sport_app.testing.SimulatedHeartRateSource],
 * `simulated = true`, aviso visible en pantalla; ADR-044 §4). Por eso esta prueba **no es evidencia de
 * un monitor físico**: la verificación con un monitor BLE real queda pendiente (riesgo R-20).
 *
 * Recorrido: vincular (POST real con `Idempotency-Key`) → iniciar la sesión de HU032 confirmada por el
 * BFF → lotes aceptados (`accepted > 0`) → reenviar un lote ya enviado (`duplicates > 0`, idempotencia
 * natural) → revocar (DELETE real) → la siguiente ingestión recibe `409 device_revoked` → finalizar la
 * sesión. Cada ejecución usa una dirección BLE sintética nueva, por lo que crea un dispositivo nuevo.
 *
 * Precondiciones: deportista `INTERMEDIATE` sintético con `devices.measurement.connect` y
 * `training.realtime.monitor`, sin sesión de entrenamiento abierta; `adb reverse tcp:18081 tcp:18081`
 * y la app compilada con `sportapp.bffMobileBaseUrl.debug=http://localhost:18081/`. Se omite si no se
 * reciben los argumentos:
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.lfnj.sport_app.e2e.Hu037IntegratedE2eTest \
 *   -Pandroid.testInstrumentationRunnerArguments.e2eIntermediateEmail=<correo INTERMEDIATE> \
 *   -Pandroid.testInstrumentationRunnerArguments.e2ePassword=<contraseña sintética>
 * ```
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class Hu037IntegratedE2eTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val args = InstrumentationRegistry.getArguments()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var container: TestAppContainer? = null
    private lateinit var navController: NavHostController

    /** Dirección BLE sintética de esta ejecución (bit «administrada localmente»: no es de un fabricante). */
    private val address: String = ByteArray(5).also(SecureRandom()::nextBytes)
        .joinToString(":", prefix = "02:") { "%02X".format(it) }
    private val monitorName = "Banda simulada E2E ${address.takeLast(5).replace(":", "")}"

    private val app get() = checkNotNull(container)

    @Before
    fun setUp() {
        assumeTrue(
            "E2E omitido: faltan la cuenta INTERMEDIATE sintética o la contraseña",
            listOf("e2ePassword", "e2eIntermediateEmail").all { args.getString(it) != null },
        )
        val config = AppConfig.fromBuildConfig()
        assumeTrue("E2E omitido: la URL del BFF móvil no está configurada", config.isBffConfigured)
        val testContainer = TestAppContainer(config, InMemoryTokenStore(), context)
        container = testContainer
        // Fuente SIMULADA marcada (simulated = true): un único monitor sintético con 0x180D, batería y 1 Hz.
        testContainer.heartRateSource.monitors = listOf(DiscoveredMonitor(address, monitorName))
        composeRule.setContent {
            navController = rememberNavController()
            SportappTheme { AppNavHost(testContainer, navController = navController) }
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        container?.close()
    }

    private fun arg(key: String): String = requireNotNull(args.getString(key)) { "Falta el argumento $key" }

    private fun login() {
        composeRule.waitUntilExactlyOneExists(hasTestTag("login_email"), TIMEOUT)
        composeRule.onNodeWithTag("login_email").performTextInput(arg("e2eIntermediateEmail"))
        composeRule.onNodeWithTag("login_password").performTextInput(arg("e2ePassword"))
        composeRule.onNodeWithTag("login_submit").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), TIMEOUT)
        composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodes(hasText("Plan ", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openMenu(tag: String, screen: String) {
        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("athlete_menu").performScrollToNode(hasTestTag(tag))
        composeRule.onNodeWithTag(tag).performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag(screen), TIMEOUT)
    }

    private fun backHome(backTag: String) {
        composeRule.onNodeWithTag(backTag).performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), TIMEOUT)
    }

    /** Ejecuta la sincronización de HU032 (controlada por la prueba) hasta que el BFF confirma la acción. */
    private fun awaitSynced(owner: String, sessionId: String, finished: Boolean) {
        val deadline = System.currentTimeMillis() + SYNC_TIMEOUT
        while (System.currentTimeMillis() < deadline) {
            runBlocking { app.trainingSyncEngine.run(owner) }
            val local = runBlocking { app.trainingSessionRepository.observe(owner).first() }.firstOrNull { it.sessionId == sessionId }
            if (local?.syncState == SyncState.SYNCED && local.details?.finished == finished) return
            Thread.sleep(1_000)
        }
        fail("El BFF no confirmó la sesión (finished=$finished)")
    }

    /** Lote enviado directamente con el cliente real de `bff-mobile` (mismo contrato que el cargador). */
    private fun ingest(sessionId: String, body: MeasurementBatchRequestDto): ApiResult<MeasurementBatchResponseDto> {
        val api = checkNotNull(app.devicesApiForTest) { "BFF no configurado" }
        return when (val outcome = runBlocking { app.sessionManager.authorized { auth -> apiCall(BffMobileClient.json) { api.ingest(auth, sessionId, body) } } }) {
            is AuthorizedResult.Completed -> outcome.result
            else -> error("Sin sesión vigente para ingerir: $outcome")
        }
    }

    /** «Revocar» de la tarjeta de esta ejecución (la cuenta puede tener dispositivos de corridas anteriores). */
    private val linkedRevokeButton
        get() = hasTestTag("devices_revoke") and hasAnyAncestor(hasTestTag("devices_item") and hasAnyDescendant(hasText(monitorName)))

    @Test
    fun vinculaIngiereConIdempotenciaRevocaYLaIngestionSiguienteEsRechazada() {
        login()
        val userId = checkNotNull(app.sessionManager.currentUser.value).userId
        val owner = TrainingOwner.keyOf(userId)
        val deviceId = DeviceIdHasher.deviceId(address, userId)

        // 1. Vincular con la fuente simulada declarada en pantalla (POST real).
        openMenu(DEVICES_MENU, "devices_screen")
        composeRule.onNodeWithTag("devices_simulated_source").performScrollTo().assertIsDisplayed()
        composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodes(hasTestTag("devices_search")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("devices_search").performScrollTo().performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("devices_found"), TIMEOUT)
        composeRule.onNodeWithTag("devices_found").performScrollTo().performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("devices_link_section"), TIMEOUT)
        composeRule.onNodeWithTag("devices_link_transparency").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("devices_link_confirm").performScrollTo().performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("devices_notice_linked"), TIMEOUT)
        val connectable = checkNotNull(runBlocking { app.devicesRepository.observeConnectable(owner).first() })
        assertEquals("identidad lógica = SHA-256(dirección + user_id)", deviceId, connectable.deviceId)
        composeRule.waitUntil(TIMEOUT) {
            composeRule.onAllNodes(hasTestTag("devices_heart_rate") and hasText("lpm", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        backHome("devices_back")

        // 2. Sesión real de HU032 confirmada por el BFF y tarea de ingestión de HU037 en marcha.
        openMenu(TRAINING_MENU, "training_session")
        composeRule.onNodeWithTag("sport_running").performClick()
        composeRule.onNodeWithTag("training_start").performScrollTo().performClick()
        val session = runBlocking { app.trainingSessionRepository.observe(owner).first { list -> list.any { it.open } } }.first { it.open }
        awaitSynced(owner, session.sessionId, finished = false)
        val startedAt = checkNotNull(session.details).startedAt
        val active = ActiveSession(owner, session.sessionId, startedAt, app.trainingSessionRepository.monotonicStartMillis(session.sessionId))
        scope.launch { app.measurementStreamTask.run(active) }

        // 3. Lotes de 1 Hz cada 5 s aceptados por el backend.
        composeRule.waitUntil(BATCH_TIMEOUT) { (app.measurementUploader.status.value.lastBatch?.accepted ?: 0) > 0 }
        assertEquals(IngestionState.SENDING, app.measurementUploader.status.value.state)

        // 4. Reenvío de un lote ya enviado: la idempotencia natural lo cuenta como duplicado.
        val resent = MeasurementBatchRequestDto(
            deviceId = deviceId,
            samples = (1L..3L).map { second ->
                MeasurementSampleDto(
                    DateTimeFormatter.ISO_INSTANT.format(startedAt.plusSeconds(second)),
                    DevicesApi.SAMPLE_TYPE_HEART_RATE,
                    120 + second.toInt(),
                    DevicesApi.SAMPLE_UNIT_BPM,
                )
            },
        )
        val first = ingest(session.sessionId, resent)
        assertTrue("primer envío confirmado: $first", first is ApiResult.Success)
        val firstReport = (first as ApiResult.Success).value
        assertEquals(3, firstReport.accepted + firstReport.duplicates)
        val again = ingest(session.sessionId, resent)
        assertTrue("reenvío confirmado: $again", again is ApiResult.Success)
        val againReport = (again as ApiResult.Success).value
        assertEquals("el mismo lote no se vuelve a aceptar", 0, againReport.accepted)
        assertEquals("el mismo lote se cuenta como duplicado", 3, againReport.duplicates)

        // 5. La pantalla muestra el último lote; revocar con el DELETE real.
        backHome("training_back")
        openMenu(DEVICES_MENU, "devices_screen")
        composeRule.waitUntilExactlyOneExists(hasTestTag("devices_connected"), TIMEOUT)
        composeRule.waitUntil(TIMEOUT) {
            composeRule.onAllNodes(hasTestTag("devices_ingestion") and hasText("Último lote", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodes(linkedRevokeButton).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNode(linkedRevokeButton).performScrollTo().performClick()
        composeRule.onNodeWithTag("devices_revoke_confirm").performScrollTo().performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("devices_notice_revoked"), TIMEOUT)
        composeRule.waitUntil(TIMEOUT) { runBlocking { app.devicesRepository.observeConnectable(owner).first() } == null }

        // 6. La siguiente ingestión del dispositivo revocado recibe 409 device_revoked (CA6).
        val now = Instant.now().minusSeconds(1)
        runBlocking { app.sensorSamplesForTest.record(owner, session.sessionId, deviceId, now.epochSecond, 131) }
        val flush = runBlocking { app.measurementUploader.flush(active, ConnectableDevice(deviceId, address)) }
        assertEquals(FlushResult.STOP, flush)
        assertEquals(IngestionState.DEVICE_REVOKED, app.measurementUploader.status.value.state)
        val rejected = ingest(
            session.sessionId,
            MeasurementBatchRequestDto(
                deviceId,
                listOf(MeasurementSampleDto(DateTimeFormatter.ISO_INSTANT.format(now), DevicesApi.SAMPLE_TYPE_HEART_RATE, 131, DevicesApi.SAMPLE_UNIT_BPM)),
            ),
        )
        assertTrue("409 device_revoked: $rejected", rejected is ApiResult.HttpFailure && rejected.status == 409 && rejected.code == "device_revoked")
        backHome("devices_back")

        // 7. Fin real de la sesión de HU032.
        openMenu(TRAINING_MENU, "training_session")
        composeRule.onNodeWithTag("training_finish").performScrollTo().performClick()
        awaitSynced(owner, session.sessionId, finished = true)
    }

    private companion object {
        const val TIMEOUT = 20_000L
        const val SYNC_TIMEOUT = 60_000L
        const val BATCH_TIMEOUT = 45_000L
        const val DEVICES_MENU = "menu_devices.measurement.connect"
        const val TRAINING_MENU = "menu_training.session.record"
    }
}
