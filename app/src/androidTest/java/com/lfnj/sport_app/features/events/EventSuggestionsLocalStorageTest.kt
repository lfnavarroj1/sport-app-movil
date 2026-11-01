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
import com.lfnj.sport_app.features.events.data.EventSuggestionsCacheJanitor
import com.lfnj.sport_app.features.events.data.EventSuggestionsRepository
import com.lfnj.sport_app.features.events.data.local.EventSuggestionsCacheEntity
import com.lfnj.sport_app.features.events.data.local.EventSuggestionsCodec
import com.lfnj.sport_app.features.events.data.local.EventSuggestionsDatabase
import com.lfnj.sport_app.features.events.model.EventPlace
import com.lfnj.sport_app.features.events.model.EventPrice
import com.lfnj.sport_app.features.events.model.EventSuggestion
import com.lfnj.sport_app.features.events.model.EventSuggestionsOwner
import com.lfnj.sport_app.features.events.model.EventSuggestionsSnapshot
import com.lfnj.sport_app.features.events.model.MyRegistration
import com.lfnj.sport_app.features.recommendations.model.ContextSourceStatus
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
 * HU027 en el dispositivo: Room real, cifrado AES-GCM con Android Keystore, partición por
 * deportista, reinicio del proceso, limpieza al cerrar sesión, esquema exportado y exclusión del
 * respaldo (ADR-041 §5).
 */
@RunWith(AndroidJUnit4::class)
class EventSuggestionsLocalStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val ana = SessionUser("7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b", "Ana Deportista", "ana@example.com", ActorType.ATHLETE)
    private val owner = EventSuggestionsOwner.keyOf(ana.userId)
    private val otherOwner = EventSuggestionsOwner.keyOf("0f0e0d0c-0b0a-4908-8706-050403020100")
    private var database = openDatabase()

    @get:Rule
    val migrationHelper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), EventSuggestionsDatabase::class.java)

    private fun openDatabase() = Room.databaseBuilder(context, EventSuggestionsDatabase::class.java, DB_NAME).build()

    private fun codec() = EventSuggestionsCodec(KeystoreTokenCipher(TestAppContainer.EVENT_SUGGESTIONS_TEST_KEY_ALIAS), BffMobileClient.json)

    /** Un repositorio nuevo equivale a un proceso nuevo: no conserva nada en memoria. */
    private fun repository(db: EventSuggestionsDatabase = database) = EventSuggestionsRepository(
        dao = db.eventSuggestionsCacheDao(),
        codec = codec(),
        api = null,
        sessionManager = SessionManager(null, InMemoryTokenStore(), BffMobileClient.json),
        json = BffMobileClient.json,
    )

    private val snapshot = EventSuggestionsSnapshot(
        items = listOf(
            EventSuggestion(
                eventId = "3f2a1b0c-9d8e-4f7a-8b6c-5d4e3f2a1b0c", title = "Media maratón de Bogotá", summary = "Recorrido urbano de 21 km.",
                discipline = "Atletismo", location = EventPlace("Parque Simón Bolívar", "Bogotá", "CO"),
                startsAt = Instant.parse("2026-11-15T12:00:00Z"), endsAt = Instant.parse("2026-11-15T17:00:00Z"),
                timeZone = "America/Bogota", capacity = 500, fee = EventPrice("45000.00", "COP"),
                myRegistration = MyRegistration("5e6f7a8b-9c0d-4e1f-a2b3-c4d5e6f7a8b9", Instant.parse("2026-10-21T10:00:00Z")),
                reasons = listOf("SPORT_MATCH", "CITY_MATCH"),
            ),
            EventSuggestion(
                eventId = "8a7b6c5d-4e3f-4a1b-9c0d-1e2f3a4b5c6d", title = "Gran fondo La Calera", summary = "",
                discipline = "Ciclismo", location = EventPlace("Plaza principal", "La Calera", "CO"),
                startsAt = Instant.parse("2026-11-20T11:00:00Z"), endsAt = Instant.parse("2026-11-20T18:00:00Z"),
                timeZone = "America/Bogota", capacity = 120, fee = null, myRegistration = null, reasons = listOf("SPORT_MATCH"),
            ),
        ),
        personalized = true,
        missingContext = emptyList(),
        generatedAt = Instant.parse("2026-10-25T13:00:00Z"),
        usersSource = ContextSourceStatus.OK,
        syncedAt = Instant.parse("2026-10-25T13:00:05Z"),
    )

    private suspend fun save(target: String = owner, db: EventSuggestionsDatabase = database) =
        db.eventSuggestionsCacheDao().replace(EventSuggestionsCacheEntity(target, codec().encode(snapshot)))

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DB_NAME)
        context.deleteDatabase(MIGRATION_DB)
        TestAppContainer.deleteTestKey(TestAppContainer.EVENT_SUGGESTIONS_TEST_KEY_ALIAS)
    }

    @Test
    fun laCacheSoloGuardaTextoCifradoYLaParticion() = runBlocking {
        save()

        val db = database.openHelper.readableDatabase
        val columns = db.query("SELECT * FROM event_suggestions_cache").use { it.columnNames.toSet() }
        assertEquals(setOf("owner_key", "payload"), columns)
        val blob = db.query("SELECT payload FROM event_suggestions_cache").use { it.moveToFirst(); it.getBlob(0) }
        val text = String(blob, Charsets.ISO_8859_1)
        listOf("Media marat", "Parque", "Bogot", "45000", "CITY_MATCH", "registration_id", "last_synced_at").forEach {
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
        assertTrue(restored.items[0].registered)
        assertNull(restored.items[1].fee)
        assertTrue(restored.isStale(snapshot.syncedAt.plusSeconds(24 * 3600 + 1)))
    }

    @Test
    fun cadaDeportistaSoloVeSuParticion() = runBlocking {
        save()

        assertNull(repository().observe(otherOwner).first())
    }

    @Test
    fun conLaClaveInvalidadaLaCacheSeIgnoraSinInventarEventos() = runBlocking {
        save()
        TestAppContainer.deleteTestKey(TestAppContainer.EVENT_SUGGESTIONS_TEST_KEY_ALIAS)

        assertNull(repository().observe(owner).first())
    }

    @Test
    fun cerrarSesionEliminaLaCacheYOtroDeportistaNoReutilizaLaAnterior() = runBlocking {
        save(owner)
        save(otherOwner)
        val user = MutableStateFlow<SessionUser?>(ana)
        val scope = CoroutineScope(Dispatchers.Default)
        EventSuggestionsCacheJanitor(user, repository()).start(scope)
        val dao = database.eventSuggestionsCacheDao()

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
        val name = EventSuggestionsDatabase.NAME

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
        const val DB_NAME = "sportapp_event_suggestions_test.db"
        const val MIGRATION_DB = "sportapp_event_suggestions_migration_test.db"
    }
}
