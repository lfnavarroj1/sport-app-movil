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
import com.lfnj.sport_app.features.services.data.SupportSuggestionsCacheJanitor
import com.lfnj.sport_app.features.services.data.SupportSuggestionsRepository
import com.lfnj.sport_app.features.services.data.local.SupportSuggestionsCacheEntity
import com.lfnj.sport_app.features.services.data.local.SupportSuggestionsCodec
import com.lfnj.sport_app.features.services.data.local.SupportSuggestionsDatabase
import com.lfnj.sport_app.features.services.model.ServiceFee
import com.lfnj.sport_app.features.services.model.SupportInterval
import com.lfnj.sport_app.features.services.model.SupportServiceSuggestion
import com.lfnj.sport_app.features.services.model.SupportSourceStatus
import com.lfnj.sport_app.features.services.model.SupportSuggestionsOwner
import com.lfnj.sport_app.features.services.model.SupportSuggestionsSnapshot
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
 * HU029 en el dispositivo: Room real, cifrado AES-GCM con Android Keystore, partición por
 * deportista, reinicio del proceso, limpieza al cerrar sesión, esquema exportado y exclusión del
 * respaldo (ADR-041 §5).
 */
@RunWith(AndroidJUnit4::class)
class SupportSuggestionsLocalStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val ana = SessionUser("7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b", "Ana Deportista", "ana@example.com", ActorType.ATHLETE)
    private val owner = SupportSuggestionsOwner.keyOf(ana.userId)
    private val otherOwner = SupportSuggestionsOwner.keyOf("0f0e0d0c-0b0a-4908-8706-050403020100")
    private var database = openDatabase()

    @get:Rule
    val migrationHelper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SupportSuggestionsDatabase::class.java)

    private fun openDatabase() = Room.databaseBuilder(context, SupportSuggestionsDatabase::class.java, DB_NAME).build()

    private fun codec() = SupportSuggestionsCodec(KeystoreTokenCipher(TestAppContainer.SUPPORT_SUGGESTIONS_TEST_KEY_ALIAS), BffMobileClient.json)

    /** Un repositorio nuevo equivale a un proceso nuevo: no conserva nada en memoria. */
    private fun repository(db: SupportSuggestionsDatabase = database) = SupportSuggestionsRepository(
        dao = db.supportSuggestionsCacheDao(),
        codec = codec(),
        api = null,
        sessionManager = SessionManager(null, InMemoryTokenStore(), BffMobileClient.json),
        json = BffMobileClient.json,
    )

    private val snapshot = SupportSuggestionsSnapshot(
        items = listOf(
            SupportServiceSuggestion(
                serviceId = "4d3c2b1a-0f9e-4d8c-9b7a-6f5e4d3c2b1a",
                title = "Transporte al Medio Maratón",
                summary = "Recogida en el punto de encuentro y regreso después de la carrera.",
                category = "TRANSPORT",
                modality = "IN_PERSON",
                fee = ServiceFee("80000.00", "COP", "SERVICE"),
                nextInterval = SupportInterval(Instant.parse("2026-11-08T10:00:00Z"), Instant.parse("2026-11-08T12:00:00Z"), ZoneId.of("America/Bogota")),
                authorName = "Carlos Rodríguez",
                reasons = listOf("EVENT_LOGISTICS"),
            ),
        ),
        personalized = true,
        missingContext = emptyList(),
        generatedAt = Instant.parse("2026-10-25T13:00:00Z"),
        eventsSource = SupportSourceStatus.OK,
        usersSource = SupportSourceStatus.UNAVAILABLE,
        syncedAt = Instant.parse("2026-10-25T13:00:05Z"),
    )

    private suspend fun save(target: String = owner, db: SupportSuggestionsDatabase = database) =
        db.supportSuggestionsCacheDao().replace(SupportSuggestionsCacheEntity(target, codec().encode(snapshot)))

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DB_NAME)
        context.deleteDatabase(MIGRATION_DB)
        TestAppContainer.deleteTestKey(TestAppContainer.SUPPORT_SUGGESTIONS_TEST_KEY_ALIAS)
    }

    @Test
    fun laCacheSoloGuardaTextoCifradoYLaParticion() = runBlocking {
        save()

        val db = database.openHelper.readableDatabase
        val columns = db.query("SELECT * FROM support_suggestions_cache").use { it.columnNames.toSet() }
        assertEquals(setOf("owner_key", "payload"), columns)
        val blob = db.query("SELECT payload FROM support_suggestions_cache").use { it.moveToFirst(); it.getBlob(0) }
        val text = String(blob, Charsets.ISO_8859_1)
        listOf("Transporte", "Carlos", "80000", "TRANSPORT", "EVENT_LOGISTICS", "America/Bogota", "last_synced_at").forEach {
            assertFalse("«$it» en claro", text.contains(it))
        }
        assertEquals(snapshot, repository().observe(owner).first())
    }

    @Test
    fun laListaConfirmadaSobreviveAlReinicioConSuAntiguedadYFuentes() = runBlocking {
        save()
        database.close()

        database = openDatabase()
        val restored = checkNotNull(repository().observe(owner).first())

        assertEquals(snapshot.syncedAt, restored.syncedAt)
        assertEquals(snapshot.items, restored.items)
        assertEquals(SupportSourceStatus.UNAVAILABLE, restored.usersSource)
        assertTrue(restored.isStale(snapshot.syncedAt.plusSeconds(24 * 3600 + 1)))
    }

    @Test
    fun cadaDeportistaSoloVeSuParticion() = runBlocking {
        save()

        assertNull(repository().observe(otherOwner).first())
    }

    @Test
    fun conLaClaveInvalidadaLaCacheSeIgnoraSinInventarServicios() = runBlocking {
        save()
        TestAppContainer.deleteTestKey(TestAppContainer.SUPPORT_SUGGESTIONS_TEST_KEY_ALIAS)

        assertNull(repository().observe(owner).first())
    }

    @Test
    fun cerrarSesionEliminaLaCacheYOtroDeportistaNoReutilizaLaAnterior() = runBlocking {
        save(owner)
        save(otherOwner)
        val user = MutableStateFlow<SessionUser?>(ana)
        val scope = CoroutineScope(Dispatchers.Default)
        SupportSuggestionsCacheJanitor(user, repository()).start(scope)
        val dao = database.supportSuggestionsCacheDao()

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
        val name = SupportSuggestionsDatabase.NAME

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
        const val DB_NAME = "sportapp_support_suggestions_test.db"
        const val MIGRATION_DB = "sportapp_support_suggestions_migration_test.db"
    }
}
