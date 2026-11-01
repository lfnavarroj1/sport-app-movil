package com.lfnj.sport_app.features.sensors.data.local

import com.lfnj.sport_app.core.security.TokenCipher
import com.lfnj.sport_app.features.sensors.model.DeviceStatus
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.time.Instant

/** Dispositivo guardado: datos confirmados por el backend y, si se vinculó aquí, la dirección BLE. */
data class StoredDevice(
    val deviceId: String,
    val displayName: String,
    val status: DeviceStatus,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val revokedAt: Instant?,
    val address: String?,
) {
    override fun toString(): String = "StoredDevice(status=$status, version=$version, hasAddress=${address != null})"
}

/** Contenido cifrado de [SensorDeviceEntity.payload]. Instantes en segundos UTC. */
@Serializable
internal data class DevicePayload(
    @SerialName("display_name") val displayName: String,
    val status: String,
    val version: Int,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("revoked_at") val revokedAt: Long? = null,
    val address: String? = null,
)

/** Contenido cifrado de [SensorSampleEntity.payload]: solo el valor en `bpm`. */
@Serializable
internal data class SamplePayload(val bpm: Int)

/**
 * Cifra y descifra las vinculaciones y las muestras con AES-GCM y una clave no exportable de
 * Android Keystore propia de HU037. Lo que no puede descifrarse se trata como inexistente: nunca
 * se envían muestras ni se muestran dispositivos inventados.
 */
class SensorsCodec(private val cipher: TokenCipher, private val json: Json) {

    fun encodeDevice(device: StoredDevice): ByteArray = encrypt(
        DevicePayload.serializer(),
        DevicePayload(
            displayName = device.displayName,
            status = device.status.wire,
            version = device.version,
            createdAt = device.createdAt.epochSecond,
            updatedAt = device.updatedAt.epochSecond,
            revokedAt = device.revokedAt?.epochSecond,
            address = device.address,
        ),
    )

    fun decodeDevice(entity: SensorDeviceEntity): StoredDevice? = safely {
        val payload = json.decodeFromString(DevicePayload.serializer(), decrypt(entity.payload))
        StoredDevice(
            deviceId = entity.deviceId,
            displayName = payload.displayName,
            status = DeviceStatus.fromWire(payload.status) ?: return@safely null,
            version = payload.version,
            createdAt = Instant.ofEpochSecond(payload.createdAt),
            updatedAt = Instant.ofEpochSecond(payload.updatedAt),
            revokedAt = payload.revokedAt?.let(Instant::ofEpochSecond),
            address = payload.address,
        )
    }

    fun encodeSample(bpm: Int): ByteArray = encrypt(SamplePayload.serializer(), SamplePayload(bpm))

    fun decodeSample(entity: SensorSampleEntity): Int? = safely {
        json.decodeFromString(SamplePayload.serializer(), decrypt(entity.payload)).bpm
    }

    private fun <T> encrypt(serializer: KSerializer<T>, payload: T): ByteArray =
        cipher.encrypt(json.encodeToString(serializer, payload).toByteArray(Charsets.UTF_8))

    private fun decrypt(payload: ByteArray): String = cipher.decrypt(payload).toString(Charsets.UTF_8)

    private inline fun <T> safely(block: () -> T?): T? = try {
        block()
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    companion object {
        /** Alias de la clave no exportable de Android Keystore para las vinculaciones y muestras de HU037. */
        const val KEY_ALIAS = "sportapp.sensors.v1"
    }
}
