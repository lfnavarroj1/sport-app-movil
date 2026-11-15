package com.lfnj.sport_app.features.training.monitoring

import com.lfnj.sport_app.features.notifications.model.SystemNotificationsStatus
import com.lfnj.sport_app.features.sensors.data.EvaluationDto
import com.lfnj.sport_app.features.sensors.data.RiskAlertDto
import com.lfnj.sport_app.features.sensors.sync.EvaluationSink
import com.lfnj.sport_app.features.training.monitoring.model.EvaluationStatus
import com.lfnj.sport_app.features.training.monitoring.model.RULE_HR_ABOVE_THRESHOLD_V1
import com.lfnj.sport_app.features.training.monitoring.model.RemoteEvaluation
import com.lfnj.sport_app.features.training.monitoring.model.RiskAlert
import com.lfnj.sport_app.features.training.session.foreground.ActiveSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** Presenta una alerta de riesgo como notificación local de prioridad alta (canal `monitoring_alerts`). */
interface RiskAlertNotifier {
    fun status(): SystemNotificationsStatus

    /** `false` si el sistema no permite mostrarla (sin permiso o canal bloqueado). */
    fun notify(alert: RiskAlert): Boolean
}

/**
 * Evaluación y alertas de la sesión en curso (solo en memoria). [alerts] está ordenada de la más
 * reciente a la más antigua; [sessionId] identifica la sesión a la que pertenecen.
 */
data class RiskState(
    val sessionId: String? = null,
    val evaluation: RemoteEvaluation? = null,
    val alerts: List<RiskAlert> = emptyList(),
) {
    fun forSession(id: String?): RiskState = if (id != null && id == sessionId) this else RiskState()
}

/**
 * Recibe la evaluación de cada lote confirmado (HU036 CA2–CA3, ADR-044 §8–§9) desde la ingestión de
 * HU037 y la publica para la pantalla y la notificación. El backend decide el riesgo y limita la
 * repetición a una alerta cada 60 s; el cliente solo deduplica por `alert_id`, estable ante el
 * reenvío del mismo lote, para no notificar dos veces la misma alerta. No persiste nada.
 */
class RiskMonitor(private val notifier: RiskAlertNotifier) : EvaluationSink {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(RiskState())
    private val notified = mutableSetOf<String>()

    val state: StateFlow<RiskState> = _state.asStateFlow()

    override suspend fun onEvaluation(session: ActiveSession, evaluation: EvaluationDto, receivedAt: Instant) {
        val status = EvaluationStatus.fromWire(evaluation.status) ?: return
        val fresh = mutex.withLock {
            val current = _state.value.forSession(session.sessionId)
            if (current.sessionId == null) notified.clear()
            val incoming = evaluation.alerts.mapNotNull(::toAlert).filter { it.alertId !in notified }.distinctBy { it.alertId }
            notified += incoming.map { it.alertId }
            val alerts = (incoming.sortedByDescending { it.detectedAt } + current.alerts).take(MAX_ALERTS)
            _state.value = RiskState(session.sessionId, RemoteEvaluation(status, receivedAt), alerts)
            incoming
        }
        fresh.forEach { alert -> runCatching { notifier.notify(alert) } }
    }

    /** Alerta de la regla aprobada con datos válidos; otra cosa no se presenta. */
    private fun toAlert(dto: RiskAlertDto): RiskAlert? {
        if (dto.rule != RULE_HR_ABOVE_THRESHOLD_V1 || dto.alertId.isBlank()) return null
        val detectedAt = try {
            OffsetDateTime.parse(dto.detectedAt).toInstant()
        } catch (_: DateTimeParseException) {
            return null
        } catch (_: DateTimeException) {
            return null
        }
        return RiskAlert(dto.alertId, dto.message, dto.instruction, detectedAt)
    }

    companion object {
        /** Alertas recientes que conserva la pantalla de la sesión. */
        const val MAX_ALERTS = 10
    }
}
