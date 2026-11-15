package com.lfnj.sport_app.features.training.monitoring.model

import com.lfnj.sport_app.core.session.AccessSnapshot
import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.SubscriptionStatus
import com.lfnj.sport_app.features.sensors.model.MonitorConnection
import com.lfnj.sport_app.features.sensors.model.MonitorSnapshot
import com.lfnj.sport_app.features.sensors.model.REALTIME_MONITOR_CAPABILITY
import java.time.Duration
import java.time.Instant

/** Capacidad `INTERMEDIATE` de la opción 210 «Monitoreo en tiempo real» (HU036, ADR-044 §11). */
const val MONITORING_CAPABILITY = REALTIME_MONITOR_CAPABILITY

/** Única regla del MVP (ADR-044 §9). */
const val RULE_HR_ABOVE_THRESHOLD_V1 = "HR_ABOVE_THRESHOLD_V1"

/** La opción se muestra y se usa solo con la capacidad vigente (CA5); el BFF decide al final. */
object MonitoringAccess {
    fun canUse(access: AccessSnapshot?): Boolean =
        access != null &&
            access.actorType == ActorType.ATHLETE &&
            access.subscriptionStatus == SubscriptionStatus.ACTIVE &&
            MONITORING_CAPABILITY in access.capabilities
}

/** `EvaluationStatus` del contrato: `NO_DATA` es pérdida de monitoreo, no ausencia de riesgo (CA3). */
enum class EvaluationStatus {
    OK,
    RISK,
    NO_DATA;

    companion object {
        fun fromWire(value: String?): EvaluationStatus? = entries.firstOrNull { it.name == value }
    }
}

/** Alerta de riesgo devuelta por el backend; [message] es el texto fijo con el aviso no diagnóstico. */
data class RiskAlert(
    val alertId: String,
    val message: String,
    val instruction: String,
    val detectedAt: Instant,
) {
    override fun toString(): String = "RiskAlert(detectedAt=$detectedAt)"
}

/** Evaluación remota del último lote confirmado y el instante en que llegó al teléfono. */
data class RemoteEvaluation(val status: EvaluationStatus, val receivedAt: Instant)

/** Origen del umbral vigente (`ThresholdSource`). */
enum class ThresholdSource {
    /** Configurado por el deportista. */
    CUSTOM,
    /** `round(0.9 × (220 − edad))`, calculado por el backend. */
    AGE_DEFAULT,
    /** `171`, sin fecha de nacimiento. */
    FIXED_DEFAULT;

    companion object {
        fun fromWire(value: String?): ThresholdSource? = entries.firstOrNull { it.name == value }
    }
}

/** Umbral de alerta vigente (`MonitoringSettingsResponse`); [disclaimer] es el aviso R-21 del backend. */
data class MonitoringSettings(
    val maxHeartRateAlertBpm: Int,
    val source: ThresholdSource,
    val updatedAt: Instant?,
    val disclaimer: String,
)

/** Reglas del cliente que derivan de `MonitoringSettingsRequest` (100–220 lpm). */
object ThresholdRules {
    const val MIN_BPM = 100
    const val MAX_BPM = 220

    /** Valor entero dentro del rango, o `null`: la validación final la hace el servicio. */
    fun parse(raw: String): Int? = raw.trim().toIntOrNull()?.takeIf { it in MIN_BPM..MAX_BPM }
}

/**
 * Lectura en vivo dibujada con los datos locales del monitor (ADR-044 §7). Solo [Live] lleva el
 * valor actual; ningún otro estado presenta una lectura anterior como actual (CA3).
 */
sealed interface LiveReading {
    /** Aún no llega la primera muestra de la sesión. */
    data object Waiting : LiveReading

    data class Live(val bpm: Int, val measuredAt: Instant, val ageSeconds: Long) : LiveReading

    /** Desconectado hace ≤ 30 s: se reintenta la conexión sin mostrar el último valor. */
    data class Reconnecting(val lastMeasuredAt: Instant) : LiveReading

    /** Más de 30 s sin datos: pérdida de monitoreo (CA3). [lastMeasuredAt] es `null` si nunca hubo datos. */
    data class NoData(val lastMeasuredAt: Instant?) : LiveReading

    /** Error terminal de la conexión (sin servicio `0x180D` o sin permiso Bluetooth). */
    data class Unavailable(val connection: MonitorConnection) : LiveReading
}

object LiveReadingRules {
    /** ADR-044 §8: el móvil declara pérdida de monitoreo cuando el sensor deja de emitir 30 s. */
    val NO_DATA_AFTER: Duration = Duration.ofSeconds(30)

    private val TERMINAL = setOf(
        MonitorConnection.MISSING_HEART_RATE_SERVICE,
        MonitorConnection.PERMISSION_REQUIRED,
        MonitorConnection.UNAVAILABLE,
    )

    /**
     * Estado de la lectura en [now]. Sin ninguna muestra, la pérdida se cuenta desde [since] (inicio de
     * la sesión o del monitoreo); `null` no la declara.
     */
    fun of(snapshot: MonitorSnapshot?, now: Instant, since: Instant?): LiveReading {
        val connection = snapshot?.connection
        if (connection in TERMINAL) return LiveReading.Unavailable(checkNotNull(connection))
        val reading = snapshot?.lastReading
        if (reading == null) {
            val waitedTooLong = since != null && Duration.between(since, now) > NO_DATA_AFTER
            return if (waitedTooLong) LiveReading.NoData(null) else LiveReading.Waiting
        }
        val age = Duration.between(reading.receivedAt, now).let { if (it.isNegative) Duration.ZERO else it }
        return when {
            age > NO_DATA_AFTER -> LiveReading.NoData(reading.receivedAt)
            connection == MonitorConnection.CONNECTED -> LiveReading.Live(reading.bpm, reading.receivedAt, age.seconds)
            else -> LiveReading.Reconnecting(reading.receivedAt)
        }
    }
}
