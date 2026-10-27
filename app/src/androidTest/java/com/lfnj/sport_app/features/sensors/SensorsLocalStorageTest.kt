package com.lfnj.sport_app.features.sensors

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lfnj.sport_app.R
import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.security.KeystoreTokenCipher
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.features.sensors.data.ConnectableDevice
import com.lfnj.sport_app.features.sensors.data.DevicesRepository
import com.lfnj.sport_app.features.sensors.data.SensorSamples
import com.lfnj.sport_app.features.sensors.data.local.SensorDeviceEntity
import com.lfnj.sport_app.features.sensors.data.local.SensorsCodec
import com.lfnj.sport_app.features.sensors.data.local.SensorsDatabase
import com.lfnj.sport_app.features.sensors.data.local.StoredDevice
import com.lfnj.sport_app.features.sensors.model.DeviceIdHasher
import com.lfnj.sport_app.features.sensors.model.DeviceStatus
import com.lfnj.sport_app.features.training.session.model.TrainingOwner
import com.lfnj.sport_app.testing.InMemoryTokenStore
import com.lfnj.sport_app.testing.TestAppContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.time.Instant
import java.util.UUID

/**
 * HU037 en el dispositivo: Room real (en memoria) y cifrado AES-GCM con una clave de prueba de
 * Android Keystore. La dirección BLE, el nombre y el valor en `bpm` nunca quedan en claro; las
 * muestras respetan la clave natural `(session_id, device_id, measured_at)`; lo que no puede
 * descifrarse se trata como inexistente; la base está excluida del respaldo (ADR-044 §2, §6).
 */
@RunWith(AndroidJUnit4::class)
class SensorsLocalStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database = Room.inMemoryDatabaseBuilder(context, SensorsDatabase::class.java).build()
    private val dao = database.sensorsDao()
    private val codec = SensorsCodec(KeystoreTokenCipher(TestAppContainer.SENSORS_TEST_KEY_ALIAS), BffMobileClient.json)
    private val samples = SensorSamples(dao, codec)

    private val userId = UUID.nameUUIDFromBytes("hu037-ana".toByteArray()).toString()
    private val owner = TrainingOwner.keyOf(userId)
    private val otherOwner = TrainingOwner.keyOf(UUID.nameUUIDFromBytes("hu037-otro".toByteArray()).toString())
    private val sessionId = UUID.nameUUIDFromBytes("hu037-sesion".toByteArray()).toString()
    private val deviceId = DeviceIdHasher.deviceId(ADDRESS, userId)
    private val at = Instant.parse("2026-10-27T15:00:00Z")

    private fun stored(status: DeviceStatus = DeviceStatus.ACTIVE, address: String? = ADDRESS, id: String = deviceId) =
        StoredDevice(id, NAME, status, 1, at, at, if (status == DeviceStatus.REVOKED) at else null, address)

    private fun repository() = DevicesRepository(
        dao = dao,
        codec = codec,
        api = null,
        sessionManager = SessionManager(null, InMemoryTokenStore(), BffMobileClient.json),
        json = BffMobileClient.json,
    )

    @After
    fun tearDown() {
        database.close()
        TestAppContainer.deleteTestKey(TestAppContainer.SENSORS_TEST_KEY_ALIAS)
        TestAppContainer.deleteTestKey(OTHER_KEY_ALIAS)
    }

    private fun ByteArray.latin1() = toString(Charsets.ISO_8859_1)

    @Test
    fun laVinculacionSeGuardaCifradaSinDireccionNiNombreEnClaroYSeRecupera() = runBlocking {
        dao.upsertDevice(SensorDeviceEntity(owner, deviceId, codec.encodeDevice(stored())))

        val raw = dao.devices(owner).single()
        val clear = raw.payload.latin1()
        assertFalse("la dirección BLE no queda en claro", clear.contains(ADDRESS))
        assertFalse("la dirección BLE no queda en claro (sin separadores)", clear.contains(ADDRESS.replace(":", "")))
        assertFalse("el nombre no queda en claro", clear.contains(NAME))
        assertFalse("los campos no quedan en claro", clear.contains("address") || clear.contains("display_name"))
        assertEquals("solo la identidad lógica (hash) queda en claro", deviceId, raw.deviceId)

        assertEquals(stored(), codec.decodeDevice(raw))
    }

    @Test
    fun laMuestraGuardaElValorCifradoYSoloElInstanteEnClaro() = runBlocking {
        samples.record(owner, sessionId, deviceId, at.epochSecond, 173)

        val raw = dao.oldestSamples(owner, sessionId, deviceId, 10).single()
        assertEquals(at.epochSecond, raw.measuredAt)
        val clear = raw.payload.latin1()
        assertFalse("el valor en bpm no queda en claro", clear.contains("173"))
        assertFalse("la estructura no queda en claro", clear.contains("bpm"))
        assertEquals(173, codec.decodeSample(raw))
        assertEquals(listOf(at.epochSecond to 173), samples.oldest(owner, sessionId, deviceId, 10))
    }

    @Test
    fun laMismaSegundaSeConservaUnaSolaVezConLaPrimeraLectura() = runBlocking {
        samples.record(owner, sessionId, deviceId, at.epochSecond, 120)
        samples.record(owner, sessionId, deviceId, at.epochSecond, 150)
        samples.record(owner, sessionId, deviceId, at.epochSecond + 1, 121)

        assertEquals(2, samples.count(owner, sessionId))
        assertEquals(listOf(at.epochSecond to 120, at.epochSecond + 1 to 121), samples.oldest(owner, sessionId, deviceId, 60))

        samples.remove(sessionId, deviceId, listOf(at.epochSecond))
        assertEquals(listOf(at.epochSecond + 1 to 121), samples.oldest(owner, sessionId, deviceId, 60))
    }

    @Test
    fun lasMuestrasSeEntreganDeLasMasAntiguasHastaElLimite() = runBlocking {
        (0 until 70).reversed().forEach { samples.record(owner, sessionId, deviceId, at.epochSecond + it, 100 + it) }

        val batch = samples.oldest(owner, sessionId, deviceId, 60)
        assertEquals(60, batch.size)
        assertEquals((0 until 60).map { at.epochSecond + it }, batch.map { it.first })
    }

    @Test
    fun loQueNoPuedeDescifrarseSeTrataComoInexistente() = runBlocking {
        val foreign = SensorsCodec(KeystoreTokenCipher(OTHER_KEY_ALIAS), BffMobileClient.json)
        dao.upsertDevice(SensorDeviceEntity(owner, deviceId, foreign.encodeDevice(stored())))
        SensorSamples(dao, foreign).record(owner, sessionId, deviceId, at.epochSecond, 140)

        assertNull(codec.decodeDevice(dao.devices(owner).single()))
        assertEquals(emptyList<Any>(), repository().observe(owner).first())
        assertEquals("una muestra ilegible no se envía y se descarta", emptyList<Pair<Long, Int>>(), samples.oldest(owner, sessionId, deviceId, 60))
        assertEquals(0, samples.count(owner, sessionId))
    }

    @Test
    fun soloElDispositivoActivoConDireccionLocalEsConectableYRevocarloBorraSusMuestras() = runBlocking {
        val repository = repository()
        val elsewhere = DeviceIdHasher.deviceId("66:77:88:99:AA:BB", userId)
        dao.upsertDevice(SensorDeviceEntity(owner, elsewhere, codec.encodeDevice(stored(address = null, id = elsewhere))))
        assertNull("vinculado desde otro teléfono: no conectable", repository.observeConnectable(owner).first())

        dao.upsertDevice(SensorDeviceEntity(owner, deviceId, codec.encodeDevice(stored())))
        assertEquals(ConnectableDevice(deviceId, ADDRESS), repository.observeConnectable(owner).first())
        val linked = repository.observe(owner).first()
        assertEquals(2, linked.size)
        assertTrue(linked.single { it.deviceId == deviceId }.connectable)
        assertFalse(linked.single { it.deviceId == elsewhere }.hasLocalAddress)

        samples.record(owner, sessionId, deviceId, at.epochSecond, 130)
        repository.markRevoked(owner, deviceId)
        assertNull(repository.observeConnectable(owner).first())
        assertEquals(DeviceStatus.REVOKED, repository.observe(owner).first().single { it.deviceId == deviceId }.status)
        assertEquals(0, samples.count(owner, sessionId))
    }

    @Test
    fun alCerrarSesionOCambiarDeDeportistaNoQuedanDatosAjenos() = runBlocking {
        val repository = repository()
        dao.upsertDevice(SensorDeviceEntity(owner, deviceId, codec.encodeDevice(stored())))
        dao.upsertDevice(SensorDeviceEntity(otherOwner, deviceId, codec.encodeDevice(stored())))
        samples.record(owner, sessionId, deviceId, at.epochSecond, 130)
        samples.record(otherOwner, "otra-sesion", deviceId, at.epochSecond, 131)

        repository.clearAllExcept(owner)
        assertTrue(dao.devices(otherOwner).isEmpty())
        assertEquals(0, samples.count(otherOwner, "otra-sesion"))
        assertNotNull(repository.observeConnectable(owner).first())

        repository.clear(owner)
        assertTrue(dao.devices(owner).isEmpty())
        assertEquals(0, samples.count(owner, sessionId))
    }

    @Test
    fun lasMuestrasVencenConLaSesionYPorAntiguedad() = runBlocking {
        samples.record(owner, "sesion-anterior", deviceId, at.epochSecond, 110)
        samples.record(owner, sessionId, deviceId, at.epochSecond, 120)
        samples.record(owner, sessionId, deviceId, at.epochSecond + 900, 125)

        samples.dropOtherSessions(owner, sessionId)
        assertEquals(0, samples.count(owner, "sesion-anterior"))
        samples.expireBefore(at.epochSecond + 1)
        assertEquals(listOf(at.epochSecond + 900 to 125), samples.oldest(owner, sessionId, deviceId, 60))
        samples.dropSession(owner, sessionId)
        assertEquals(0, samples.count(owner, sessionId))
    }

    @Test
    fun laBaseQuedaExcluidaDelRespaldoYDeLaTransferencia() {
        val backup = excludedPaths(R.xml.backup_rules)
        val extraction = excludedPaths(R.xml.data_extraction_rules)
        val name = SensorsDatabase.NAME
        listOf(name, "$name-wal", "$name-shm", "$name-journal").forEach { file ->
            assertTrue("$file excluido del respaldo", "database:$file" in backup)
            assertTrue("$file excluido de la transferencia", "database:$file" in extraction)
        }
    }

    private fun excludedPaths(xml: Int): List<String> {
        val paths = mutableListOf<String>()
        context.resources.getXml(xml).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "exclude") {
                    paths += "${parser.getAttributeValue(null, "domain")}:${parser.getAttributeValue(null, "path")}"
                }
            }
        }
        return paths
    }

    private companion object {
        /** Dirección BLE sintética. */
        const val ADDRESS = "00:11:22:33:44:55"
        const val NAME = "Banda pecho sintetica"
        const val OTHER_KEY_ALIAS = "sportapp.sensors.test.other"
    }
}
