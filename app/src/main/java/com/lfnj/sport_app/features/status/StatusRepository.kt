package com.lfnj.sport_app.features.status

import com.lfnj.sport_app.core.network.ApiResult
import com.lfnj.sport_app.core.network.HealthApi
import com.lfnj.sport_app.core.network.apiCall
import kotlinx.serialization.json.Json

/** Disponibilidad del BFF móvil vista desde el cliente. */
enum class BffAvailability { READY, UNAVAILABLE, NO_CONNECTION, NOT_CONFIGURED, UNEXPECTED }

fun interface StatusRepository {
    suspend fun bffAvailability(): BffAvailability
}

class HttpStatusRepository(
    private val api: HealthApi?,
    private val json: Json,
) : StatusRepository {
    override suspend fun bffAvailability(): BffAvailability {
        if (api == null) return BffAvailability.NOT_CONFIGURED
        return when (val result = apiCall(json) { api.ready() }) {
            is ApiResult.Success ->
                if (result.value.status == "ok") BffAvailability.READY else BffAvailability.UNAVAILABLE
            is ApiResult.HttpFailure ->
                if (result.status == 503) BffAvailability.UNAVAILABLE else BffAvailability.UNEXPECTED
            ApiResult.NoConnection, ApiResult.Timeout -> BffAvailability.NO_CONNECTION
            ApiResult.NotConfigured -> BffAvailability.NOT_CONFIGURED
            ApiResult.InvalidResponse -> BffAvailability.UNEXPECTED
        }
    }
}
