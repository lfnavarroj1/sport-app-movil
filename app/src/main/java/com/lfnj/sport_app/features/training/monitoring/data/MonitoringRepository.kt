package com.lfnj.sport_app.features.training.monitoring.data

import com.lfnj.sport_app.core.network.ApiResult
import com.lfnj.sport_app.core.network.apiCall
import com.lfnj.sport_app.core.session.AuthorizedResult
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.features.training.monitoring.model.MonitoringSettings
import com.lfnj.sport_app.features.training.monitoring.model.ThresholdRules
import com.lfnj.sport_app.features.training.monitoring.model.ThresholdSource
import kotlinx.serialization.json.Json
import java.time.DateTimeException
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** Causa explicable de una consulta o cambio de umbral que no se completó. */
enum class SettingsProblem {
    OFFLINE,
    UNAVAILABLE,
    /** `403 capability_required` o `permission_required` (CA5). */
    CAPABILITY_REQUIRED,
    /** `422 validation_error`: el valor está fuera de 100–220. */
    OUT_OF_RANGE,
    /** La respuesta no corresponde al contrato. */
    INVALID,
    SIGNED_OUT,
}

sealed interface SettingsResult {
    data class Done(val settings: MonitoringSettings) : SettingsResult
    data class Failed(val problem: SettingsProblem) : SettingsResult
}

/** Operaciones del umbral para la pantalla de monitoreo; las implementa [MonitoringRepository]. */
interface MonitoringGateway {
    suspend fun settings(): SettingsResult
    suspend fun update(bpm: Int): SettingsResult
}

/**
 * Umbral de alerta (HU036, ADR-044 §9). No se guarda en el teléfono: se consulta al abrir la pantalla
 * y solo se muestra como vigente lo que confirma el BFF. El valor por defecto lo calcula el backend.
 */
class MonitoringRepository(
    private val api: MonitoringApi?,
    private val sessionManager: SessionManager,
    private val json: Json,
) : MonitoringGateway {

    override suspend fun settings(): SettingsResult = call { auth, api -> api.settings(auth) }

    override suspend fun update(bpm: Int): SettingsResult {
        if (bpm !in ThresholdRules.MIN_BPM..ThresholdRules.MAX_BPM) return SettingsResult.Failed(SettingsProblem.OUT_OF_RANGE)
        return call { auth, api -> api.update(auth, MonitoringSettingsRequestDto(bpm)) }
    }

    private suspend fun call(block: suspend (String, MonitoringApi) -> retrofit2.Response<MonitoringSettingsDto>): SettingsResult {
        val api = api ?: return SettingsResult.Failed(SettingsProblem.UNAVAILABLE)
        return when (val outcome = sessionManager.authorized { auth -> apiCall(json) { block(auth, api) } }) {
            AuthorizedResult.SignedOut -> SettingsResult.Failed(SettingsProblem.SIGNED_OUT)
            AuthorizedResult.RenewalUnavailable -> SettingsResult.Failed(SettingsProblem.UNAVAILABLE)
            is AuthorizedResult.Completed -> when (val result = outcome.result) {
                is ApiResult.Success -> result.value.toModel()?.let(SettingsResult::Done) ?: SettingsResult.Failed(SettingsProblem.INVALID)
                ApiResult.NoConnection, ApiResult.Timeout -> SettingsResult.Failed(SettingsProblem.OFFLINE)
                ApiResult.InvalidResponse -> SettingsResult.Failed(SettingsProblem.INVALID)
                ApiResult.NotConfigured -> SettingsResult.Failed(SettingsProblem.UNAVAILABLE)
                is ApiResult.HttpFailure -> SettingsResult.Failed(
                    when {
                        result.status == 403 -> SettingsProblem.CAPABILITY_REQUIRED
                        result.status == 422 -> SettingsProblem.OUT_OF_RANGE
                        else -> SettingsProblem.UNAVAILABLE
                    }
                )
            }
        }
    }
}

/** `null` si la respuesta no corresponde al contrato: nunca se completa un umbral inventado. */
internal fun MonitoringSettingsDto.toModel(): MonitoringSettings? {
    val source = ThresholdSource.fromWire(source) ?: return null
    if (maxHeartRateAlertBpm !in ThresholdRules.MIN_BPM..ThresholdRules.MAX_BPM) return null
    val updated = try {
        updatedAt?.let { OffsetDateTime.parse(it).toInstant() }
    } catch (_: DateTimeParseException) {
        return null
    } catch (_: DateTimeException) {
        return null
    }
    return MonitoringSettings(maxHeartRateAlertBpm, source, updated, disclaimer)
}
