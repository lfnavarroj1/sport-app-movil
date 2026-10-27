package com.lfnj.sport_app.features.sensors.data

import com.lfnj.sport_app.core.network.ApiResult
import com.lfnj.sport_app.core.network.apiCall
import com.lfnj.sport_app.core.session.AuthorizedResult
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.sensors.data.local.SensorDeviceEntity
import com.lfnj.sport_app.features.sensors.data.local.SensorSampleEntity
import com.lfnj.sport_app.features.sensors.data.local.SensorsCodec
import com.lfnj.sport_app.features.sensors.data.local.SensorsDao
import com.lfnj.sport_app.features.sensors.data.local.StoredDevice
import com.lfnj.sport_app.features.sensors.model.DEVICE_TYPE_HEART_RATE_MONITOR
import com.lfnj.sport_app.features.sensors.model.DeviceIdHasher
import com.lfnj.sport_app.features.sensors.model.DeviceStatus
import com.lfnj.sport_app.features.sensors.model.DiscoveredMonitor
import com.lfnj.sport_app.features.sensors.model.LinkedDevice
import com.lfnj.sport_app.features.sensors.model.PROTOCOL_BLE_HRS
import com.lfnj.sport_app.features.training.session.model.TrainingOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID

/** Motivo por el que una operación de dispositivos no se confirmó; nunca describe datos ni detalles técnicos. */
enum class DevicesProblem {
    /** Sin red: vincular y revocar exigen conexión (no se encolan). */
    OFFLINE,
    /** Tiempo agotado o servicio no disponible: resultado desconocido, reintentar con la misma clave. */
    UNAVAILABLE,
    /** `403 capability_required`: explicar el plan requerido. */
    CAPABILITY_REQUIRED,
    /** `403 permission_required`. */
    PERMISSION_REQUIRED,
    /** `409 device_already_registered`: el mismo monitor ya está activo con otro nombre. */
    ALREADY_REGISTERED,
    /** `409 idempotency_key_reused`: se preparó una clave nueva; confirmar de nuevo. */
    KEY_REUSED,
    /** `404 device_not_found`. */
    NOT_FOUND,
    /** `422` o respuesta fuera del contrato. */
    INVALID,
    /** La sesión de la aplicación ya no es válida. */
    SIGNED_OUT,
}

sealed interface DevicesResult<out T> {
    data class Done<T>(val value: T) : DevicesResult<T>
    data class Failed(val problem: DevicesProblem) : DevicesResult<Nothing>
}

/** Operaciones de la pantalla «Dispositivos de medición» (HU037). */
interface DevicesGateway {
    fun observe(owner: String): Flow<List<LinkedDevice>>

    /** Reemplaza el estado local con `GET /v1/me/devices`, conservando las direcciones cifradas ya conocidas. */
    suspend fun refresh(owner: String): DevicesResult<Unit>

    /** Vincula [monitor] (ya verificado con el servicio `0x180D`) con [displayName] y la clave [idempotencyKey]. */
    suspend fun link(user: SessionUser, monitor: DiscoveredMonitor, displayName: String, idempotencyKey: String): DevicesResult<LinkedDevice>

    suspend fun revoke(owner: String, deviceId: String): DevicesResult<LinkedDevice>

    /** Clave UUID nueva para cada intento de vinculación. */
    fun newIdempotencyKey(): String = UUID.randomUUID().toString()
}

/** Dispositivo conectable para la ingestión: el último vinculado en este teléfono que sigue `ACTIVE`. */
interface ConnectableDevices {
    fun observeConnectable(owner: String): Flow<ConnectableDevice?>

    /** El backend respondió `409 device_revoked`: se refleja localmente sin esperar otra lectura (CA6). */
    suspend fun markRevoked(owner: String, deviceId: String)
}

/** Identidad lógica y dirección del monitor activo; la dirección solo viaja hacia la conexión BLE. */
class ConnectableDevice(val deviceId: String, val address: String) {
    override fun equals(other: Any?): Boolean = other is ConnectableDevice && other.deviceId == deviceId && other.address == address
    override fun hashCode(): Int = deviceId.hashCode()
    override fun toString(): String = "ConnectableDevice(deviceId=$deviceId)"
}

/** Limpieza local (cierre de sesión o cambio de deportista). */
interface SensorsStore {
    suspend fun clear(owner: String)
    suspend fun clearAllExcept(owner: String)
}

/**
 * HU037 (ADR-044 §2–§3): vinculaciones del deportista confirmadas por `bff-mobile` y guardadas
 * cifradas. Vincular y revocar son comandos solo en línea: no se encolan y nada se confirma en el
 * dispositivo sin la respuesta del backend. La dirección BLE solo se conserva cifrada y únicamente
 * para el monitor vinculado desde este teléfono.
 */
class DevicesRepository(
    private val dao: SensorsDao,
    private val codec: SensorsCodec,
    private val api: DevicesApi?,
    private val sessionManager: SessionManager,
    private val json: Json,
) : DevicesGateway, ConnectableDevices, SensorsStore {

    override fun observe(owner: String): Flow<List<LinkedDevice>> =
        stored(owner).map { devices -> devices.map { it.toLinked() }.sortedByDescending { it.createdAt } }.distinctUntilChanged()

    override fun observeConnectable(owner: String): Flow<ConnectableDevice?> =
        stored(owner).map { devices ->
            devices.filter { it.status == DeviceStatus.ACTIVE && it.address != null }
                .maxByOrNull { it.updatedAt }
                ?.let { ConnectableDevice(it.deviceId, checkNotNull(it.address)) }
        }.distinctUntilChanged()

    override suspend fun refresh(owner: String): DevicesResult<Unit> {
        val api = api ?: return DevicesResult.Failed(DevicesProblem.UNAVAILABLE)
        return when (val result = authorized { auth -> api.list(auth) }) {
            is DevicesResult.Failed -> result
            is DevicesResult.Done -> try {
                val remote = result.value.items.map { it.toRemote() }
                val known = dao.devices(owner).mapNotNull(codec::decodeDevice).associateBy { it.deviceId }
                remote.forEach { device -> save(owner, device.stored(known[device.deviceId]?.address)) }
                dao.deleteDevicesExcept(owner, remote.map { it.deviceId })
                DevicesResult.Done(Unit)
            } catch (_: InvalidDevicesResponse) {
                DevicesResult.Failed(DevicesProblem.INVALID)
            }
        }
    }

    override suspend fun link(
        user: SessionUser,
        monitor: DiscoveredMonitor,
        displayName: String,
        idempotencyKey: String,
    ): DevicesResult<LinkedDevice> {
        val api = api ?: return DevicesResult.Failed(DevicesProblem.UNAVAILABLE)
        val owner = TrainingOwner.keyOf(user.userId)
        val body = RegisterDeviceRequestDto(
            deviceId = DeviceIdHasher.deviceId(monitor.address, user.userId),
            displayName = displayName,
            deviceType = DEVICE_TYPE_HEART_RATE_MONITOR,
            protocol = PROTOCOL_BLE_HRS,
        )
        return when (val result = authorized { auth -> api.register(auth, idempotencyKey, body) }) {
            is DevicesResult.Failed -> result
            is DevicesResult.Done -> try {
                val stored = result.value.toRemote().stored(monitor.address)
                if (stored.deviceId != body.deviceId) return DevicesResult.Failed(DevicesProblem.INVALID)
                save(owner, stored)
                DevicesResult.Done(stored.toLinked())
            } catch (_: InvalidDevicesResponse) {
                DevicesResult.Failed(DevicesProblem.INVALID)
            }
        }
    }

    override suspend fun revoke(owner: String, deviceId: String): DevicesResult<LinkedDevice> {
        val api = api ?: return DevicesResult.Failed(DevicesProblem.UNAVAILABLE)
        return when (val result = authorized { auth -> api.revoke(auth, deviceId) }) {
            is DevicesResult.Failed -> result
            is DevicesResult.Done -> try {
                val known = dao.devices(owner).mapNotNull(codec::decodeDevice).firstOrNull { it.deviceId == deviceId }
                val stored = result.value.toRemote().stored(known?.address)
                save(owner, stored)
                dao.deleteDeviceSamples(owner, deviceId)
                DevicesResult.Done(stored.toLinked())
            } catch (_: InvalidDevicesResponse) {
                DevicesResult.Failed(DevicesProblem.INVALID)
            }
        }
    }

    override suspend fun markRevoked(owner: String, deviceId: String) {
        val known = dao.devices(owner).mapNotNull(codec::decodeDevice).firstOrNull { it.deviceId == deviceId } ?: return
        if (known.status != DeviceStatus.REVOKED) save(owner, known.copy(status = DeviceStatus.REVOKED))
        dao.deleteDeviceSamples(owner, deviceId)
    }

    override suspend fun clear(owner: String) {
        dao.deleteDevices(owner)
        dao.deleteAllSamples(owner)
    }

    override suspend fun clearAllExcept(owner: String) {
        dao.deleteDevicesOfOthers(owner)
        dao.deleteSamplesOfOthers(owner)
    }

    private fun stored(owner: String): Flow<List<StoredDevice>> =
        dao.observeDevices(owner).map { entities -> entities.mapNotNull(codec::decodeDevice) }

    private suspend fun save(owner: String, device: StoredDevice) =
        dao.upsertDevice(SensorDeviceEntity(owner, device.deviceId, codec.encodeDevice(device)))

    private suspend fun <T : Any> authorized(call: suspend (String) -> retrofit2.Response<T>): DevicesResult<T> =
        when (val outcome = sessionManager.authorized { auth -> apiCall(json) { call(auth) } }) {
            AuthorizedResult.SignedOut -> DevicesResult.Failed(DevicesProblem.SIGNED_OUT)
            AuthorizedResult.RenewalUnavailable -> DevicesResult.Failed(DevicesProblem.UNAVAILABLE)
            is AuthorizedResult.Completed -> when (val result = outcome.result) {
                is ApiResult.Success -> DevicesResult.Done(result.value)
                is ApiResult.Failure -> DevicesResult.Failed(problemOf(result))
            }
        }

    private fun RemoteDevice.stored(address: String?) =
        StoredDevice(deviceId, displayName, status, version, createdAt, updatedAt, revokedAt, address)

    private fun StoredDevice.toLinked() =
        LinkedDevice(deviceId, displayName, status, version, createdAt, updatedAt, revokedAt, hasLocalAddress = address != null)
}

/** Traduce una falla del BFF al motivo que la pantalla explica. */
internal fun problemOf(failure: ApiResult.Failure): DevicesProblem = when (failure) {
    ApiResult.NoConnection -> DevicesProblem.OFFLINE
    ApiResult.Timeout, ApiResult.NotConfigured -> DevicesProblem.UNAVAILABLE
    ApiResult.InvalidResponse -> DevicesProblem.INVALID
    is ApiResult.HttpFailure -> when {
        failure.status == 403 && failure.code == "capability_required" -> DevicesProblem.CAPABILITY_REQUIRED
        failure.status == 403 -> DevicesProblem.PERMISSION_REQUIRED
        failure.status == 409 && failure.code == "device_already_registered" -> DevicesProblem.ALREADY_REGISTERED
        failure.status == 409 && failure.code == "idempotency_key_reused" -> DevicesProblem.KEY_REUSED
        failure.status == 404 -> DevicesProblem.NOT_FOUND
        failure.status == 422 || failure.status in 400..499 -> DevicesProblem.INVALID
        else -> DevicesProblem.UNAVAILABLE
    }
}

/**
 * Borra las vinculaciones y muestras al terminar la sesión o cambiar de deportista: sin sesión de
 * aplicación no queda ninguna dirección BLE ni medición en el teléfono.
 */
class SensorsJanitor(private val currentUser: StateFlow<SessionUser?>, private val store: SensorsStore) {
    fun start(scope: CoroutineScope) = scope.launch {
        var previous: String? = null
        currentUser
            .map { user -> user?.userId?.let(TrainingOwner::keyOf) }
            .distinctUntilChanged()
            .catch { }
            .collect { owner ->
                try {
                    previous?.takeIf { it != owner }?.let { store.clear(it) }
                    owner?.let { store.clearAllExcept(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // La limpieza se reintenta en el próximo cambio de sesión; la partición protege mientras tanto.
                }
                previous = owner
            }
    }
}

/** Muestras pendientes de la sesión activa, cifradas (CA5); las usa la tarea de ingestión. */
class SensorSamples(private val dao: SensorsDao, private val codec: SensorsCodec) {
    suspend fun record(owner: String, sessionId: String, deviceId: String, measuredAtEpochSecond: Long, bpm: Int) =
        dao.insertSample(SensorSampleEntity(owner, sessionId, deviceId, measuredAtEpochSecond, codec.encodeSample(bpm)))

    /** Las más antiguas primero, hasta [limit]; una muestra que no puede descifrarse se descarta. */
    suspend fun oldest(owner: String, sessionId: String, deviceId: String, limit: Int): List<Pair<Long, Int>> {
        val entities = dao.oldestSamples(owner, sessionId, deviceId, limit)
        val unreadable = entities.filter { codec.decodeSample(it) == null }.map { it.measuredAt }
        if (unreadable.isNotEmpty()) dao.deleteSamples(sessionId, deviceId, unreadable)
        return entities.mapNotNull { entity -> codec.decodeSample(entity)?.let { entity.measuredAt to it } }
    }

    suspend fun remove(sessionId: String, deviceId: String, measuredAt: List<Long>) = dao.deleteSamples(sessionId, deviceId, measuredAt)

    suspend fun count(owner: String, sessionId: String): Int = dao.countSamples(owner, sessionId)

    suspend fun dropSession(owner: String, sessionId: String) = dao.deleteSessionSamples(owner, sessionId)

    suspend fun dropOtherSessions(owner: String, sessionId: String) = dao.deleteSamplesOfOtherSessions(owner, sessionId)

    suspend fun expireBefore(epochSecond: Long) = dao.deleteSamplesBefore(epochSecond)
}
