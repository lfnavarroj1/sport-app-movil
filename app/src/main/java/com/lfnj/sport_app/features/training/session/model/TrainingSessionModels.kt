package com.lfnj.sport_app.features.training.session.model

import androidx.annotation.StringRes
import com.lfnj.sport_app.R
import java.security.MessageDigest
import java.time.Instant

/** Capacidad del plan que exige iniciar una sesión (HU032); finalizar la propia no la exige. */
const val TRAINING_SESSION_CAPABILITY = "training.session.record"

/** Catálogo de deportes de HU010 que acepta `sport` al iniciar una sesión (ADR-029 §2). */
enum class TrainingSport(val wire: String, @param:StringRes val label: Int) {
    RUNNING("running", R.string.sport_running),
    CYCLING("cycling", R.string.sport_cycling),
    SWIMMING("swimming", R.string.sport_swimming),
    STRENGTH("strength", R.string.sport_strength),
    OTHER("other", R.string.sport_other);

    companion object {
        /** `null` para un valor desconocido: nunca se asume otro deporte. */
        fun fromWire(value: String?): TrainingSport? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Estado de sincronización de un registro local (ADR-026 §2 y ADR-029, política offline §1).
 * Solo [SYNCED] significa que el backend aceptó la última acción registrada.
 */
enum class SyncState {
    /** La acción solo está guardada en el dispositivo y aún no se intentó enviar. */
    LOCAL,

    /** Se está enviando o espera reintento por falta de red o indisponibilidad (`502`/`503`/`504`). */
    PENDING_SYNC,

    /** El backend confirmó la última acción registrada en el dispositivo. */
    SYNCED,

    /** El backend rechazó la cadena o venció sin sincronizarse: no forma parte del historial oficial. */
    SYNC_FAILED,
}

/** Causa explicable de [SyncState.SYNC_FAILED]. Se guarda solo como código técnico, sin datos. */
enum class SyncFailure {
    /** `409 active_session_exists`: otra sesión activa (p. ej. desde otro dispositivo, ADR-029 offline §7). */
    ACTIVE_SESSION_EXISTS,
    /** `403 capability_required`: el plan no incluye `training.session.record`. */
    CAPABILITY_REQUIRED,
    /** `403 permission_required`: la cuenta no es de deportista. */
    PERMISSION_REQUIRED,
    /** `404 training_plan_not_found` (CA9). */
    PLAN_NOT_FOUND,
    /** `404 training_session_not_found` (CA3). */
    SESSION_NOT_FOUND,
    /** `409 training_session_not_active` o `training_session_already_finished` (CA3). */
    SESSION_NOT_ACTIVE,
    /** `409 session_id_reused` o `idempotency_key_reused` (CA4). */
    CONFLICT,
    /** `422` por `too_far_in_future` o `ends_before_starts`: la hora del dispositivo no es válida. */
    DEVICE_CLOCK,
    /** Vencimiento local a los 30 días o `422 too_old` (ADR-029, política offline §6). */
    EXPIRED,
    /** Otro rechazo del servicio que no se reintenta automáticamente. */
    REJECTED,
    /** La respuesta no corresponde a la sesión enviada. */
    INVALID_RESPONSE,
    /** El registro cifrado no pudo leerse (clave del Keystore invalidada o dato alterado). */
    UNREADABLE;

    companion object {
        fun fromWire(value: String?): SyncFailure? = entries.firstOrNull { it.name == value }

        /** Traduce un rechazo funcional (`4xx` o `5xx` no reintentable) del BFF móvil por su `code`. */
        fun fromHttp(code: String?, issues: List<String>): SyncFailure = when (code) {
            "active_session_exists" -> ACTIVE_SESSION_EXISTS
            "capability_required" -> CAPABILITY_REQUIRED
            "permission_required" -> PERMISSION_REQUIRED
            "training_plan_not_found" -> PLAN_NOT_FOUND
            "training_session_not_found" -> SESSION_NOT_FOUND
            "training_session_not_active", "training_session_already_finished" -> SESSION_NOT_ACTIVE
            "session_id_reused", "idempotency_key_reused" -> CONFLICT
            "validation_error" -> when {
                "too_old" in issues -> EXPIRED
                "too_far_in_future" in issues || "ends_before_starts" in issues -> DEVICE_CLOCK
                else -> REJECTED
            }
            else -> REJECTED
        }
    }
}

/** Datos de negocio de una sesión; en el dispositivo solo existen cifrados. */
data class TrainingSessionDetails(
    val sport: TrainingSport,
    val planId: String?,
    val startedAt: Instant,
    val endedAt: Instant?,
    /** Duración oficial calculada por el backend al confirmar el fin (`duration_seconds`). */
    val officialDurationSeconds: Long?,
) {
    val finished: Boolean get() = endedAt != null

    /** Duración informativa medida en el dispositivo; la oficial la decide el backend (ADR-029 §6). */
    val localDurationSeconds: Long? get() = endedAt?.let { (it.epochSecond - startedAt.epochSecond).coerceAtLeast(0) }

    override fun toString(): String = "TrainingSessionDetails(finished=$finished)"
}

/**
 * Sesión registrada en el dispositivo para un deportista. [details] es `null` si el registro
 * cifrado no pudo leerse; entonces el estado es [SyncState.SYNC_FAILED] con [SyncFailure.UNREADABLE].
 */
data class LocalTrainingSession(
    val sessionId: String,
    val syncState: SyncState,
    val failure: SyncFailure?,
    val details: TrainingSessionDetails?,
) {
    /** Sesión iniciada, sin fin registrado y no rechazada: admite «Finalizar». */
    val open: Boolean get() = details != null && !details.finished && syncState != SyncState.SYNC_FAILED

    /** Se puede retirar del dispositivo sin perder una acción pendiente. */
    val dismissible: Boolean
        get() = syncState == SyncState.SYNC_FAILED || (syncState == SyncState.SYNCED && details?.finished == true)
}

/**
 * Partición local por identidad técnica (ADR-029 offline §4): huella SHA-256 del `user_id`,
 * para no guardar el identificador del deportista en claro junto a sus registros.
 */
object TrainingOwner {
    fun keyOf(userId: String): String =
        MessageDigest.getInstance("SHA-256").digest(userId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
