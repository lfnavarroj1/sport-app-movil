package com.lfnj.sport_app.testing

import com.lfnj.sport_app.features.events.data.ConnectivityMonitor
import com.lfnj.sport_app.features.notifications.model.SystemNotificationsStatus
import com.lfnj.sport_app.features.sensors.data.EvaluationDto
import com.lfnj.sport_app.features.sensors.data.RiskAlertDto
import com.lfnj.sport_app.features.training.monitoring.PracticeStopper
import com.lfnj.sport_app.features.training.monitoring.RiskAlertNotifier
import com.lfnj.sport_app.features.training.monitoring.data.MonitoringGateway
import com.lfnj.sport_app.features.training.monitoring.data.SettingsResult
import com.lfnj.sport_app.features.training.monitoring.model.MonitoringSettings
import com.lfnj.sport_app.features.training.monitoring.model.RiskAlert
import com.lfnj.sport_app.features.training.monitoring.model.ThresholdSource
import com.lfnj.sport_app.features.training.session.data.FinishResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.util.UUID

/** Datos sintéticos de HU036 según `bff-mobile` 0.21.0. */
object MonitoringFixtures {
    const val MESSAGE = "Tu frecuencia cardiaca supera el límite que configuraste. Reduce la intensidad o detén la práctica. " +
        "Esto no es un diagnóstico médico."
    const val INSTRUCTION = "Reduce la intensidad o detén la práctica."
    const val DISCLAIMER = "El umbral no es un criterio clínico ni un diagnóstico médico."

    fun alertId(n: Int = 1): String = UUID.nameUUIDFromBytes("hu036-alerta-$n".toByteArray()).toString()

    fun alertDto(n: Int = 1, detectedAt: String = "2026-10-24T12:31:15Z", rule: String = "HR_ABOVE_THRESHOLD_V1") =
        RiskAlertDto(alertId(n), rule, "HIGH", MESSAGE, INSTRUCTION, detectedAt)

    fun evaluation(status: String = "OK", vararg alerts: RiskAlertDto) = EvaluationDto(status, alerts.toList())

    fun alertJson(n: Int = 1, detectedAt: String = "2026-10-24T12:31:15Z"): String =
        """{"alert_id":"${alertId(n)}","rule":"HR_ABOVE_THRESHOLD_V1","severity":"HIGH","message":"$MESSAGE",""" +
            """"instruction":"$INSTRUCTION","detected_at":"$detectedAt"}"""

    fun evaluationJson(status: String, vararg alerts: String): String = """{"status":"$status","alerts":${alerts.joinToString(",", "[", "]")}}"""

    fun settingsBody(bpm: Int = 171, source: String = "FIXED_DEFAULT", updatedAt: String? = null): String {
        val updated = updatedAt?.let { "\"$it\"" } ?: "null"
        return """{"max_heart_rate_alert_bpm":$bpm,"source":"$source","updated_at":$updated,"disclaimer":"$DISCLAIMER"}"""
    }

    fun settings(bpm: Int = 171, source: ThresholdSource = ThresholdSource.FIXED_DEFAULT, updatedAt: Instant? = null) =
        MonitoringSettings(bpm, source, updatedAt, DISCLAIMER)
}

class FakeRiskAlertNotifier(var status: SystemNotificationsStatus = SystemNotificationsStatus.ENABLED) : RiskAlertNotifier {
    val notified = mutableListOf<RiskAlert>()

    override fun status(): SystemNotificationsStatus = status

    override fun notify(alert: RiskAlert): Boolean {
        notified += alert
        return status == SystemNotificationsStatus.ENABLED
    }
}

/** Umbral en memoria: responde lo programado o el valor fijo por defecto. */
class FakeMonitoringGateway : MonitoringGateway {
    val settingsResults = ArrayDeque<SettingsResult>()
    val updateResults = ArrayDeque<SettingsResult>()
    val updates = mutableListOf<Int>()
    var settingsCalls = 0

    override suspend fun settings(): SettingsResult {
        settingsCalls++
        return settingsResults.removeFirstOrNull() ?: SettingsResult.Done(MonitoringFixtures.settings())
    }

    override suspend fun update(bpm: Int): SettingsResult {
        updates += bpm
        return updateResults.removeFirstOrNull() ?: SettingsResult.Done(MonitoringFixtures.settings(bpm, ThresholdSource.CUSTOM, Instant.parse("2026-10-24T12:00:00Z")))
    }
}

/** Registra cada detención; [gate] permite retener la respuesta para probar toques repetidos. */
class FakePracticeStopper(var result: FinishResult = FinishResult.FINISHED) : PracticeStopper {
    val stops = mutableListOf<Pair<String, String>>()
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun stop(owner: String, sessionId: String): FinishResult {
        stops += owner to sessionId
        gate?.await()
        return result
    }
}

class FakeConnectivity(online: Boolean = true) : ConnectivityMonitor {
    val state = MutableStateFlow(online)

    override fun online(): Flow<Boolean> = state
}
