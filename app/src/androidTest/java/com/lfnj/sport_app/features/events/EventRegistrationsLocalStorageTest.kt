package com.lfnj.sport_app.features.events

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
import com.lfnj.sport_app.features.events.data.EventRegistrationRepository
import com.lfnj.sport_app.features.events.data.EventRegistrationsCacheJanitor
import com.lfnj.sport_app.features.events.data.local.EventRegistrationsCacheEntity
import com.lfnj.sport_app.features.events.data.local.EventRegistrationsCodec
import com.lfnj.sport_app.features.events.data.local.EventRegistrationsDatabase
import com.lfnj.sport_app.features.events.model.EventPlace
import com.lfnj.sport_app.features.events.model.EventPrice
import com.lfnj.sport_app.features.events.model.EventRegistration
import com.lfnj.sport_app.features.events.model.EventRegistrationsOwner
import com.lfnj.sport_app.features.events.model.RegisteredEvent
import com.lfnj.sport_app.features.events.model.RegistrationsSnapshot
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

/**
 * HU041 en el dispositivo: Room real, cifrado AES-GCM con Android Keystore, partición por
 * deportista, reinicio del proceso, limpieza al cerrar sesión, esquema exportado y exclusión del
 * respaldo (ADR-032 §9).
 */
@RunWith(AndroidJUnit4::class)
class EventRegistrationsLocalStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val ana = SessionUser("7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b", "Ana Deportista", "ana@example.com", ActorType.ATHLETE)
    private val owner = EventRegistrationsOwner.keyOf(ana.userId)
    private val otherOwner = EventRegistrationsOwner.keyOf("0f0e0d0c-0b0a-4908-8706-050403020100")
    private var database = openDatabase()

    @get:Rule
    val migrationHelper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), EventRegistrationsDatabase::class.java)

    private fun openDatabase() = Room.databaseBuilder(context, EventRegistrationsDatabase::class.java, DB_NAME).build()

    private fun codec() = EventRegistrationsCodec(KeystoreTokenCipher(TestAppContainer.EVENT_REGISTRATIONS_TEST_KEY_ALIAS), BffMobileClient.json)

    /** Un repositorio nuevo equivale a un proceso nuevo: no conserva nada en memoria. */
    private fun repository(db: EventRegistrationsDatabase = database) = EventRegistrationRepository(
        dao = db.eventRegistrationsCacheDao(),
        codec = codec(),
        api = null,
        sessionManager = SessionManager(null, InMemoryTokenStore(), BffMobileClient.json),
        json = BffMobileClient.json,
    )

    private val snapshot = RegistrationsSnapshot(
        items = listOf(
            EventRegistration(
                registrationId = "6d2f0c8e-4a1b-4f3e-9c7d-2b8a5e1f0d43",
                eventId = "683f4e38-6bb3-4c47-a6dc-9bb1588c67ef",
                registeredAt = Instant.parse("2026-10-12T22:30:00Z"),
                appliedFee = EventPrice("85000.00", "COP"),
                version = 1,
                event = RegisteredEvent(
                    "Carrera 10K Bogotá", Instant.parse("2026-11-15T12:00:00Z"), Instant.parse("2026-11-15T15:00:00Z"),
                    "America/Bogota", EventPlace("Parque Metropolitano Simón Bolívar", "Bogotá", "CO"),
                ),
            ),
        ),
        nextCursor = "cursor-opaco",
        syncedAt = Instant.parse("2026-10-12T22:31:00Z"),
    )

    private suspend fun save(target: String = owner, db: EventRegistrationsDatabase = database) =
        db.eventRegistrationsCacheDao().replace(EventRegistrationsCacheEntity(target, codec().encode(snapshot)))

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DB_NAME)
        context.deleteDatabase(MIGRATION_DB)
        TestAppContainer.deleteTestKey(TestAppContainer.EVENT_REGISTRATIONS_TEST_KEY_ALIAS)
    }

    @Test
    fun laCacheSoloGuardaTextoCifradoYLaParticion() = runBlocking {
        save()

        val db = database.openHelper.readableDatabase
        val columns = db.query("SELECT * FROM event_registrations_cache").use { it.columnNames.toSet() }
        assertEquals(setOf("owner_key", "payload"), columns)
        val blob = db.query("SELECT payload FROM event_registrations_cache").use { it.moveToFirst(); it.getBlob(0) }
        val text = String(blob, Charsets.ISO_8859_1)
        listOf("Carrera", "Parque", "Bogot", "683f4e38", "6d2f0c8e", "85000", "cursor", "last_synced_at").forEach {
            assertFalse("«$it» en claro", text.contains(it))
        }
        assertEquals(snapshot, repository().observe(owner).first())
    }

    @Test
    fun laListaConfirmadaSobreviveAlReinicioConSuAntiguedad() = runBlocking {
        save()
        database.close()

        database = openDatabase()
        val restored = checkNotNull(repository().observe(owner).first())

        assertEquals(snapshot.syncedAt, restored.syncedAt)
        assertEquals(snapshot.items, restored.items)
        assertEquals("cursor-opaco", restored.nextCursor)
    }

    @Test
    fun cadaDeportistaSoloVeSuParticion() = runBlocking {
        save()

        assertNull(repository().observe(otherOwner).first())
    }

    @Test
    fun conLaClaveInvalidadaLaCacheSeIgnoraSinInventarInscripciones() = runBlocking {
        save()
        TestAppContainer.deleteTestKey(TestAppContainer.EVENT_REGISTRATIONS_TEST_KEY_ALIAS)

        assertNull(repository().observe(owner).first())
    }

    @Test
    fun cerrarSesionEliminaLaCacheYOtroDeportistaNoReutilizaLaAnterior() = runBlocking {
        save(owner)
        save(otherOwner)
        val user = MutableStateFlow<SessionUser?>(ana)
        val scope = CoroutineScope(Dispatchers.Default)
        EventRegistrationsCacheJanitor(user, repository()).start(scope)
        val dao = database.eventRegistrationsCacheDao()

        withTimeout(5_000) { while (dao.observe(otherOwner).first() != null) delay(50) }
        assertTrue(dao.observe(owner).first() != null)

        user.value = null
        withTimeout(5_000) { while (dao.observe(owner).first() != null) delay(50) }
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
        val name = EventRegistrationsDatabase.NAME

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
        const val DB_NAME = "sportapp_event_registrations_test.db"
        const val MIGRATION_DB = "sportapp_event_registrations_migration_test.db"
    }
}
