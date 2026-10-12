package com.lfnj.sport_app.core.network

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET

/** Operaciones técnicas de salud del contrato `bff-mobile` (`/health/ready`). */
interface HealthApi {
    @GET("health/ready")
    suspend fun ready(): Response<HealthStatusDto>
}

/** `HealthStatus` del contrato `bff-mobile`. */
@Serializable
data class HealthStatusDto(
    val status: String,
    val checks: Map<String, String> = emptyMap(),
)
