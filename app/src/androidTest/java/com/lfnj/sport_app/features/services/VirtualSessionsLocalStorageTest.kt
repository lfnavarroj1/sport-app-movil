package com.lfnj.sport_app.features.services

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lfnj.sport_app.R
import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.security.KeystoreTokenCipher
import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.services.data.VirtualSessionsCacheJanitor
import com.lfnj.sport_app.features.services.data.VirtualSessionsRepository
import com.lfnj.sport_app.features.services.data.local.VirtualSessionsAppointmentsEntity
import com.lfnj.sport_app.features.services.data.local.VirtualSessionsCatalogEntity
import com.lfnj.sport_app.features.services.data.local.VirtualSessionsCodec
import com.lfnj.sport_app.features.services.data.local.VirtualSessionsDatabase
import com.lfnj.sport_app.features.services.model.Appointment
import com.lfnj.sport_app.features.services.model.AppointmentStatus
import com.lfnj.sport_app.features.services.model.AppointmentsSnapshot
import com.lfnj.sport_app.features.services.model.AvailabilitySlot
import com.lfnj.sport_app.features.services.model.CatalogSnapshot
import com.lfnj.sport_app.features.services.model.CoachingService
import com.lfnj.sport_app.features.services.model.ServiceFee
import com.lfnj.sport_app.features.services.model.ServiceModality
import com.lfnj.sport_app.features.services.model.VirtualSessionsOwner
import com.lfnj.sport_app.testing.InMemoryTokenStore
import com.lfnj.sport_app.testing.TestAppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.time.Instant
import java.time.ZoneId

/**
 * HU040 en el dispositivo: Room real, cifrado AES-GCM con Android Keystore, partición por
 * deportista, reinicio del proceso, limpieza al cerrar sesión, esquema exportado y exclusión del
 * respaldo (ADR-046 §8).
 */
@RunWith(AndroidJUnit4::class)
class VirtualSessionsLocalStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val ana = SessionUser("7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b", "Ana Deportista", "ana@example.com", ActorType.ATHLETE)
    private val owner = VirtualSessionsOwner.keyOf(ana.userId)
    private val otherOwner = VirtualSessionsOwner.keyOf("0f0e0d0c-0b0a-4908-8706-050403020100")
    private var database = openDatabase()

    @get:Rule
    val migrationHelper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), VirtualSessionsDatabase::class.java)

    private fun openDatabase() = Room.databaseBuilder(context, VirtualSessionsDatabase::class.java, DB_NAME).build()

    private fun codec() = VirtualSessionsCodec(KeystoreTokenCipher(TestAppContainer.VIRTUAL_SESSIONS_TEST_KEY_ALIAS), BffMobileClient.json)

    /** Un repositorio nuevo equivale a un proceso nuevo: no conserva nada en memoria. */
    private fun repository(db: VirtualSessionsDatabase = database) = VirtualSessionsRepository(
        dao = db.virtualSessionsCacheDao(),
        codec = codec(),
        api = null,
        sessionManager = SessionManager(null, InMemoryTokenStore(), BffMobileClient.json),
        json = BffMobileClient.json,
    )

    private val fee = ServiceFee("120000.00", "COP", "SESSION")
    private val catalog = CatalogSnapshot(
        services = listOf(
            CoachingService(
                serviceId = "3f2a1b0c-9d8e-4f7a-8b6c-5d4e3f2a1b0c", providerId = "9e8d7c6b-5a4f-4e3d-8c2b-1a0f9e8d7c6b",
                name = "Asesoría de ritmo para 10K", description = "Revisión de objetivos.", modality = ServiceModality.HYBRID, fee = fee,
                slots = listOf(
                    AvailabilitySlot("11111111-2222-4333-8444-555555555555", Instant.parse("2026-10-28T15:00:00Z"), Instant.parse("2026-10-28T16:00:00Z"), ZoneId.of("Europe/Madrid"), 3),
                ),
            ),
        ),
        truncated = false,
        syncedAt = Instant.parse("2026-10-25T13:00:05Z"),
    )
    private val appointments = AppointmentsSnapshot(
        items = listOf(
            Appointment(
                appointmentId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", serviceId = "3f2a1b0c-9d8e-4f7a-8b6c-5d4e3f2a1b0c",
                availabilityId = "11111111-2222-4333-8444-555555555555", providerId = "9e8d7c6b-5a4f-4e3d-8c2b-1a0f9e8d7c6b",
                serviceName = "Asesoría de ritmo para 10K", modality = "HYBRID", fee = fee,
                startsAt = Instant.parse("2026-10-28T15:00:00Z"), endsAt = Instant.parse("2026-10-28T16:00:00Z"),
                timeZone = ZoneId.of("Europe/Madrid"), status = AppointmentStatus.CANCELLED_BY_ATHLETE,
                bookedAt = Instant.parse("2026-10-25T12:00:00Z"), cancelledAt = Instant.parse("2026-10-25T12:30:00Z"), version = 2,
            ),
        ),
        syncedAt = Instant.parse("2026-10-25T13:00:06Z"),
    )

    private suspend fun save(target: String = owner, db: VirtualSessionsDatabase = database) {
        db.virtualSessionsCacheDao().replaceCatalog(VirtualSessionsCatalogEntity(target, codec().encodeCatalog(catalog)))
        db.virtualSessionsCacheDao().replaceAppointments(VirtualSessionsAppointmentsEntity(target, codec().encodeAppointments(appointments)))
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DB_NAME)
        context.deleteDatabase(MIGRATION_DB)
        TestAppContainer.deleteTestKey(TestAppContainer.VIRTUAL_SESSIONS_TEST_KEY_ALIAS)
    }

    @Test
    fun laCacheSoloGuardaTextoCifradoYLaParticion() = runBlocking {
        save()

        val db = database.openHelper.readableDatabase
        listOf("virtual_sessions_catalog", "virtual_sessions_appointments").forEach { table ->
            val columns = db.query("SELECT * FROM $table").use { it.columnNames.toSet() }
            assertEquals(setOf("owner_key", "payload"), columns)
            val blob = db.query("SELECT payload FROM $table").use { it.moveToFirst(); it.getBlob(0) }
            val text = String(blob, Charsets.ISO_8859_1)
            listOf("Asesor", "120000", "Madrid", "CANCELLED", "provider", "last_synced_at").forEach {
                assertFalse("«$it» en claro en $table", text.contains(it))
            }
        }
        assertEquals(catalog, repository().observeCatalog(owner).first())
        assertEquals(appointments, repository().observeAppointments(owner).first())
    }

    @Test
    fun loConfirmadoSobreviveAlReinicioConSuAntiguedad() = runBlocking {
        save()
        database.close()

        database = openDatabase()
        val restored = checkNotNull(repository().observeAppointments(owner).first())

        assertEquals(appointments, restored)
        assertTrue(restored.isStale(appointments.syncedAt.plusSeconds(24 * 3600 + 1)))
        assertEquals(catalog, repository().observeCatalog(owner).first())
    }

    @Test
    fun cadaDeportistaSoloVeSuParticion() = runBlocking {
        save()

        assertNull(repository().observeCatalog(otherOwner).first())
        assertNull(repository().observeAppointments(otherOwner).first())
    }

    @Test
    fun conLaClaveInvalidadaLaCacheSeIgnoraSinInventarDatos() = runBlocking {
        save()
        TestAppContainer.deleteTestKey(TestAppContainer.VIRTUAL_SESSIONS_TEST_KEY_ALIAS)

        assertNull(repository().observeCatalog(owner).first())
        assertNull(repository().observeAppointments(owner).first())
    }

    @Test
    fun cerrarSesionEliminaLaCacheYOtroDeportistaNoReutilizaLaAnterior() = runBlocking {
        save(owner)
        save(otherOwner)
        val user = MutableStateFlow<SessionUser?>(ana)
        val scope = CoroutineScope(Dispatchers.Default)
        VirtualSessionsCacheJanitor(user, repository()).start(scope)
        val dao = database.virtualSessionsCacheDao()

        withTimeout(5_000) { while (dao.readCatalog(otherOwner) != null || dao.readAppointments(otherOwner) != null) delay(50) }
        assertTrue(dao.readCatalog(owner) != null)

        user.value = null
        withTimeout(5_000) { while (dao.readCatalog(owner) != null || dao.readAppointments(owner) != null) delay(50) }
        scope.cancel()
    }

    @Test
    fun elEsquemaCreadoCoincideConElExportadoDeLaVersion1() {
        migrationHelper.createDatabase(MIGRATION_DB, 1).close()

        migrationHelper.runMigrationsAndValidate(MIGRATION_DB, 1, true).close()
    }

    @Test
    fun laCacheSeExcluyeDelRespaldoYLaTransferencia() {
        val backup = excludedPaths(R.xml.backup_rules)
        val extraction = excludedPaths(R.xml.data_extraction_rules)
        val name = VirtualSessionsDatabase.NAME

        listOf(name, "$name-wal", "$name-shm", "$name-journal").forEach { file ->
            assertTrue("$file en backup_rules", "database:$file" in backup)
            assertEquals("$file en nube y transferencia", 2, extraction.count { it == "database:$file" })
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
        const val DB_NAME = "sportapp_virtual_sessions_test.db"
        const val MIGRATION_DB = "sportapp_virtual_sessions_migration_test.db"
    }
}
