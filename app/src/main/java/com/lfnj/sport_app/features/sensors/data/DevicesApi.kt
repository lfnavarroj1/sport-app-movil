package com.lfnj.sport_app.features.sensors.data

import com.lfnj.sport_app.features.sensors.model.DeviceStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** Dispositivos de medición e ingestión de mediciones en `bff-mobile` 0.21.0 (HU037, ADR-044). */
interface DevicesApi {
    /** Dispositivos propios `ACTIVE` y `REVOKED`, `created_at DESC`. */
    @GET("v1/me/devices")
    suspend fun list(@Header("Authorization") authorization: String): Response<DevicesResponseDto>

    /** Vincula o reactiva; `201` nuevo o repetición de la misma clave, `200` ya activo con el mismo nombre. */
    @POST("v1/me/devices")
    suspend fun register(
        @Header("Authorization") authorization: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: RegisterDeviceRequestDto,
    ): Response<DeviceDto>

    /** Revocación idempotente: el dispositivo pasa a `REVOKED` y deja de aceptarse su ingestión (CA6). */
    @DELETE("v1/me/devices/{device_id}")
    suspend fun revoke(
        @Header("Authorization") authorization: String,
        @Path("device_id") deviceId: String,
    ): Response<DeviceDto>

    /** Lote de 1 a 60 muestras de la sesión activa; idempotencia natural por `(session_id, device_id, measured_at)`. */
    @POST("v1/training-sessions/{session_id}/measurements")
    suspend fun ingest(
        @Header("Authorization") authorization: String,
        @Path("session_id") sessionId: String,
        @Body body: MeasurementBatchRequestDto,
    ): Response<MeasurementBatchResponseDto>

    companion object {
        /** `samples.maxItems` del contrato. */
        const val MAX_SAMPLES_PER_BATCH = 60
        const val SAMPLE_TYPE_HEART_RATE = "HEART_RATE"
        const val SAMPLE_UNIT_BPM = "bpm"
    }
}

@Serializable
data class RegisterDeviceRequestDto(
    @SerialName("device_id") val deviceId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("device_type") val deviceType: String,
    val protocol: String,
) {
    override fun toString(): String = "RegisterDeviceRequestDto(device_type=$deviceType)"
}

@Serializable
data class DevicesResponseDto(val items: List<DeviceDto>) {
    override fun toString(): String = "DevicesResponseDto(items=${items.size})"
}

/** `DeviceResponse`. */
@Serializable
data class DeviceDto(
    @SerialName("device_id") val deviceId: String,
    @SerialName("athlete_id") val athleteId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("device_type") val deviceType: String,
    val protocol: String,
    val status: String,
    val version: Int,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("revoked_at") val revokedAt: String?,
) {
    override fun toString(): String = "DeviceDto(status=$status, version=$version)"
}

@Serializable
data class MeasurementSampleDto(
    @SerialName("measured_at") val measuredAt: String,
    val type: String,
    val value: Int,
    val unit: String,
) {
    override fun toString(): String = "MeasurementSampleDto(type=$type)"
}

@Serializable
data class MeasurementBatchRequestDto(
    @SerialName("device_id") val deviceId: String,
    val samples: List<MeasurementSampleDto>,
) {
    override fun toString(): String = "MeasurementBatchRequestDto(samples=${samples.size})"
}

@Serializable
data class RejectedSampleDto(val index: Int, val issue: String)

/** HU036: evaluación del mismo ciclo; HU037 no la presenta (alertas y riesgo pertenecen a HU036). */
@Serializable
data class EvaluationDto(val status: String, val alerts: List<kotlinx.serialization.json.JsonObject>)

@Serializable
data class MeasurementBatchResponseDto(
    @SerialName("session_id") val sessionId: String,
    @SerialName("device_id") val deviceId: String,
    val received: Int,
    val accepted: Int,
    val duplicates: Int,
    val rejected: List<RejectedSampleDto>,
    val evaluation: EvaluationDto,
) {
    override fun toString(): String = "MeasurementBatchResponseDto(received=$received, accepted=$accepted, duplicates=$duplicates)"
}

/** La respuesta no corresponde al contrato: nunca se completan datos inventados. */
class InvalidDevicesResponse : Exception()

/** Dispositivo confirmado por el backend, sin la dirección local. */
data class RemoteDevice(
    val deviceId: String,
    val displayName: String,
    val status: DeviceStatus,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val revokedAt: Instant?,
)

private val DEVICE_ID_PATTERN = Regex("^[0-9a-f]{64}$")

fun DeviceDto.toRemote(): RemoteDevice {
    val parsedStatus = DeviceStatus.fromWire(status) ?: throw InvalidDevicesResponse()
    if (!DEVICE_ID_PATTERN.matches(deviceId)) throw InvalidDevicesResponse()
    return try {
        RemoteDevice(
            deviceId = deviceId,
            displayName = displayName,
            status = parsedStatus,
            version = version,
            createdAt = instant(createdAt),
            updatedAt = instant(updatedAt),
            revokedAt = revokedAt?.let(::instant),
        )
    } catch (_: DateTimeParseException) {
        throw InvalidDevicesResponse()
    } catch (_: DateTimeException) {
        throw InvalidDevicesResponse()
    }
}

private fun instant(value: String): Instant = OffsetDateTime.parse(value).toInstant()
