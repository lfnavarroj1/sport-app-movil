package com.lfnj.sport_app.features.training.monitoring.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PUT

/** Umbral de alerta de frecuencia cardiaca en `bff-mobile` 0.21.0 (HU036, ADR-044 §9). */
interface MonitoringApi {
    /** Umbral vigente: configurado o el valor por defecto que calcula el backend. */
    @GET("v1/me/monitoring-settings")
    suspend fun settings(@Header("Authorization") authorization: String): Response<MonitoringSettingsDto>

    /** Reemplaza el umbral propio (100–220 lpm); repetir el mismo valor no cambia nada. */
    @PUT("v1/me/monitoring-settings")
    suspend fun update(
        @Header("Authorization") authorization: String,
        @Body body: MonitoringSettingsRequestDto,
    ): Response<MonitoringSettingsDto>
}

/** `MonitoringSettingsRequest`. */
@Serializable
data class MonitoringSettingsRequestDto(
    @SerialName("max_heart_rate_alert_bpm") val maxHeartRateAlertBpm: Int,
) {
    override fun toString(): String = "MonitoringSettingsRequestDto(<oculto>)"
}

/** `MonitoringSettingsResponse`. */
@Serializable
data class MonitoringSettingsDto(
    @SerialName("max_heart_rate_alert_bpm") val maxHeartRateAlertBpm: Int,
    val source: String,
    @SerialName("updated_at") val updatedAt: String?,
    val disclaimer: String,
) {
    override fun toString(): String = "MonitoringSettingsDto(source=$source)"
}
