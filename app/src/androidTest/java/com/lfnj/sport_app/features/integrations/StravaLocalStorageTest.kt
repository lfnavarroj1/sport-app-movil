package com.lfnj.sport_app.features.integrations

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
import com.lfnj.sport_app.features.integrations.data.StravaCacheJanitor
import com.lfnj.sport_app.features.integrations.data.StravaRepository
import com.lfnj.sport_app.features.integrations.data.local.StravaCacheEntity
import com.lfnj.sport_app.features.integrations.data.local.StravaCodec
import com.lfnj.sport_app.features.integrations.data.local.StravaDatabase
import com.lfnj.sport_app.features.integrations.model.StravaIntegration
import com.lfnj.sport_app.features.integrations.model.StravaLastSync
import com.lfnj.sport_app.features.integrations.model.StravaLinkStatus
import com.lfnj.sport_app.features.integrations.model.StravaSyncError
import com.lfnj.sport_app.features.integrations.model.StravaSyncStatus
import com.lfnj.sport_app.features.training.session.model.TrainingOwner
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
 * HU031 en el dispositivo: Room real, cifrado AES-GCM con Android Keystore, partición por deportista,
 * reinicio del proceso, limpieza al cerrar sesión, esquema exportado y exclusión del respaldo
 * (ADR-042 §13, ADR-026). Solo se guarda el estado de vinculación; nunca tokens.
 */
@RunWith(AndroidJUnit4::class)
class StravaLocalStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val ana = SessionUser("7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b", "Ana Deportista", "ana@example.com", ActorType.ATHLETE)
    private val owner = TrainingOwner.keyOf(ana.userId)
    private val otherOwner = TrainingOwner.keyOf("0f0e0d0c-0b0a-4908-8706-050403020100")
    private var database = openDatabase()

    @get:Rule
    val migrationHelper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), StravaDatabase::class.java)

    private fun openDatabase() = Room.databaseBuilder(context, StravaDatabase::class.java, DB_NAME).build()

    private fun codec() = StravaCodec(KeystoreTokenCipher(TestAppContainer.STRAVA_TEST_KEY_ALIAS), BffMobileClient.json)

    /** Un repositorio nuevo equivale a un proceso nuevo: no conserva nada en memoria. */
    private fun repository(db: StravaDatabase = database) = StravaRepository(
        dao = db.stravaCacheDao(),
        codec = codec(),
        api = null,
        sessionManager = SessionManager(null, InMemoryTokenStore(), BffMobileClient.json),
        json = BffMobileClient.json,
    )

    private val integration = StravaIntegration(
        status = StravaLinkStatus.ACTIVE,
        linkedAt = Instant.parse("2026-11-10T12:00:00Z"),
        scopes = listOf("read", "activity:read"),
        lastSync = StravaLastSync(StravaSyncStatus.FAILED, Instant.parse("2026-11-15T14:00:00Z"), Instant.parse("2026-11-15T14:05:00Z"), 2, StravaSyncError.CIRCUIT_OPEN),
        syncedAt = Instant.parse("2026-11-15T15:00:05Z"),
    )

    private suspend fun save(target: String = owner) =
        database.stravaCacheDao().replace(StravaCacheEntity(target, codec().encode(integration)))

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DB_NAME)
        context.deleteDatabase(MIGRATION_DB)
        TestAppContainer.deleteTestKey(TestAppContainer.STRAVA_TEST_KEY_ALIAS)
    }

    @Test
    fun laCacheSoloGuardaTextoCifradoYLaParticion() = runBlocking {
        save()

        val db = database.openHelper.readableDatabase
        val columns = db.query("SELECT * FROM strava_integration_cache").use { it.columnNames.toSet() }
        assertEquals(setOf("owner_key", "payload"), columns)
        val blob = db.query("SELECT payload FROM strava_integration_cache").use { it.moveToFirst(); it.getBlob(0) }
        val text = String(blob, Charsets.ISO_8859_1)
        listOf("ACTIVE", "activity:read", "circuit_open", "2026", "synced_at", "token").forEach {
            assertFalse("«$it» en claro", text.contains(it))
        }
        assertEquals(integration, repository().observe(owner).first())
    }

    @Test
    fun elEstadoConfirmadoSobreviveAlReinicioConSuAntiguedad() = runBlocking {
        save()
        database.close()

        database = openDatabase()
        val restored = checkNotNull(repository().observe(owner).first())

        assertEquals(integration.syncedAt, restored.syncedAt)
        assertEquals(integration.lastSync, restored.lastSync)
    }

    @Test
    fun cadaDeportistaSoloVeSuEstado() = runBlocking {
        save()

        assertNull(repository().observe(otherOwner).first())
    }

    @Test
    fun conLaClaveInvalidadaLaCacheSeIgnoraSinInventarDatos() = runBlocking {
        save()
        TestAppContainer.deleteTestKey(TestAppContainer.STRAVA_TEST_KEY_ALIAS)

        assertNull(repository().observe(owner).first())
    }

    @Test
    fun cerrarSesionEliminaElEstadoYOtroDeportistaNoReutilizaElAnterior() = runBlocking {
        save(owner)
        save(otherOwner)
        val user = MutableStateFlow<SessionUser?>(ana)
        val scope = CoroutineScope(Dispatchers.Default)
        StravaCacheJanitor(user, repository()).start(scope)
        val dao = database.stravaCacheDao()

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
        val name = StravaDatabase.NAME

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
        const val DB_NAME = "sportapp_strava_test.db"
        const val MIGRATION_DB = "sportapp_strava_migration_test.db"
    }
}
