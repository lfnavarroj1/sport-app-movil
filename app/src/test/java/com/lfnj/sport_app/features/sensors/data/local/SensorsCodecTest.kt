package com.lfnj.sport_app.features.sensors.data.local

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.features.sensors.model.DeviceStatus
import com.lfnj.sport_app.testing.FakeCipher
import com.lfnj.sport_app.testing.SensorsFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** HU037 (ADR-044 §2): vinculaciones y muestras cifradas; lo que no se descifra no existe. */
class SensorsCodecTest {
    private val cipher = FakeCipher()
    private val codec = SensorsCodec(cipher, BffMobileClient.json)
    private val deviceId = SensorsFixtures.deviceId()

    private val device = StoredDevice(
        deviceId = deviceId,
        displayName = SensorsFixtures.DISPLAY_NAME,
        status = DeviceStatus.ACTIVE,
        version = 1,
        createdAt = Instant.parse("2026-10-24T12:00:00Z"),
        updatedAt = Instant.parse("2026-10-24T12:05:00Z"),
        revokedAt = null,
        address = SensorsFixtures.ADDRESS,
    )

    private fun entity(payload: ByteArray) = SensorDeviceEntity(SensorsFixtures.OWNER, deviceId, payload)

    private fun sample(payload: ByteArray) = SensorSampleEntity(SensorsFixtures.OWNER, SensorsFixtures.SESSION_ID, deviceId, 1_761_309_001L, payload)

    @Test
    fun `un dispositivo activo con direccion va y vuelve igual`() {
        assertEquals(device, codec.decodeDevice(entity(codec.encodeDevice(device))))
    }

    @Test
    fun `un dispositivo revocado sin direccion va y vuelve igual`() {
        val revoked = device.copy(
            status = DeviceStatus.REVOKED,
            version = 2,
            revokedAt = Instant.parse("2026-10-24T13:00:00Z"),
            address = null,
        )

        assertEquals(revoked, codec.decodeDevice(entity(codec.encodeDevice(revoked))))
    }

    @Test
    fun `una muestra va y vuelve igual`() {
        assertEquals(142, codec.decodeSample(sample(codec.encodeSample(142))))
    }

    @Test
    fun `el payload guardado no contiene la direccion, el nombre ni el bpm en claro`() {
        val devicePayload = String(codec.encodeDevice(device), Charsets.ISO_8859_1)
        val samplePayload = String(codec.encodeSample(137), Charsets.ISO_8859_1)

        assertFalse(devicePayload.contains(SensorsFixtures.ADDRESS))
        assertFalse(devicePayload.contains("address"))
        assertFalse(samplePayload.contains("137"))
        assertFalse(samplePayload.contains("bpm"))
    }

    @Test
    fun `el toString del dispositivo guardado no expone la direccion`() {
        assertFalse(device.toString().contains(SensorsFixtures.ADDRESS))
    }

    @Test
    fun `un cifrado alterado se trata como inexistente`() {
        val payload = codec.encodeDevice(device)
        val tampered = payload.copyOf().also { it[0] = (it[0] + 1).toByte() }

        assertNull(codec.decodeDevice(entity(tampered)))
        assertNull(codec.decodeSample(sample(codec.encodeSample(120).copyOfRange(1, 4))))
    }

    @Test
    fun `una clave inutilizable o un contenido ajeno se tratan como inexistentes`() {
        val payload = codec.encodeDevice(device)
        val samplePayload = codec.encodeSample(120)
        cipher.broken = true

        assertNull(codec.decodeDevice(entity(payload)))
        assertNull(codec.decodeSample(sample(samplePayload)))

        cipher.broken = false
        assertNull(codec.decodeDevice(entity(cipher.encrypt("no es json".toByteArray()))))
        assertNull(codec.decodeSample(sample(cipher.encrypt("""{"valor":1}""".toByteArray()))))
    }

    @Test
    fun `un estado desconocido no produce un dispositivo`() {
        val unknown = """{"display_name":"x","status":"LOST","version":1,"created_at":1,"updated_at":1}"""

        assertNull(codec.decodeDevice(entity(cipher.encrypt(unknown.toByteArray()))))
    }

    @Test
    fun `la clave de Keystore es propia de HU037`() {
        assertEquals("sportapp.sensors.v1", SensorsCodec.KEY_ALIAS)
    }
}
