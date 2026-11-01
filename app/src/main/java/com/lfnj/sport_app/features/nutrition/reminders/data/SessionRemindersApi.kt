package com.lfnj.sport_app.features.nutrition.reminders.data

import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderPreferences
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderRule
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderRuleset
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderSetting
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PUT

/** Reglas y preferencias de recordatorios durante la sesión en `bff-mobile` 0.18.0 (HU038, ADR-045 §4–§5). */
interface SessionRemindersApi {
    @GET("v1/session-reminder-rules")
    suspend fun rules(@Header("Authorization") authorization: String): Response<ReminderRulesResponseDto>

    @GET("v1/me/session-reminder-preferences")
    suspend fun preferences(@Header("Authorization") authorization: String): Response<ReminderPreferencesResponseDto>

    @PUT("v1/me/session-reminder-preferences")
    suspend fun updatePreferences(
        @Header("Authorization") authorization: String,
        @Body body: ReminderPreferencesRequestDto,
    ): Response<ReminderPreferencesResponseDto>
}

@Serializable
data class ReminderRulesResponseDto(
    @SerialName("ruleset_version") val rulesetVersion: String,
    val rules: List<ReminderRuleDto>,
)

@Serializable
data class ReminderRuleDto(
    val type: String,
    @SerialName("default_interval_minutes") val defaultIntervalMinutes: Int,
    val min: Int,
    val max: Int,
    @SerialName("applies_after_minutes") val appliesAfterMinutes: Int,
    val message: String,
    val disclaimer: String,
)

/** `HydrationSetting` y `NutritionSetting` comparten la forma `{enabled, interval_minutes}`. */
@Serializable
data class ReminderSettingDto(
    val enabled: Boolean,
    @SerialName("interval_minutes") val intervalMinutes: Int,
)

@Serializable
data class ReminderPreferencesResponseDto(
    val hydration: ReminderSettingDto,
    val nutrition: ReminderSettingDto,
    val version: Int,
)

/** Cuerpo del `PUT`: reemplaza ambas preferencias con la versión leída (`0` sin preferencias guardadas). */
@Serializable
data class ReminderPreferencesRequestDto(
    val hydration: ReminderSettingDto,
    val nutrition: ReminderSettingDto,
    @SerialName("expected_version") val expectedVersion: Int,
)

/** Error de contrato: la respuesta no corresponde a `bff-mobile` y no se guarda parcialmente. */
class InvalidSessionRemindersResponse : Exception()

/**
 * Ruleset confirmado. Un tipo desconocido, un tipo repetido, la falta de alguno de los dos tipos o
 * una regla incoherente invalidan toda la respuesta: nunca se completan reglas inventadas.
 */
fun ReminderRulesResponseDto.toRuleset(): ReminderRuleset {
    if (rulesetVersion.isBlank()) throw InvalidSessionRemindersResponse()
    val parsed = rules.map { dto ->
        val type = ReminderType.fromWire(dto.type) ?: throw InvalidSessionRemindersResponse()
        ReminderRule(type, dto.defaultIntervalMinutes, dto.min, dto.max, dto.appliesAfterMinutes, dto.message, dto.disclaimer)
            .takeIf { it.valid } ?: throw InvalidSessionRemindersResponse()
    }
    if (parsed.map { it.type }.toSet() != ReminderType.entries.toSet() || parsed.size != ReminderType.entries.size) {
        throw InvalidSessionRemindersResponse()
    }
    return ReminderRuleset(rulesetVersion, parsed)
}

fun ReminderPreferencesResponseDto.toPreferences(): ReminderPreferences {
    if (version < 0) throw InvalidSessionRemindersResponse()
    return ReminderPreferences(hydration.toSetting(), nutrition.toSetting(), version)
}

fun ReminderSetting.toDto() = ReminderSettingDto(enabled, intervalMinutes)

private fun ReminderSettingDto.toSetting() = ReminderSetting(enabled, intervalMinutes)
