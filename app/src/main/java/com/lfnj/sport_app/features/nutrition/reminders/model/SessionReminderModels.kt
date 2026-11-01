package com.lfnj.sport_app.features.nutrition.reminders.model

import com.lfnj.sport_app.core.session.AccessSnapshot
import com.lfnj.sport_app.core.session.SubscriptionStatus
import java.time.Instant

/** Capacidad `INTERMEDIATE` de «Recordatorios durante sesión» (HU038, ADR-045 §5). */
const val SESSION_REMINDERS_CAPABILITY = "nutrition.session.reminders"

/** Guarda visual: solo un deportista con suscripción activa y la capacidad vigente. El BFF decide. */
object SessionRemindersAccess {
    fun canUse(access: AccessSnapshot?): Boolean =
        access != null && access.subscriptionStatus == SubscriptionStatus.ACTIVE && SESSION_REMINDERS_CAPABILITY in access.capabilities

    /**
     * El motor local solo se detiene cuando las capacidades conocidas excluyen la de HU038; sin
     * capacidades cargadas (p. ej. tras reiniciar el proceso sin red) decide la caché confirmada.
     */
    fun allowsLocalEvaluation(access: AccessSnapshot?): Boolean = access == null || canUse(access)
}

/** Tipo de recordatorio del ruleset (`ReminderType`). */
enum class ReminderType {
    HYDRATION,
    NUTRITION;

    companion object {
        fun fromWire(value: String?): ReminderType? = entries.firstOrNull { it.name == value }
    }
}

/**
 * Regla del ruleset tal como la confirmó el BFF. [message] y [disclaimer] son textos aprobados
 * que el cliente muestra sin modificar; la regla no prescribe cantidades.
 */
data class ReminderRule(
    val type: ReminderType,
    val defaultIntervalMinutes: Int,
    val minIntervalMinutes: Int,
    val maxIntervalMinutes: Int,
    /** La regla aplica solo cuando la sesión supera esta duración (`0`: desde el inicio). */
    val appliesAfterMinutes: Int,
    val message: String,
    val disclaimer: String,
) {
    fun accepts(intervalMinutes: Int): Boolean = intervalMinutes in minIntervalMinutes..maxIntervalMinutes

    /** Coherencia mínima de una regla recibida: rango positivo que contiene el valor por defecto. */
    val valid: Boolean
        get() = minIntervalMinutes > 0 && minIntervalMinutes <= maxIntervalMinutes && accepts(defaultIntervalMinutes) &&
            appliesAfterMinutes >= 0 && message.isNotBlank()
}

/** Ruleset versionado e inmutable por versión (`session-reminders-2026-10-13.v1`). */
data class ReminderRuleset(val version: String, val rules: List<ReminderRule>) {
    fun rule(type: ReminderType): ReminderRule? = rules.firstOrNull { it.type == type }
}

data class ReminderSetting(val enabled: Boolean, val intervalMinutes: Int)

/** Preferencias confirmadas del deportista; `version = 0` son los valores por defecto del ruleset. */
data class ReminderPreferences(val hydration: ReminderSetting, val nutrition: ReminderSetting, val version: Int) {
    fun setting(type: ReminderType): ReminderSetting = when (type) {
        ReminderType.HYDRATION -> hydration
        ReminderType.NUTRITION -> nutrition
    }
}

/**
 * Última configuración confirmada por el BFF y guardada cifrada en el dispositivo: el motor la
 * evalúa sin red. Sin ella nunca se genera un recordatorio (CA5).
 */
data class ReminderConfig(val ruleset: ReminderRuleset, val preferences: ReminderPreferences, val syncedAt: Instant) {
    override fun toString(): String = "ReminderConfig(version=${ruleset.version}, preferences=${preferences.version})"
}

/** Cómo se presentó un recordatorio: con notificación del sistema y banner, o solo banner (CA4). */
enum class ReminderPresentation { NOTIFICATION_AND_BANNER, BANNER_ONLY }

/** Recordatorio generado para la ocurrencia `(session_id, type, n)` de la sesión activa. */
data class SessionReminder(
    val sessionId: String,
    val type: ReminderType,
    val n: Int,
    /** Momento de la ocurrencia, medido desde el inicio de la sesión. */
    val dueAtElapsedMillis: Long,
    val message: String,
    val disclaimer: String,
)
