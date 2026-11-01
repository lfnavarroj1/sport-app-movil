package com.lfnj.sport_app.features.nutrition.reminders.engine

import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderRule
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderSetting
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderType

/** Ocurrencia `n` (desde 1) de un tipo, en el instante `n × intervalo` medido desde el inicio de la sesión. */
data class ReminderOccurrence(val type: ReminderType, val n: Int, val dueAtElapsedMillis: Long)

/**
 * Cálculo puro de ocurrencias (ADR-045 §4): cada tipo habilitado se repite cada
 * `interval_minutes` desde el inicio de la sesión y una ocurrencia solo existe cuando su instante
 * supera `applies_after_minutes` (`NUTRITION`: más de 60 min). Un intervalo fuera del rango de la
 * regla no genera ocurrencias: nunca se corrige con un valor inventado.
 */
object ReminderSchedule {
    private const val MINUTE_MILLIS = 60_000L

    /** Ocurrencia más reciente ya vencida en [elapsedMillis], o `null` si aún no hay ninguna. */
    fun latestDue(rule: ReminderRule, setting: ReminderSetting, elapsedMillis: Long): ReminderOccurrence? {
        val interval = intervalMillis(rule, setting) ?: return null
        val n = elapsedMillis / interval
        if (n < 1) return null
        val dueAt = n * interval
        if (dueAt <= rule.appliesAfterMinutes * MINUTE_MILLIS) return null
        return ReminderOccurrence(rule.type, n.toInt(), dueAt)
    }

    /** Instante de la siguiente ocurrencia posterior a [elapsedMillis], o `null` si el tipo no genera ninguna. */
    fun nextDueAt(rule: ReminderRule, setting: ReminderSetting, elapsedMillis: Long): Long? {
        val interval = intervalMillis(rule, setting) ?: return null
        val threshold = maxOf(elapsedMillis, rule.appliesAfterMinutes * MINUTE_MILLIS)
        return (threshold / interval + 1) * interval
    }

    /** Todas las ocurrencias de una sesión de [durationMillis]; la usan las pruebas y la evidencia. */
    fun occurrencesWithin(rule: ReminderRule, setting: ReminderSetting, durationMillis: Long): List<ReminderOccurrence> {
        val result = mutableListOf<ReminderOccurrence>()
        var next = nextDueAt(rule, setting, 0) ?: return result
        while (next <= durationMillis) {
            latestDue(rule, setting, next)?.let(result::add)
            next = nextDueAt(rule, setting, next) ?: break
        }
        return result
    }

    private fun intervalMillis(rule: ReminderRule, setting: ReminderSetting): Long? =
        if (setting.enabled && rule.accepts(setting.intervalMinutes)) setting.intervalMinutes * MINUTE_MILLIS else null
}
