package com.lfnj.sport_app.features.health.model

import androidx.annotation.StringRes
import com.lfnj.sport_app.R
import com.lfnj.sport_app.features.training.session.model.TrainingOwner
import java.time.Duration
import java.time.Instant

/** Tipo de reporte de salud (ADR-037, HU042 §1): catálogo cerrado, sin texto libre. */
enum class HealthReportType(val wire: String, @param:StringRes val label: Int) {
    DISCOMFORT("DISCOMFORT", R.string.health_type_discomfort),
    INJURY("INJURY", R.string.health_type_injury),
    INCAPACITY("INCAPACITY", R.string.health_type_incapacity);

    companion object {
        fun fromWire(value: String?): HealthReportType? = entries.firstOrNull { it.wire == value }
    }
}

/** Zona corporal: las 14 zonas del catálogo de ADR-037. */
enum class BodyArea(val wire: String, @param:StringRes val label: Int) {
    HEAD("HEAD", R.string.health_area_head),
    NECK("NECK", R.string.health_area_neck),
    SHOULDER("SHOULDER", R.string.health_area_shoulder),
    ARM("ARM", R.string.health_area_arm),
    ELBOW("ELBOW", R.string.health_area_elbow),
    WRIST_HAND("WRIST_HAND", R.string.health_area_wrist_hand),
    CHEST("CHEST", R.string.health_area_chest),
    BACK("BACK", R.string.health_area_back),
    HIP("HIP", R.string.health_area_hip),
    THIGH("THIGH", R.string.health_area_thigh),
    KNEE("KNEE", R.string.health_area_knee),
    LOWER_LEG("LOWER_LEG", R.string.health_area_lower_leg),
    ANKLE_FOOT("ANKLE_FOOT", R.string.health_area_ankle_foot),
    OTHER("OTHER", R.string.health_area_other);

    companion object {
        fun fromWire(value: String?): BodyArea? = entries.firstOrNull { it.wire == value }
    }
}

/** Severidad declarada por el deportista; el móvil nunca la interpreta como diagnóstico (CA6). */
enum class HealthSeverity(val wire: String, @param:StringRes val label: Int) {
    LOW("LOW", R.string.health_severity_low),
    MODERATE("MODERATE", R.string.health_severity_moderate),
    HIGH("HIGH", R.string.health_severity_high);

    /** Con `HIGH` se muestra el texto de consulta profesional (ADR-037 HU042 §5). */
    val suggestsProfessional: Boolean get() = this == HIGH

    companion object {
        fun fromWire(value: String?): HealthSeverity? = entries.firstOrNull { it.wire == value }
    }
}

/** Datos de negocio de un reporte; en el dispositivo solo existen cifrados. */
data class HealthReportDetails(
    val type: HealthReportType,
    val bodyArea: BodyArea,
    val severity: HealthSeverity,
    val occurredAt: Instant,
    /** Sesión propia de HU032 asociada, si el deportista eligió una. */
    val sessionId: String?,
    val expectedRecoveryDays: Int?,
) {
    override fun toString(): String = "HealthReportDetails(<oculto>)"
}

/**
 * Estado de sincronización de un reporte (ADR-026 §2 y ADR-029 offline §1, reutilizado por ADR-037).
 * Solo [SYNCED] significa que el backend respondió `201` para ese `report_id`.
 */
enum class HealthSyncState { PENDING_SYNC, SYNCED, SYNC_FAILED }

/** Causa explicable de [HealthSyncState.SYNC_FAILED]; se guarda solo como código, sin datos. */
enum class HealthSyncFailure {
    /** `422 validation_error` (campo y causa en el reporte). */
    VALIDATION,
    /** `422 unsupported_consent_version` o `consent.version` ausente: la versión del consentimiento cambió. */
    CONSENT_VERSION,
    /** `404 training_session_not_found`: la sesión elegida no existe o no es propia. */
    SESSION_NOT_FOUND,
    /** `403 permission_required`: la cuenta no es de deportista. */
    PERMISSION_REQUIRED,
    /** `409 report_id_reused` o `idempotency_key_reused`: el servidor no confirmó este reporte. */
    CONFLICT,
    /** Vencido a los 30 días sin sincronizarse, o `422 too_old`. */
    EXPIRED,
    /** Otro rechazo del servicio que no se reintenta automáticamente. */
    REJECTED,
    /** La respuesta no corresponde al reporte enviado. */
    INVALID_RESPONSE,
    /** El registro cifrado no pudo leerse (clave del Keystore invalidada o dato alterado). */
    UNREADABLE;

    companion object {
        fun fromWire(value: String?): HealthSyncFailure? = entries.firstOrNull { it.name == value }
    }
}

/**
 * Reporte guardado en el dispositivo. [details] es `null` si el registro cifrado no pudo leerse;
 * entonces el estado es [HealthSyncState.SYNC_FAILED] con [HealthSyncFailure.UNREADABLE].
 */
data class LocalHealthReport(
    val reportId: String,
    val state: HealthSyncState,
    val failure: HealthSyncFailure?,
    val failureField: String?,
    val failureIssue: String?,
    val createdAt: Instant,
    val details: HealthReportDetails?,
    /** `reported_at` del backend; solo existe en [HealthSyncState.SYNCED]. */
    val reportedAt: Instant?,
) {
    /** Puede retirarse del dispositivo: nunca un reporte pendiente. */
    val dismissible: Boolean get() = state == HealthSyncState.SYNC_FAILED
}

/** Sesión propia confirmada (HU032) que se puede asociar al reporte. */
data class RecentTrainingSession(val sessionId: String, val startedAt: Instant)

enum class OccurredAtError { IN_FUTURE, TOO_OLD }

enum class RecoveryDaysError { NOT_INTEGER, OUT_OF_RANGE }

/**
 * Validaciones de experiencia del formulario (ADR-037 HU042 §1). El backend sigue siendo la
 * autoridad: un rechazo llega como `SYNC_FAILED` con su campo y causa.
 */
object HealthReportValidation {
    val MAX_FUTURE: Duration = Duration.ofMinutes(5)
    val MAX_AGE: Duration = Duration.ofDays(365)
    const val MAX_RECOVERY_DAYS = 365

    fun validateOccurredAt(occurredAt: Instant, now: Instant): OccurredAtError? = when {
        occurredAt.isAfter(now.plus(MAX_FUTURE)) -> OccurredAtError.IN_FUTURE
        occurredAt.isBefore(now.minus(MAX_AGE)) -> OccurredAtError.TOO_OLD
        else -> null
    }

    sealed interface Recovery {
        data class Valid(val days: Int?) : Recovery
        data class Invalid(val error: RecoveryDaysError) : Recovery
    }

    /** Días esperados de recuperación: opcional, entero entre 0 y 365. */
    fun parseRecoveryDays(text: String): Recovery {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Recovery.Valid(null)
        if (!trimmed.all(Char::isDigit)) return Recovery.Invalid(RecoveryDaysError.NOT_INTEGER)
        val days = trimmed.toIntOrNull() ?: return Recovery.Invalid(RecoveryDaysError.OUT_OF_RANGE)
        return if (days in 0..MAX_RECOVERY_DAYS) Recovery.Valid(days) else Recovery.Invalid(RecoveryDaysError.OUT_OF_RANGE)
    }
}

/** Partición local por la huella SHA-256 del `user_id`, la misma de HU032 (ADR-029 offline §4). */
object HealthOwner {
    fun keyOf(userId: String): String = TrainingOwner.keyOf(userId)
}
