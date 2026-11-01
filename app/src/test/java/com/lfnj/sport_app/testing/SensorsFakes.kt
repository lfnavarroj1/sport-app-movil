package com.lfnj.sport_app.testing

import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.sensors.ble.BleAvailability
import com.lfnj.sport_app.features.sensors.ble.HeartRateMonitorSource
import com.lfnj.sport_app.features.sensors.ble.MonitorEvent
import com.lfnj.sport_app.features.sensors.data.ConnectableDevice
import com.lfnj.sport_app.features.sensors.data.ConnectableDevices
import com.lfnj.sport_app.features.sensors.data.DeviceDto
import com.lfnj.sport_app.features.sensors.data.DevicesApi
import com.lfnj.sport_app.features.sensors.data.DevicesResponseDto
import com.lfnj.sport_app.features.sensors.data.EvaluationDto
import com.lfnj.sport_app.features.sensors.data.MeasurementBatchRequestDto
import com.lfnj.sport_app.features.sensors.data.MeasurementBatchResponseDto
import com.lfnj.sport_app.features.sensors.data.RegisterDeviceRequestDto
import com.lfnj.sport_app.features.sensors.data.local.SensorDeviceEntity
import com.lfnj.sport_app.features.sensors.data.local.SensorSampleEntity
import com.lfnj.sport_app.features.sensors.data.local.SensorsDao
import com.lfnj.sport_app.features.sensors.model.DeviceIdHasher
import com.lfnj.sport_app.features.sensors.model.DeviceStatus
import com.lfnj.sport_app.features.sensors.model.DiscoveredMonitor
import com.lfnj.sport_app.features.sensors.model.LinkedDevice
import com.lfnj.sport_app.features.training.session.model.TrainingOwner
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import java.time.Instant
import java.util.UUID

/** Datos sintéticos de HU037 conforme a `bff-mobile` 0.21.0 (`DeviceResponse`, `MeasurementBatchResponse`). */
object SensorsFixtures {
    /** Direcciones BLE sintéticas (prefijo inventado, no corresponden a ningún fabricante real). */
    const val ADDRESS = "C0:FF:EE:00:00:01"
    const val OTHER_ADDRESS = "C0:FF:EE:00:00:02"
    const val ADVERTISED_NAME = "Polar H10 0001"
    const val DISPLAY_NAME = "Pulsómetro de pecho"

    val SESSION_ID: String = UUID.nameUUIDFromBytes("hu037-sesion-activa".toByteArray()).toString()
    val START: Instant = Instant.parse("2026-10-24T12:30:00Z")
    val OWNER: String = TrainingOwner.keyOf(TrainingFixtures.USER_ID)
    val OTHER_OWNER: String = TrainingOwner.keyOf(TrainingFixtures.OTHER_USER_ID)

    fun deviceId(address: String = ADDRESS, userId: String = TrainingFixtures.USER_ID): String = DeviceIdHasher.deviceId(address, userId)

    fun user(userId: String = TrainingFixtures.USER_ID) = SessionUser(userId, "Ana Deportista", "ana@example.com", ActorType.ATHLETE)

    fun monitor(address: String = ADDRESS, name: String? = ADVERTISED_NAME) = DiscoveredMonitor(address, name)

    fun deviceBody(
        deviceId: String = deviceId(),
        displayName: String = DISPLAY_NAME,
        status: String = "ACTIVE",
        version: Int = 1,
        createdAt: String = "2026-10-24T12:00:00Z",
        updatedAt: String = createdAt,
        revokedAt: String? = null,
    ): String {
        val revoked = revokedAt?.let { "\"$it\"" } ?: "null"
        return """{"device_id":"$deviceId","athlete_id":"${TrainingFixtures.USER_ID}","display_name":"$displayName",""" +
            """"device_type":"HEART_RATE_MONITOR","protocol":"BLE_HRS","status":"$status","version":$version,""" +
            """"created_at":"$createdAt","updated_at":"$updatedAt","revoked_at":$revoked}"""
    }

    fun devicesBody(vararg items: String): String = """{"items":${items.joinToString(",", "[", "]")}}"""

    fun batchBody(
        sessionId: String = SESSION_ID,
        deviceId: String = deviceId(),
        received: Int,
        accepted: Int,
        duplicates: Int = 0,
        rejected: List<Pair<Int, String>> = emptyList(),
    ): String {
        val rejectedJson = rejected.joinToString(",", "[", "]") { (index, issue) -> """{"index":$index,"issue":"$issue"}""" }
        return """{"session_id":"$sessionId","device_id":"$deviceId","received":$received,"accepted":$accepted,""" +
            """"duplicates":$duplicates,"rejected":$rejectedJson,"evaluation":{"status":"OK","alerts":[]}}"""
    }

    fun linked(
        deviceId: String = deviceId(),
        name: String = DISPLAY_NAME,
        status: DeviceStatus = DeviceStatus.ACTIVE,
        hasLocalAddress: Boolean = true,
        createdAt: Instant = Instant.parse("2026-10-24T12:00:00Z"),
    ) = LinkedDevice(
        deviceId = deviceId,
        displayName = name,
        status = status,
        version = if (status == DeviceStatus.ACTIVE) 1 else 2,
        createdAt = createdAt,
        updatedAt = createdAt,
        revokedAt = if (status == DeviceStatus.REVOKED) createdAt.plusSeconds(60) else null,
        hasLocalAddress = hasLocalAddress,
    )

    fun <T> error(status: Int, code: String): Response<T> = Response.error(
        status,
        """{"code":"$code","message":"mensaje","correlation_id":"8c1f2d3e-0000-4000-8000-000000000037"}"""
            .toResponseBody("application/json".toMediaType()),
    )
}

/** Base de HU037 en memoria con la misma semántica que el DAO de Room (`REPLACE`, `IGNORE`, particiones). */
class InMemorySensorsDao : SensorsDao {
    val deviceRows = MutableStateFlow<List<SensorDeviceEntity>>(emptyList())
    val sampleRows = mutableListOf<SensorSampleEntity>()

    override fun observeDevices(owner: String): Flow<List<SensorDeviceEntity>> = deviceRows.map { rows -> rows.filter { it.ownerKey == owner } }

    override suspend fun devices(owner: String): List<SensorDeviceEntity> = deviceRows.value.filter { it.ownerKey == owner }

    override suspend fun upsertDevice(entry: SensorDeviceEntity) {
        deviceRows.value = deviceRows.value.filterNot { it.ownerKey == entry.ownerKey && it.deviceId == entry.deviceId } + entry
    }

    override suspend fun deleteDevicesExcept(owner: String, keep: List<String>) {
        deviceRows.value = deviceRows.value.filterNot { it.ownerKey == owner && it.deviceId !in keep }
    }

    override suspend fun insertSample(entry: SensorSampleEntity) {
        val exists = sampleRows.any { it.sessionId == entry.sessionId && it.deviceId == entry.deviceId && it.measuredAt == entry.measuredAt }
        if (!exists) sampleRows += entry
    }

    override suspend fun oldestSamples(owner: String, sessionId: String, deviceId: String, limit: Int): List<SensorSampleEntity> =
        sampleRows.filter { it.ownerKey == owner && it.sessionId == sessionId && it.deviceId == deviceId }.sortedBy { it.measuredAt }.take(limit)

    override suspend fun countSamples(owner: String, sessionId: String): Int = sampleRows.count { it.ownerKey == owner && it.sessionId == sessionId }

    override suspend fun deleteSamples(sessionId: String, deviceId: String, measuredAt: List<Long>) {
        sampleRows.removeAll { it.sessionId == sessionId && it.deviceId == deviceId && it.measuredAt in measuredAt }
    }

    override suspend fun deleteSessionSamples(owner: String, sessionId: String) {
        sampleRows.removeAll { it.ownerKey == owner && it.sessionId == sessionId }
    }

    override suspend fun deleteDeviceSamples(owner: String, deviceId: String) {
        sampleRows.removeAll { it.ownerKey == owner && it.deviceId == deviceId }
    }

    override suspend fun deleteSamplesBefore(before: Long) {
        sampleRows.removeAll { it.measuredAt < before }
    }

    override suspend fun deleteSamplesOfOtherSessions(owner: String, sessionId: String) {
        sampleRows.removeAll { it.ownerKey == owner && it.sessionId != sessionId }
    }

    override suspend fun deleteDevices(owner: String) {
        deviceRows.value = deviceRows.value.filterNot { it.ownerKey == owner }
    }

    override suspend fun deleteAllSamples(owner: String) {
        sampleRows.removeAll { it.ownerKey == owner }
    }

    override suspend fun deleteDevicesOfOthers(owner: String) {
        deviceRows.value = deviceRows.value.filter { it.ownerKey == owner }
    }

    override suspend fun deleteSamplesOfOthers(owner: String) {
        sampleRows.removeAll { it.ownerKey != owner }
    }
}

/**
 * Fuente simulada de monitores: declara [simulated] como exige ADR-044 §4. Cada `connect` toma la
 * siguiente conexión programada o, si no hay, una que se conecta y queda abierta hasta cancelarse.
 */
class FakeMonitorSource(
    override val simulated: Boolean = true,
    var available: BleAvailability = BleAvailability.READY,
) : HeartRateMonitorSource {
    var scanFlow: Flow<DiscoveredMonitor> = emptyFlow()
    val connections = ArrayDeque<Flow<MonitorEvent>>()
    val connectAddresses = mutableListOf<String>()

    override fun availability(): BleAvailability = available

    override fun scan(windowMillis: Long): Flow<DiscoveredMonitor> = scanFlow

    override fun connect(address: String): Flow<MonitorEvent> {
        connectAddresses += address
        return connections.removeFirstOrNull() ?: openConnection()
    }

    companion object {
        fun openConnection(vararg extra: MonitorEvent): Flow<MonitorEvent> = flow {
            emit(MonitorEvent.Connecting)
            emit(MonitorEvent.Connected)
            extra.forEach { emit(it) }
            awaitCancellation()
        }
    }
}

/** Monitor conectable observable; registra las revocaciones locales (`409 device_revoked`). */
class FakeConnectableDevices(initial: ConnectableDevice? = null) : ConnectableDevices {
    val connectable = MutableStateFlow(initial)
    val revoked = mutableListOf<Pair<String, String>>()

    override fun observeConnectable(owner: String): Flow<ConnectableDevice?> = connectable

    override suspend fun markRevoked(owner: String, deviceId: String) {
        revoked += owner to deviceId
        connectable.value = null
    }
}

/** API de ingestión en memoria: acepta todo salvo que la prueba programe otra respuesta. */
class FakeDevicesApi : DevicesApi {
    val ingested = mutableListOf<MeasurementBatchRequestDto>()
    val ingestResponses = ArrayDeque<Response<MeasurementBatchResponseDto>>()

    override suspend fun list(authorization: String): Response<DevicesResponseDto> = error("no se usa en estas pruebas")

    override suspend fun register(authorization: String, idempotencyKey: String, body: RegisterDeviceRequestDto): Response<DeviceDto> =
        error("no se usa en estas pruebas")

    override suspend fun revoke(authorization: String, deviceId: String): Response<DeviceDto> = error("no se usa en estas pruebas")

    override suspend fun ingest(authorization: String, sessionId: String, body: MeasurementBatchRequestDto): Response<MeasurementBatchResponseDto> {
        ingested += body
        return ingestResponses.removeFirstOrNull()
            ?: Response.success(
                MeasurementBatchResponseDto(sessionId, body.deviceId, body.samples.size, body.samples.size, 0, emptyList(), EvaluationDto("OK", emptyList()))
            )
    }
}
