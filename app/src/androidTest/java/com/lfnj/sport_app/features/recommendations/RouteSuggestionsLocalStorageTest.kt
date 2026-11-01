package com.lfnj.sport_app.features.recommendations

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
import com.lfnj.sport_app.features.recommendations.data.RouteSuggestionsCacheJanitor
import com.lfnj.sport_app.features.recommendations.data.RouteSuggestionsRepository
import com.lfnj.sport_app.features.recommendations.data.local.RouteSuggestionsCacheEntity
import com.lfnj.sport_app.features.recommendations.data.local.RouteSuggestionsCodec
import com.lfnj.sport_app.features.recommendations.data.local.RouteSuggestionsDatabase
import com.lfnj.sport_app.features.recommendations.model.ContextSourceStatus
import com.lfnj.sport_app.features.recommendations.model.RoutePoint
import com.lfnj.sport_app.features.recommendations.model.RouteSuggestion
import com.lfnj.sport_app.features.recommendations.model.RouteSuggestionsOwner
import com.lfnj.sport_app.features.recommendations.model.RouteSuggestionsSnapshot
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
 * HU026 en el dispositivo: Room real, cifrado AES-GCM con Android Keystore, partición por
 * deportista, reinicio del proceso, limpieza al cerrar sesión, esquema exportado y exclusión del
 * respaldo (ADR-041 §5).
 */
@RunWith(AndroidJUnit4::class)
class RouteSuggestionsLocalStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val ana = SessionUser("7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b", "Ana Deportista", "ana@example.com", ActorType.ATHLETE)
    private val owner = RouteSuggestionsOwner.keyOf(ana.userId)
    private val otherOwner = RouteSuggestionsOwner.keyOf("0f0e0d0c-0b0a-4908-8706-050403020100")
    private var database = openDatabase()

    @get:Rule
    val migrationHelper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), RouteSuggestionsDatabase::class.java)

    private fun openDatabase() = Room.databaseBuilder(context, RouteSuggestionsDatabase::class.java, DB_NAME).build()

    private fun codec() = RouteSuggestionsCodec(KeystoreTokenCipher(TestAppContainer.ROUTE_SUGGESTIONS_TEST_KEY_ALIAS), BffMobileClient.json)

    /** Un repositorio nuevo equivale a un proceso nuevo: no conserva nada en memoria. */
    private fun repository(db: RouteSuggestionsDatabase = database) = RouteSuggestionsRepository(
        dao = db.routeSuggestionsCacheDao(),
        codec = codec(),
        api = null,
        sessionManager = SessionManager(null, InMemoryTokenStore(), BffMobileClient.json),
        json = BffMobileClient.json,
    )

    private val snapshot = RouteSuggestionsSnapshot(
        items = listOf(
            RouteSuggestion(
                routeId = "6b1d2c3e-4f5a-4b6c-8d7e-9f0a1b2c3d4e", title = "Circuito Parque Simón Bolívar",
                summary = "Recorrido plano alrededor del lago.", sport = "running", distanceKm = "5.0", difficulty = "LOW",
                city = "Bogotá", countryCode = "CO", startPoint = RoutePoint(4.6097, -74.0817),
                waypoints = listOf(RoutePoint(4.6584, -74.0937), RoutePoint(4.6601, -74.0952)),
                publishedAt = Instant.parse("2026-10-20T14:00:00Z"), authorName = "Carlos Ruiz", reasons = listOf("SPORT_MATCH", "CITY_MATCH"),
            ),
        ),
        personalized = true,
        missingContext = listOf("city"),
        generatedAt = Instant.parse("2026-10-25T13:00:00Z"),
        usersSource = ContextSourceStatus.OK,
        syncedAt = Instant.parse("2026-10-25T13:00:05Z"),
    )

    private suspend fun save(target: String = owner, db: RouteSuggestionsDatabase = database) =
        db.routeSuggestionsCacheDao().replace(RouteSuggestionsCacheEntity(target, codec().encode(snapshot)))

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DB_NAME)
        context.deleteDatabase(MIGRATION_DB)
        TestAppContainer.deleteTestKey(TestAppContainer.ROUTE_SUGGESTIONS_TEST_KEY_ALIAS)
    }

    @Test
    fun laCacheSoloGuardaTextoCifradoYLaParticion() = runBlocking {
        save()

        val db = database.openHelper.readableDatabase
        val columns = db.query("SELECT * FROM route_suggestions_cache").use { it.columnNames.toSet() }
        assertEquals(setOf("owner_key", "payload"), columns)
        val blob = db.query("SELECT payload FROM route_suggestions_cache").use { it.moveToFirst(); it.getBlob(0) }
        val text = String(blob, Charsets.ISO_8859_1)
        listOf("Circuito", "Carlos", "4.6097", "-74.08", "Bogot", "CITY_MATCH", "last_synced_at").forEach {
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
        assertEquals(listOf("city"), restored.missingContext)
        assertTrue(restored.isStale(snapshot.syncedAt.plusSeconds(24 * 3600 + 1)))
    }

    @Test
    fun cadaDeportistaSoloVeSuParticion() = runBlocking {
        save()

        assertNull(repository().observe(otherOwner).first())
    }

    @Test
    fun conLaClaveInvalidadaLaCacheSeIgnoraSinInventarRutas() = runBlocking {
        save()
        TestAppContainer.deleteTestKey(TestAppContainer.ROUTE_SUGGESTIONS_TEST_KEY_ALIAS)

        assertNull(repository().observe(owner).first())
    }

    @Test
    fun cerrarSesionEliminaLaCacheYOtroDeportistaNoReutilizaLaAnterior() = runBlocking {
        save(owner)
        save(otherOwner)
        val user = MutableStateFlow<SessionUser?>(ana)
        val scope = CoroutineScope(Dispatchers.Default)
        RouteSuggestionsCacheJanitor(user, repository()).start(scope)
        val dao = database.routeSuggestionsCacheDao()

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
        val name = RouteSuggestionsDatabase.NAME

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
        const val DB_NAME = "sportapp_route_suggestions_test.db"
        const val MIGRATION_DB = "sportapp_route_suggestions_migration_test.db"
    }
}
