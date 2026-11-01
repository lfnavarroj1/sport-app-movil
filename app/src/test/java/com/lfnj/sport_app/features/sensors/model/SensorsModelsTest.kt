package com.lfnj.sport_app.features.sensors.model

import com.lfnj.sport_app.core.session.AccessSnapshot
import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.SubscriptionStatus
import com.lfnj.sport_app.testing.SensorsFixtures
import com.lfnj.sport_app.testing.SessionFixtures
import com.lfnj.sport_app.testing.TrainingFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** HU037 (ADR-044 §2–§3): identidad lógica del dispositivo, nombre visible y acceso a la opción 220. */
class DeviceIdHasherTest {
    private val userId = TrainingFixtures.USER_ID

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @Test
    fun `el device_id son 64 caracteres hexadecimales en minusculas`() {
        val id = DeviceIdHasher.deviceId(SensorsFixtures.ADDRESS, userId)

        assertTrue(id, Regex("^[0-9a-f]{64}$").matches(id))
    }

    @Test
    fun `es el SHA-256 de la direccion en mayusculas seguida del user_id`() {
        assertEquals(sha256Hex(SensorsFixtures.ADDRESS.uppercase() + userId), DeviceIdHasher.deviceId(SensorsFixtures.ADDRESS, userId))
    }

    @Test
    fun `es determinista e independiente de mayusculas y espacios de la direccion`() {
        val id = DeviceIdHasher.deviceId(SensorsFixtures.ADDRESS, userId)

        assertEquals(id, DeviceIdHasher.deviceId(SensorsFixtures.ADDRESS, userId))
        assertEquals(id, DeviceIdHasher.deviceId(SensorsFixtures.ADDRESS.lowercase(), userId))
        assertEquals(id, DeviceIdHasher.deviceId("  ${SensorsFixtures.ADDRESS.lowercase()} ", userId))
    }

    @Test
    fun `el mismo monitor tiene identidades distintas para deportistas distintos`() {
        assertNotEquals(
            DeviceIdHasher.deviceId(SensorsFixtures.ADDRESS, TrainingFixtures.USER_ID),
            DeviceIdHasher.deviceId(SensorsFixtures.ADDRESS, TrainingFixtures.OTHER_USER_ID),
        )
        assertNotEquals(
            DeviceIdHasher.deviceId(SensorsFixtures.ADDRESS, userId),
            DeviceIdHasher.deviceId(SensorsFixtures.OTHER_ADDRESS, userId),
        )
    }

    @Test
    fun `el device_id no contiene la direccion BLE`() {
        val id = DeviceIdHasher.deviceId(SensorsFixtures.ADDRESS, userId)

        assertFalse(id.contains(SensorsFixtures.ADDRESS.replace(":", "").lowercase()))
    }
}

class DeviceRulesTest {
    @Test
    fun `normalizeName recorta los espacios`() {
        assertEquals("Polar H10", DeviceRules.normalizeName("  Polar H10  "))
    }

    @Test
    fun `normalizeName rechaza un nombre vacio`() {
        assertNull(DeviceRules.normalizeName(""))
        assertNull(DeviceRules.normalizeName("    "))
    }

    @Test
    fun `normalizeName admite hasta 60 caracteres y rechaza mas`() {
        assertEquals("a".repeat(60), DeviceRules.normalizeName("a".repeat(60)))
        assertEquals("a".repeat(60), DeviceRules.normalizeName("  ${"a".repeat(60)}  "))
        assertNull(DeviceRules.normalizeName("a".repeat(61)))
        assertEquals(60, DeviceRules.MAX_NAME_LENGTH)
    }

    @Test
    fun `el nombre sugerido sale del anuncio BLE ajustado al maximo`() {
        assertEquals(SensorsFixtures.ADVERTISED_NAME, DeviceRules.suggestedName(SensorsFixtures.monitor()))
        assertEquals("", DeviceRules.suggestedName(SensorsFixtures.monitor(name = null)))
        assertEquals("b".repeat(60), DeviceRules.suggestedName(SensorsFixtures.monitor(name = " ${"b".repeat(70)}")))
    }

    @Test
    fun `el monitor descubierto se identifica por direccion y no la expone en toString`() {
        val monitor = SensorsFixtures.monitor()

        assertEquals(monitor, DiscoveredMonitor(SensorsFixtures.ADDRESS, "Otro nombre"))
        assertNotEquals(monitor, SensorsFixtures.monitor(SensorsFixtures.OTHER_ADDRESS))
        assertFalse(monitor.toString().contains(SensorsFixtures.ADDRESS))
    }
}

class DevicesAccessTest {
    private fun access(
        capabilities: List<String>,
        status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
        actor: ActorType = ActorType.ATHLETE,
    ) = AccessSnapshot(actor, status, "INTERMEDIATE", emptySet(), capabilities.toSet())

    @Test
    fun `INTERMEDIATE con suscripcion activa puede usar dispositivos`() {
        assertTrue(DevicesAccess.canUse(access(SessionFixtures.INTERMEDIATE_CAPABILITIES)))
        assertTrue(DevicesAccess.canUse(access(SessionFixtures.PREMIUM_CAPABILITIES)))
    }

    @Test
    fun `FREE no puede usar dispositivos`() {
        assertFalse(DevicesAccess.canUse(access(SessionFixtures.FREE_CAPABILITIES)))
    }

    @Test
    fun `una suscripcion no activa, otro actor o sin capacidades conocidas no puede`() {
        SubscriptionStatus.entries.filter { it != SubscriptionStatus.ACTIVE }.forEach { status ->
            assertFalse(status.name, DevicesAccess.canUse(access(SessionFixtures.INTERMEDIATE_CAPABILITIES, status)))
        }
        assertFalse(DevicesAccess.canUse(access(SessionFixtures.INTERMEDIATE_CAPABILITIES, actor = ActorType.ORGANIZER)))
        assertFalse(DevicesAccess.canUse(null))
    }

    @Test
    fun `la capacidad es la opcion 220 del plan`() {
        assertEquals("devices.measurement.connect", DEVICES_CAPABILITY)
        assertTrue(DEVICES_CAPABILITY in SessionFixtures.INTERMEDIATE_CAPABILITIES)
    }

    @Test
    fun `un dispositivo solo es conectable activo y con direccion local`() {
        assertTrue(SensorsFixtures.linked().connectable)
        assertFalse(SensorsFixtures.linked(hasLocalAddress = false).connectable)
        assertFalse(SensorsFixtures.linked(status = DeviceStatus.REVOKED).connectable)
        assertEquals(DeviceStatus.REVOKED, DeviceStatus.fromWire("REVOKED"))
        assertNull(DeviceStatus.fromWire("LOST"))
    }
}
