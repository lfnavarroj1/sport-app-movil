package com.lfnj.sport_app.features.training.monitoring

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lfnj.sport_app.R
import com.lfnj.sport_app.features.notifications.model.SystemNotificationsStatus
import com.lfnj.sport_app.features.sensors.model.HeartRateReading
import com.lfnj.sport_app.features.sensors.model.IngestionState
import com.lfnj.sport_app.features.sensors.model.IngestionStatus
import com.lfnj.sport_app.features.sensors.model.MonitorConnection
import com.lfnj.sport_app.features.sensors.model.MonitorSnapshot
import com.lfnj.sport_app.features.training.monitoring.model.EvaluationStatus
import com.lfnj.sport_app.features.training.monitoring.model.MonitoringSettings
import com.lfnj.sport_app.features.training.monitoring.model.RemoteEvaluation
import com.lfnj.sport_app.features.training.monitoring.model.RiskAlert
import com.lfnj.sport_app.features.training.monitoring.model.ThresholdSource
import com.lfnj.sport_app.features.training.session.foreground.ActiveSession
import com.lfnj.sport_app.ui.theme.SportappTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

/** HU036: estados de «Monitoreo en tiempo real» con la pantalla Compose real y datos sintéticos. */
@RunWith(AndroidJUnit4::class)
class MonitoringScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = Instant.parse("2026-10-24T12:31:00Z")
    private val session = ActiveSession("owner", UUID.nameUUIDFromBytes("hu036-pantalla".toByteArray()).toString(), now.minusSeconds(600), null)
    private val settings = MonitoringSettings(171, ThresholdSource.FIXED_DEFAULT, null, "El umbral no es un criterio clínico ni un diagnóstico médico.")
    private val alert = RiskAlert(
        UUID.nameUUIDFromBytes("hu036-alerta-pantalla".toByteArray()).toString(),
        "Tu frecuencia cardiaca supera el límite que configuraste. Reduce la intensidad o detén la práctica. Esto no es un diagnóstico médico.",
        "Reduce la intensidad o detén la práctica.",
        now.minusSeconds(3),
    )

    private val clicks = mutableListOf<String>()

    private fun live(bpm: Int = 142, ageSeconds: Long = 2) = MonitoringUiState(
        capabilityRequired = false,
        simulatedSource = true,
        session = session,
        hasDevice = true,
        monitor = MonitorSnapshot(MonitorConnection.CONNECTED, batteryPercent = 76, lastReading = HeartRateReading(bpm, now.minusSeconds(ageSeconds))),
        monitoringSince = now.minusSeconds(120),
        now = now,
        threshold = ThresholdState(loading = false, settings = settings, draft = "171"),
    )

    private fun show(initial: MonitoringUiState): (MonitoringUiState) -> Unit {
        var state by mutableStateOf(initial)
        composeRule.setContent {
            SportappTheme {
                MonitoringScreen(
                    state = state,
                    onOpenSession = { clicks += "session" },
                    onOpenDevices = { clicks += "devices" },
                    onDraftChange = { clicks += "draft:$it" },
                    onSaveThreshold = { clicks += "save" },
                    onRetrySettings = { clicks += "retry" },
                    onAskStop = { clicks += "ask_stop" },
                    onConfirmStop = { clicks += "confirm_stop" },
                    onCancelStop = { clicks += "cancel_stop" },
                    onEnableNotifications = { clicks += "notifications" },
                    onBack = { clicks += "back" },
                )
            }
        }
        return { state = it }
    }

    @Test
    fun enVivoMuestraValorInstanteConexionYFuenteSimulada() {
        show(live())

        composeRule.onNodeWithTag("monitoring_simulated_source").assertIsDisplayed()
            .assertTextEquals(context.getString(R.string.monitoring_simulated_source))
        composeRule.onNodeWithTag("monitoring_live_value").assertTextEquals("142 lpm")
            .assertContentDescriptionEquals(context.getString(R.string.monitoring_live_value_description, 142))
        composeRule.onNodeWithTag("monitoring_live_measured_at").assertTextContains("hace 2 s", substring = true)
        composeRule.onNodeWithTag("monitoring_connection")
            .assertTextEquals(context.getString(R.string.monitoring_connection, context.getString(R.string.devices_connection_connected)))
        composeRule.onNodeWithTag("monitoring_battery").assertTextEquals(context.getString(R.string.devices_battery, 76))
        composeRule.onNodeWithTag("monitoring_remote").assertTextEquals(context.getString(R.string.monitoring_remote_waiting))
        composeRule.onNodeWithTag("monitoring_stop").performScrollTo().assertIsEnabled()
    }

    @Test
    fun sinDatosMasDe30sDeclaraPerdidaYNoMuestraElValor() {
        show(live(bpm = 150, ageSeconds = 31))

        composeRule.onNodeWithTag("monitoring_no_data").assertIsDisplayed().assertTextEquals(context.getString(R.string.monitoring_no_data))
        assertEquals(0, composeRule.onAllNodes(hasTestTag("monitoring_live_value")).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodes(hasText("150", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun noDataDelBackendSeDistingueDeAusenciaDeRiesgo() {
        show(live().copy(evaluation = RemoteEvaluation(EvaluationStatus.NO_DATA, now)))

        composeRule.onNodeWithTag("monitoring_remote").assertTextContains("pérdida de monitoreo, no ausencia de riesgo", substring = true)
    }

    @Test
    fun alertaMuestraMensajeInstruccionAvisoYPermiteDetener() {
        val update = show(live(bpm = 181).copy(evaluation = RemoteEvaluation(EvaluationStatus.RISK, now), alerts = listOf(alert)))

        composeRule.onNodeWithTag("monitoring_alert").assertIsDisplayed()
        composeRule.onNodeWithTag("monitoring_alert_message").assertTextContains("no es un diagnóstico médico", substring = true)
        composeRule.onNodeWithTag("monitoring_alert_instruction").assertTextEquals(alert.instruction)
        composeRule.onNodeWithTag("monitoring_remote").assertTextContains("riesgo detectado", substring = true)
        composeRule.onNodeWithTag("monitoring_disclaimer").performScrollTo().assertTextEquals(settings.disclaimer)

        composeRule.onNodeWithTag("monitoring_stop").performScrollTo().performClick()
        assertEquals(listOf("ask_stop"), clicks)

        update(live(bpm = 181).copy(alerts = listOf(alert), stop = StopPhase.CONFIRMING))
        composeRule.onNodeWithTag("monitoring_stop_confirmation").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("monitoring_stop_confirm").performClick()
        assertEquals("confirm_stop", clicks.last())

        update(live().copy(stop = StopPhase.STOPPING))
        composeRule.onNodeWithTag("monitoring_stopping").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodes(hasTestTag("monitoring_stop")).fetchSemanticsNodes().size)

        update(live().copy(session = null, stop = StopPhase.STOPPED))
        composeRule.onNodeWithTag("monitoring_stopped").assertTextEquals(context.getString(R.string.monitoring_stopped))
    }

    @Test
    fun sinRedSigueConDatosLocalesYDeclaraSinEvaluacionRemota() {
        show(live().copy(online = false, ingestion = IngestionStatus(IngestionState.PENDING_RETRY, pendingSamples = 12)))

        composeRule.onNodeWithTag("monitoring_offline").assertIsDisplayed()
        composeRule.onNodeWithTag("monitoring_live_value").assertTextEquals("142 lpm")
        composeRule.onNodeWithTag("monitoring_remote").assertTextEquals(context.getString(R.string.monitoring_remote_unavailable))
    }

    @Test
    fun sinCapacidadExplicaElPlanSinOtrosControles() {
        show(MonitoringUiState(capabilityRequired = true, simulatedSource = false, now = now))

        composeRule.onNodeWithTag("monitoring_capability_required").assertIsDisplayed()
            .assertTextEquals(context.getString(R.string.monitoring_capability_required))
        listOf("monitoring_live", "monitoring_stop", "monitoring_threshold").forEach { tag ->
            assertEquals(tag, 0, composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun sinSesionOSinMonitorOrientaAlDeportista() {
        val update = show(live().copy(session = null))
        composeRule.onNodeWithTag("monitoring_no_session").assertIsDisplayed()
        composeRule.onNodeWithTag("monitoring_open_session").performClick()
        assertEquals(listOf("session"), clicks)

        update(live().copy(hasDevice = false))
        composeRule.onNodeWithTag("monitoring_no_device").assertIsDisplayed()
        composeRule.onNodeWithTag("monitoring_open_devices").performClick()
        assertEquals("devices", clicks.last())
    }

    @Test
    fun umbralPorDefectoValidacionYReintento() {
        val update = show(live())
        composeRule.onNodeWithTag("monitoring_threshold_current").performScrollTo()
            .assertTextEquals(context.getString(R.string.monitoring_threshold_current, 171))
        composeRule.onNodeWithTag("monitoring_threshold_source").assertTextEquals(context.getString(R.string.monitoring_threshold_source_fixed))
        composeRule.onNodeWithTag("monitoring_threshold_save").performScrollTo().performClick()
        assertEquals("save", clicks.last())

        update(live().copy(threshold = ThresholdState(loading = false, settings = settings, draft = "99", invalidDraft = true)))
        composeRule.onNodeWithTag("monitoring_threshold_invalid").performScrollTo().assertIsDisplayed()

        update(live().copy(threshold = ThresholdState(loading = false, settings = settings, draft = "120", saving = true)))
        composeRule.onNodeWithTag("monitoring_threshold_save").performScrollTo().assertIsNotEnabled()

        update(live().copy(threshold = ThresholdState(loading = false, problem = com.lfnj.sport_app.features.training.monitoring.data.SettingsProblem.OFFLINE)))
        composeRule.onNodeWithTag("monitoring_threshold_problem").performScrollTo()
            .assertTextEquals(context.getString(R.string.monitoring_threshold_offline))
        composeRule.onNodeWithTag("monitoring_threshold_retry").performScrollTo().performClick()
        assertEquals("retry", clicks.last())
        // Sin umbral confirmado sigue visible el aviso no diagnóstico.
        composeRule.onNodeWithTag("monitoring_disclaimer").performScrollTo().assertTextEquals(context.getString(R.string.monitoring_disclaimer))
    }

    @Test
    fun notificacionesDesactivadasOfrecenActivarlas() {
        show(live().copy(notifications = SystemNotificationsStatus.PERMISSION_REQUIRED))

        composeRule.onNodeWithTag("monitoring_notifications_off").assertIsDisplayed()
        composeRule.onNodeWithTag("monitoring_notifications_enable").performClick()
        assertTrue("notifications" in clicks)
    }

    @Test
    fun monitorDesconectadoNoPresentaElUltimoValorComoActual() {
        show(live().copy(monitor = MonitorSnapshot(MonitorConnection.DISCONNECTED, lastReading = HeartRateReading(150, now.minusSeconds(8)))))

        composeRule.onNodeWithTag("monitoring_reconnecting").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodes(hasTestTag("monitoring_live_value")).fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("monitoring_connection")
            .assertTextEquals(context.getString(R.string.monitoring_connection, context.getString(R.string.devices_connection_disconnected)))
    }
}
