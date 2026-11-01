package com.lfnj.sport_app.features.nutrition.reminders.data.local

import com.lfnj.sport_app.core.security.TokenCipher
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderConfig
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderPreferences
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderPresentation
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderRule
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderRuleset
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderSetting
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.time.DateTimeException
import java.time.Instant

/** Contenido cifrado de [SessionReminderConfigEntity.payload]. Instantes en segundos UTC. */
@Serializable
internal data class ReminderConfigPayload(
    @SerialName("ruleset_version") val rulesetVersion: String,
    val rules: List<CachedRule>,
    val hydration: CachedSetting,
    val nutrition: CachedSetting,
    @SerialName("preferences_version") val preferencesVersion: Int,
    @SerialName("last_synced_at") val lastSyncedAt: Long,
)

@Serializable
internal data class CachedRule(
    val type: String,
    @SerialName("default_interval_minutes") val defaultIntervalMinutes: Int,
    val min: Int,
    val max: Int,
    @SerialName("applies_after_minutes") val appliesAfterMinutes: Int,
    val message: String,
    val disclaimer: String,
)

@Serializable
internal data class CachedSetting(val enabled: Boolean, @SerialName("interval_minutes") val intervalMinutes: Int)

/** Contenido cifrado de [SessionReminderDeliveryEntity.payload]. */
@Serializable
internal data class DeliveryPayload(
    @SerialName("delivered_at_ms") val deliveredAtMs: Long,
    val presentation: String,
)

/** Entrega registrada en el dispositivo (evidencia local de CA2). */
data class ReminderDelivery(val type: ReminderType, val n: Int, val deliveredAt: Instant, val presentation: ReminderPresentation)

/**
 * Cifra y descifra la configuración y el registro de entregas de HU038 con AES-GCM y una clave no
 * exportable de Android Keystore propia. Un dato que no puede descifrarse se trata como inexistente:
 * sin configuración legible no hay recordatorios (CA5).
 */
class SessionRemindersCodec(private val cipher: TokenCipher, private val json: Json) {

    fun encodeConfig(config: ReminderConfig): ByteArray {
        val payload = ReminderConfigPayload(
            rulesetVersion = config.ruleset.version,
            rules = config.ruleset.rules.map {
                CachedRule(it.type.name, it.defaultIntervalMinutes, it.minIntervalMinutes, it.maxIntervalMinutes, it.appliesAfterMinutes, it.message, it.disclaimer)
            },
            hydration = config.preferences.hydration.cached(),
            nutrition = config.preferences.nutrition.cached(),
            preferencesVersion = config.preferences.version,
            lastSyncedAt = config.syncedAt.epochSecond,
        )
        return encrypt(json.encodeToString(ReminderConfigPayload.serializer(), payload))
    }

    /** `null` si la configuración no puede descifrarse o no corresponde al formato esperado. */
    fun decodeConfig(entity: SessionReminderConfigEntity): ReminderConfig? = readable {
        val payload = json.decodeFromString(ReminderConfigPayload.serializer(), decrypt(entity.payload))
        val rules = payload.rules.map {
            ReminderRule(checkNotNull(ReminderType.fromWire(it.type)), it.defaultIntervalMinutes, it.min, it.max, it.appliesAfterMinutes, it.message, it.disclaimer)
        }
        ReminderConfig(
            ruleset = ReminderRuleset(payload.rulesetVersion, rules),
            preferences = ReminderPreferences(payload.hydration.setting(), payload.nutrition.setting(), payload.preferencesVersion),
            syncedAt = Instant.ofEpochSecond(payload.lastSyncedAt),
        )
    }

    fun encodeDelivery(deliveredAt: Instant, presentation: ReminderPresentation): ByteArray =
        encrypt(json.encodeToString(DeliveryPayload.serializer(), DeliveryPayload(deliveredAt.toEpochMilli(), presentation.name)))

    /** `null` si la entrada no puede leerse; su clave sigue impidiendo un duplicado. */
    fun decodeDelivery(entity: SessionReminderDeliveryEntity): ReminderDelivery? = readable {
        val payload = json.decodeFromString(DeliveryPayload.serializer(), decrypt(entity.payload))
        val (type, n) = parseOccurrence(entity.occurrence) ?: error("ocurrencia ilegible")
        ReminderDelivery(type, n, Instant.ofEpochMilli(payload.deliveredAtMs), ReminderPresentation.valueOf(payload.presentation))
    }

    private fun encrypt(text: String): ByteArray = cipher.encrypt(text.toByteArray(Charsets.UTF_8))

    private fun decrypt(bytes: ByteArray): String = cipher.decrypt(bytes).toString(Charsets.UTF_8)

    private fun <T> readable(block: () -> T): T? = try {
        block()
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: SerializationException) {
        null
    } catch (_: DateTimeException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    private fun ReminderSetting.cached() = CachedSetting(enabled, intervalMinutes)

    private fun CachedSetting.setting() = ReminderSetting(enabled, intervalMinutes)

    companion object {
        /** Alias de la clave no exportable de Android Keystore de HU038. */
        const val KEY_ALIAS = "sportapp.session_reminders.v1"

        /** Clave técnica de la ocurrencia `(type, n)` dentro de una sesión. */
        fun occurrenceKey(type: ReminderType, n: Int): String = "${type.name}:$n"

        fun parseOccurrence(value: String): Pair<ReminderType, Int>? {
            val type = ReminderType.fromWire(value.substringBefore(':', "")) ?: return null
            val n = value.substringAfter(':', "").toIntOrNull()?.takeIf { it > 0 } ?: return null
            return type to n
        }
    }
}
