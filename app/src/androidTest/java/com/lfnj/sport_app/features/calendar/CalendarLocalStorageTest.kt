package com.lfnj.sport_app.features.calendar

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
import com.lfnj.sport_app.features.calendar.data.CalendarCacheJanitor
import com.lfnj.sport_app.features.calendar.data.CalendarRepository
import com.lfnj.sport_app.features.calendar.data.local.CalendarCacheCodec
import com.lfnj.sport_app.features.calendar.data.local.CalendarCacheEntity
import com.lfnj.sport_app.features.calendar.data.local.CalendarDatabase
import com.lfnj.sport_app.features.calendar.model.CalendarItem
import com.lfnj.sport_app.features.calendar.model.CalendarOwner
import com.lfnj.sport_app.features.calendar.model.CalendarSnapshot
import com.lfnj.sport_app.features.calendar.model.CalendarSourceState
import com.lfnj.sport_app.features.calendar.model.EventLocation
import com.lfnj.sport_app.features.calendar.model.SourceStatus
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
import java.time.LocalDate
import java.time.YearMonth

/**
 * HU035 en el dispositivo: Room real, cifrado AES-GCM con Android Keystore, partición por
 * deportista, reinicio del proceso, limpieza al cerrar sesión, esquema exportado y exclusión del
 * respaldo (ADR-031, política offline).
 */
@RunWith(AndroidJUnit4::class)
class CalendarLocalStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val ana = SessionUser("7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b", "Ana Deportista", "ana@example.com", ActorType.ATHLETE)
    private val owner = CalendarOwner.keyOf(ana.userId)
    private val otherOwner = CalendarOwner.keyOf("0f0e0d0c-0b0a-4908-8706-050403020100")
    private var database = openDatabase()

    @get:Rule
    val migrationHelper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), CalendarDatabase::class.java)

    private fun openDatabase() = Room.databaseBuilder(context, CalendarDatabase::class.java, DB_NAME).build()

    private fun codec() = CalendarCacheCodec(KeystoreTokenCipher(TestAppContainer.CALENDAR_TEST_KEY_ALIAS), BffMobileClient.json)

    /** Un repositorio nuevo equivale a un proceso nuevo: no conserva nada en memoria. */
    private fun repository(db: CalendarDatabase = database) = CalendarRepository(
        dao = db.calendarCacheDao(),
        codec = codec(),
        api = null,
        sessionManager = SessionManager(null, InMemoryTokenStore(), BffMobileClient.json),
        json = BffMobileClient.json,
    )

    private val snapshot = CalendarSnapshot(
        month = YearMonth.of(2026, 11),
        from = Instant.parse("2026-11-01T05:00:00Z"),
        to = Instant.parse("2026-12-01T04:59:59Z"),
        items = listOf(
            CalendarItem.Event(
                "EVENT_PUBLISHED:683f4e38-6bb3-4c47-a6dc-9bb1588c67ef", LocalDate.of(2026, 11, 15), "Carrera 10K Bogotá", "Running",
                Instant.parse("2026-11-15T12:00:00Z"), Instant.parse("2026-11-15T15:00:00Z"), "America/Bogota",
                EventLocation("Parque Metropolitano Simón Bolívar", "Bogotá", "CO"),
            ),
            CalendarItem.PlannedSession("TRAINING_PLAN_SESSION:0d1c3e4a", LocalDate.of(2026, 11, 16), "Base aeróbica noviembre", "running", 45, 4),
        ),
        sources = listOf(CalendarSourceState("events", SourceStatus.OK, false), CalendarSourceState("training", SourceStatus.TIMEOUT, true)),
        evaluatedAt = Instant.parse("2026-10-12T22:00:00Z"),
        syncedAt = Instant.parse("2026-10-12T22:00:05Z"),
    )

    private suspend fun save(target: String = owner, db: CalendarDatabase = database) =
        db.calendarCacheDao().replace(CalendarCacheEntity(target, codec().encode(snapshot)))

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DB_NAME)
        context.deleteDatabase(MIGRATION_DB)
        TestAppContainer.deleteTestKey(TestAppContainer.CALENDAR_TEST_KEY_ALIAS)
    }

    @Test
    fun laCacheSoloGuardaTextoCifradoYLaParticion() = runBlocking {
        save()

        val db = database.openHelper.readableDatabase
        val columns = db.query("SELECT * FROM calendar_cache").use { it.columnNames.toSet() }
        assertEquals(setOf("owner_key", "payload"), columns)
        val blob = db.query("SELECT payload FROM calendar_cache").use { it.moveToFirst(); it.getBlob(0) }
        val text = String(blob, Charsets.ISO_8859_1)
        listOf("Carrera", "Base aer", "2026", "Bogota", "Parque", "EVENT_PUBLISHED", "last_synced_at").forEach {
            assertFalse("«$it» en claro", text.contains(it))
        }
        assertEquals(snapshot, repository().observe(owner).first())
    }

    @Test
    fun elPeriodoConfirmadoSobreviveAlReinicioConSuAntiguedad() = runBlocking {
        save()
        database.close()

        database = openDatabase()
        val restored = checkNotNull(repository().observe(owner).first())

        assertEquals(snapshot.syncedAt, restored.syncedAt)
        assertEquals(snapshot.items, restored.items)
        assertEquals(listOf("training"), restored.incompleteSources)
        assertEquals(listOf("training"), restored.truncatedSources)
    }

    @Test
    fun cadaDeportistaSoloVeSuParticion() = runBlocking {
        save()

        assertNull(repository().observe(otherOwner).first())
    }

    @Test
    fun conLaClaveInvalidadaLaCacheSeIgnoraSinInventarElementos() = runBlocking {
        save()
        TestAppContainer.deleteTestKey(TestAppContainer.CALENDAR_TEST_KEY_ALIAS)

        assertNull(repository().observe(owner).first())
    }

    @Test
    fun cerrarSesionEliminaLaCacheYOtroDeportistaNoReutilizaLaAnterior() = runBlocking {
        save(owner)
        save(otherOwner)
        val user = MutableStateFlow<SessionUser?>(ana)
        val scope = CoroutineScope(Dispatchers.Default)
        CalendarCacheJanitor(user, repository()).start(scope)
        val dao = database.calendarCacheDao()

        // Al estar autenticada Ana, se elimina la partición que otro deportista dejó en el dispositivo.
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

        listOf(CalendarDatabase.NAME, "${CalendarDatabase.NAME}-wal", "${CalendarDatabase.NAME}-shm", "${CalendarDatabase.NAME}-journal").forEach { file ->
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
        const val DB_NAME = "sportapp_calendar_test.db"
        const val MIGRATION_DB = "sportapp_calendar_migration_test.db"
    }
}
