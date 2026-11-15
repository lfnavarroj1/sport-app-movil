package com.lfnj.sport_app.e2e

import android.content.Context
import android.content.Intent
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
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lfnj.sport_app.core.config.AppConfig
import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.features.sensors.model.DiscoveredMonitor
import com.lfnj.sport_app.features.training.monitoring.data.SettingsResult
import com.lfnj.sport_app.features.training.session.data.local.CommandType
import com.lfnj.sport_app.features.training.session.data.local.PendingCommand
import com.lfnj.sport_app.features.training.session.data.local.TrainingRecordCodec
import com.lfnj.sport_app.features.training.session.foreground.ActiveSessionDependencies
import com.lfnj.sport_app.features.training.session.foreground.ActiveSessionService
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

/**
 * E2E de HU036 contra el **`bff-mobile` real** (≥ 0.21.0, composición local `:18081`) con la
 * aplicación real (menú 210, pantalla y ViewModel, servicio en primer plano, ingestión de HU037, cola
 * de HU032, Room y Keystore). La única pieza sustituida es la fuente BLE: la **fuente SIMULADA marcada
 * como prueba** (`simulated = true`, aviso visible; ADR-044 §4). Por eso **no es evidencia de un monitor
 * físico**: la verificación con un monitor BLE real queda pendiente del responsable (riesgo R-20).
 *
 * Recorrido (ADR-044, «Evidencia requerida»): vincular el monitor simulado → iniciar la sesión de HU032
 * confirmada por el BFF → ver la FC en vivo → configurar un umbral bajo (100 lpm) → la evaluación del
 * lote devuelve la alerta `HR_ABOVE_THRESHOLD_V1` → «Detener la práctica» → el fin se encola con
 * `reason = RISK_STOP` y el BFF lo confirma. Con `e2eFreeEmail`, un deportista `FREE` no ve la opción y
 * recibe `403 capability_required` al forzar el umbral.
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.lfnj.sport_app.e2e.Hu036IntegratedE2eTest \
 *   -Pandroid.testInstrumentationRunnerArguments.e2eIntermediateEmail=<correo INTERMEDIATE> \
 *   -Pandroid.testInstrumentationRunnerArguments.e2ePassword=<contraseña sintética> \
 *   [-Pandroid.testInstrumentationRunnerArguments.e2eFreeEmail=<correo FREE>]
 * ```
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class Hu036IntegratedE2eTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val args = InstrumentationRegistry.getArguments()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val defaultDependencies = ActiveSessionService.dependencies
    private var container: TestAppContainer? = null

    private val address: String = ByteArray(5).also(SecureRandom()::nextBytes).joinToString(":", prefix = "02:") { "%02X".format(it) }
    private val monitorName = "Banda simulada HU036 ${address.takeLast(5).replace(":", "")}"
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
        testContainer.heartRateSource.monitors = listOf(DiscoveredMonitor(address, monitorName))
        ActiveSessionService.dependencies = { ActiveSessionDependencies(testContainer.activeSessionTracker, testContainer.activeSessionTasks) }
        composeRule.setContent { SportappTheme { AppNavHost(testContainer, navController = rememberNavController()) } }
    }

    @After
    fun tearDown() {
        scope.cancel()
        context.stopService(Intent(context, ActiveSessionService::class.java))
        ActiveSessionService.dependencies = defaultDependencies
        container?.close()
    }

    private fun arg(key: String): String = requireNotNull(args.getString(key)) { "Falta el argumento $key" }

    private fun login(email: String) {
        composeRule.waitUntilExactlyOneExists(hasTestTag("login_email"), TIMEOUT)
        composeRule.onNodeWithTag("login_email").performTextInput(email)
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

    @Test
    fun umbralBajoProvocaLaAlertaYDetenerFinalizaConRiskStop() {
        login(arg("e2eIntermediateEmail"))
        val owner = TrainingOwner.keyOf(checkNotNull(app.sessionManager.currentUser.value).userId)
        val original = runBlocking { app.monitoringRepository.settings() }
        assumeTrue("E2E omitido: el BFF no publica el umbral ($original)", original is SettingsResult.Done)

        // 1. Vincular el monitor simulado (HU037, POST real).
        openMenu(DEVICES_MENU, "devices_screen")
        composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodes(hasTestTag("devices_search")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("devices_search").performScrollTo().performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("devices_found"), TIMEOUT)
        composeRule.onNodeWithTag("devices_found").performScrollTo().performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("devices_link_section"), TIMEOUT)
        composeRule.onNodeWithTag("devices_link_confirm").performScrollTo().performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("devices_notice_linked"), TIMEOUT)
        backHome("devices_back")

        // 2. Sesión real de HU032 y servicio en primer plano con la ingestión de HU037.
        openMenu(TRAINING_MENU, "training_session")
        composeRule.onNodeWithTag("sport_running").performClick()
        composeRule.onNodeWithTag("training_start").performScrollTo().performClick()
        val session = runBlocking { app.trainingSessionRepository.observe(owner).first { list -> list.any { it.open } } }.first { it.open }
        awaitSynced(owner, session.sessionId, finished = false)
        app.activeSessionTracker.start(scope)
        backHome("training_back")

        try {
            // 3. FC en vivo (fuente simulada declarada) y umbral bajo confirmado por el BFF.
            openMenu(MONITORING_MENU, "monitoring_screen")
            composeRule.onNodeWithTag("monitoring_simulated_source").assertIsDisplayed()
            composeRule.waitUntilExactlyOneExists(hasTestTag("monitoring_live_value"), TIMEOUT)
            composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodes(hasTestTag("monitoring_threshold_input")).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithTag("monitoring_threshold_input").performScrollTo().performTextClearance()
            composeRule.onNodeWithTag("monitoring_threshold_input").performTextInput("100")
            composeRule.onNodeWithTag("monitoring_threshold_save").performScrollTo().performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag("monitoring_threshold_saved"), TIMEOUT)

            // 4. CA2: ≥ 15 s sobre el umbral → alerta del backend con el aviso no diagnóstico.
            composeRule.waitUntilExactlyOneExists(hasTestTag("monitoring_alert"), ALERT_TIMEOUT)
            composeRule.onNodeWithTag("monitoring_alert_message").assertTextContains("diagnóstico médico", substring = true)
            assertEquals(1, app.recordingRiskAlertNotifier.posted.map { it.alertId }.distinct().size)

            // 5. CA4: detener la práctica; el fin se encola con RISK_STOP y el BFF lo confirma.
            composeRule.onNodeWithTag("monitoring_stop").performScrollTo().performClick()
            composeRule.onNodeWithTag("monitoring_stop_confirm").performScrollTo().performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag("monitoring_stopped"), TIMEOUT)
            val codec = TrainingRecordCodec(app.trainingCipher, BffMobileClient.json)
            val finish = runBlocking { app.database.trainingSessionDao().commandsCreatedBefore(Long.MAX_VALUE) }
                .filter { it.ownerKey == owner && it.sessionId == session.sessionId && it.type == CommandType.FINISH.name }
                .map { codec.decodeCommand(it) }
                .filterIsInstance<PendingCommand.Finish>()
                .single()
            assertEquals("RISK_STOP", finish.body.reason)
            awaitSynced(owner, session.sessionId, finished = true)
        } finally {
            // Restaura el umbral previo de la cuenta sintética.
            runBlocking { app.monitoringRepository.update((original as SettingsResult.Done).settings.maxHeartRateAlertBpm) }
        }
    }

    @Test
    fun deportistaFreeNoVeLaOpcionYRecibe403AlForzarla() {
        assumeTrue("E2E FREE omitido: falta e2eFreeEmail", args.getString("e2eFreeEmail") != null)
        login(arg("e2eFreeEmail"))
        composeRule.onNodeWithTag("open_menu").performClick()
        assertEquals(0, composeRule.onAllNodes(hasTestTag(MONITORING_MENU)).fetchSemanticsNodes().size)
        val forced = runBlocking { app.monitoringRepository.settings() }
        assertTrue("403 capability_required: $forced", forced == SettingsResult.Failed(com.lfnj.sport_app.features.training.monitoring.data.SettingsProblem.CAPABILITY_REQUIRED))
    }

    private companion object {
        const val TIMEOUT = 20_000L
        const val SYNC_TIMEOUT = 60_000L
        const val ALERT_TIMEOUT = 60_000L
        const val DEVICES_MENU = "menu_devices.measurement.connect"
        const val TRAINING_MENU = "menu_training.session.record"
        const val MONITORING_MENU = "menu_training.realtime.monitor"
    }
}
