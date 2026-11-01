package com.lfnj.sport_app.testing

import com.lfnj.sport_app.features.notifications.model.SystemNotificationsStatus
import com.lfnj.sport_app.features.nutrition.reminders.data.local.SessionReminderConfigEntity
import com.lfnj.sport_app.features.nutrition.reminders.data.local.SessionReminderDeliveryEntity
import com.lfnj.sport_app.features.nutrition.reminders.data.local.SessionRemindersDao
import com.lfnj.sport_app.features.nutrition.reminders.engine.ReminderNotifier
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderConfig
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderPreferences
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderRule
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderRuleset
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderSetting
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderType
import com.lfnj.sport_app.features.nutrition.reminders.model.SessionReminder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant

/** Ruleset `session-reminders-2026-10-13.v1` y preferencias conforme a `bff-mobile` 0.18.0 (HU038). */
object SessionRemindersFixtures {
    const val RULESET_VERSION = "session-reminders-2026-10-13.v1"
    const val HYDRATION_MESSAGE = "Hidrátate: bebe agua o bebida isotónica"
    const val NUTRITION_MESSAGE = "Consume un alimento ligero según tu plan"
    const val DISCLAIMER = "Recordatorio general: no es un diagnóstico médico ni prescribe cantidades."
    const val SESSION_ID = "5b4a3c2d-1e0f-4a9b-8c7d-6e5f4a3b2c1d"

    val HYDRATION = ReminderRule(ReminderType.HYDRATION, 15, 10, 60, 0, HYDRATION_MESSAGE, DISCLAIMER)
    val NUTRITION = ReminderRule(ReminderType.NUTRITION, 45, 30, 120, 60, NUTRITION_MESSAGE, DISCLAIMER)
    val RULESET = ReminderRuleset(RULESET_VERSION, listOf(HYDRATION, NUTRITION))
    val DEFAULTS = ReminderPreferences(ReminderSetting(true, 15), ReminderSetting(true, 45), 0)

    fun config(preferences: ReminderPreferences = DEFAULTS, syncedAt: Instant = Instant.parse("2026-10-26T12:00:00Z")) =
        ReminderConfig(RULESET, preferences, syncedAt)

    fun rule(type: String, default: Int, min: Int, max: Int, after: Int, message: String) =
        """{"type":"$type","default_interval_minutes":$default,"min":$min,"max":$max,"applies_after_minutes":$after,""" +
            """"message":"$message","disclaimer":"$DISCLAIMER"}"""

    fun rulesBody(
        rules: List<String> = listOf(
            rule("HYDRATION", 15, 10, 60, 0, HYDRATION_MESSAGE),
            rule("NUTRITION", 45, 30, 120, 60, NUTRITION_MESSAGE),
        ),
        version: String = RULESET_VERSION,
    ) = """{"ruleset_version":"$version","rules":${rules.joinToString(",", "[", "]")}}"""

    fun preferencesBody(
        hydrationEnabled: Boolean = true,
        hydrationInterval: Int = 15,
        nutritionEnabled: Boolean = true,
        nutritionInterval: Int = 45,
        version: Int = 0,
    ) = """{"hydration":{"enabled":$hydrationEnabled,"interval_minutes":$hydrationInterval},""" +
        """"nutrition":{"enabled":$nutritionEnabled,"interval_minutes":$nutritionInterval},"version":$version}"""

    fun versionConflict(current: Int) =
        """{"code":"version_conflict","message":"mensaje","correlation_id":"8c1f2d3e-0000-4000-8000-000000000038",""" +
            """"details":[{"field":"expected_version","issue":"$current"}]}"""

    fun validationError(field: String = "hydration.interval_minutes") =
        """{"code":"validation_error","message":"mensaje","correlation_id":"8c1f2d3e-0000-4000-8000-000000000039",""" +
            """"details":[{"field":"$field","issue":"out_of_range"}]}"""
}

/** DAO en memoria con la misma semántica que Room (inserción única por ocurrencia). */
class InMemorySessionRemindersDao : SessionRemindersDao {
    val configs = MutableStateFlow<Map<String, SessionReminderConfigEntity>>(emptyMap())
    val deliveries = linkedMapOf<Triple<String, String, String>, SessionReminderDeliveryEntity>()
    var failWrites = false

    override fun observeConfig(owner: String): Flow<SessionReminderConfigEntity?> = configs.map { it[owner] }

    override suspend fun config(owner: String): SessionReminderConfigEntity? = configs.value[owner]

    override suspend fun replaceConfig(entry: SessionReminderConfigEntity) {
        check(!failWrites) { "disco lleno" }
        configs.value = configs.value + (entry.ownerKey to entry)
    }

    override suspend fun deleteConfig(owner: String) {
        configs.value = configs.value - owner
    }

    override suspend fun claim(delivery: SessionReminderDeliveryEntity): Long {
        check(!failWrites) { "disco lleno" }
        val key = Triple(delivery.ownerKey, delivery.sessionId, delivery.occurrence)
        if (key in deliveries) return -1
        deliveries[key] = delivery
        return deliveries.size.toLong()
    }

    override suspend fun deliveries(owner: String, sessionId: String): List<SessionReminderDeliveryEntity> =
        deliveries.values.filter { it.ownerKey == owner && it.sessionId == sessionId }.sortedBy { it.occurrence }

    override suspend fun deleteDeliveriesExceptSession(owner: String, sessionId: String) {
        deliveries.keys.removeAll { it.first == owner && it.second != sessionId }
    }

    override suspend fun deleteDeliveries(owner: String) {
        deliveries.keys.removeAll { it.first == owner }
    }

    override suspend fun deleteConfigsExcept(owner: String) {
        configs.value = configs.value.filterKeys { it == owner }
    }

    override suspend fun deleteDeliveriesExcept(owner: String) {
        deliveries.keys.removeAll { it.first != owner }
    }
}

/** Notificaciones del sistema simuladas: registra lo publicado sin Android. */
class RecordingReminderNotifier(var status: SystemNotificationsStatus = SystemNotificationsStatus.ENABLED) : ReminderNotifier {
    val posted = mutableListOf<SessionReminder>()

    override fun status(): SystemNotificationsStatus = status

    override fun notify(reminder: SessionReminder): Boolean {
        if (status != SystemNotificationsStatus.ENABLED) return false
        posted += reminder
        return true
    }
}
