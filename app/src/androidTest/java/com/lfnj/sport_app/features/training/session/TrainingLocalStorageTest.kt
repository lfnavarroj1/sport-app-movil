package com.lfnj.sport_app.features.training.session

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lfnj.sport_app.R
import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.security.KeystoreTokenCipher
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.features.training.session.data.StartResult
import com.lfnj.sport_app.features.training.session.data.TrainingSessionRepository
import com.lfnj.sport_app.features.training.session.data.local.TrainingDatabase
import com.lfnj.sport_app.features.training.session.data.local.TrainingRecordCodec
import com.lfnj.sport_app.features.training.session.model.SyncFailure
import com.lfnj.sport_app.features.training.session.model.SyncState
import com.lfnj.sport_app.features.training.session.model.TrainingOwner
import com.lfnj.sport_app.features.training.session.model.TrainingSport
import com.lfnj.sport_app.testing.InMemoryTokenStore
import com.lfnj.sport_app.testing.RecordingScheduler
import com.lfnj.sport_app.testing.TestAppContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/**
 * HU032 en el dispositivo: Room real, cifrado AES-GCM con Android Keystore, partición por
 * deportista, reinicio del proceso, esquema exportado y exclusión del respaldo (ADR-029 offline §5).
 */
@RunWith(AndroidJUnit4::class)
class TrainingLocalStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val owner = TrainingOwner.keyOf("7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b")
    private val otherOwner = TrainingOwner.keyOf("0f0e0d0c-0b0a-4908-8706-050403020100")
    private val scheduler = RecordingScheduler()
    private var database = openDatabase()

    @get:Rule
    val migrationHelper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TrainingDatabase::class.java)

    private fun openDatabase() = Room.databaseBuilder(context, TrainingDatabase::class.java, DB_NAME).build()

    /** Un repositorio nuevo equivale a un proceso nuevo: no conserva nada en memoria. */
    private fun repository(db: TrainingDatabase = database) = TrainingSessionRepository(
        dao = db.trainingSessionDao(),
        transactions = db.transactions,
        codec = TrainingRecordCodec(KeystoreTokenCipher(TestAppContainer.TEST_KEY_ALIAS), BffMobileClient.json),
        scheduler = scheduler,
        api = null,
        sessionManager = SessionManager(null, InMemoryTokenStore(), BffMobileClient.json),
        json = BffMobileClient.json,
    )

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DB_NAME)
        context.deleteDatabase(MIGRATION_DB)
        TestAppContainer.deleteTestKey()
    }

    private fun rawBlobs(): List<ByteArray> {
        val blobs = mutableListOf<ByteArray>()
        val db = database.openHelper.readableDatabase
        for (table in listOf("training_session", "training_command")) {
            db.query("SELECT payload FROM $table").use { cursor ->
                while (cursor.moveToNext()) blobs += cursor.getBlob(0)
            }
        }
        return blobs
    }

    @Test
    fun laBaseSoloGuardaTextoCifradoYMetadatosTecnicos() = runBlocking {
        val repository = repository()
        val id = (repository.start(owner, TrainingSport.SWIMMING) as StartResult.Started).sessionId
        repository.finish(owner, id)

        val blobs = rawBlobs()
        assertEquals(3, blobs.size) // sesión + inicio + fin
        blobs.forEach { blob ->
            val text = String(blob, Charsets.ISO_8859_1)
            listOf("swimming", "started_at", "ended_at", "2026", id).forEach { assertFalse("«$it» en claro", text.contains(it)) }
        }
        val columns = database.openHelper.readableDatabase.query("SELECT * FROM training_session").use { it.columnNames.toSet() }
        assertEquals(setOf("session_id", "owner_key", "sync_state", "failure_reason", "created_at_ms", "payload"), columns)
        assertEquals(TrainingSport.SWIMMING, repository.observe(owner).first().single().details?.sport)
    }

    @Test
    fun laSesionYSuColaSobrevivenAlReinicioDelProceso() = runBlocking {
        val id = (repository().start(owner, TrainingSport.RUNNING) as StartResult.Started).sessionId
        database.close()

        database = openDatabase()
        val restarted = repository()
        val session = restarted.observe(owner).first().single()

        assertEquals(id, session.sessionId)
        assertTrue(session.open)
        assertEquals(SyncState.LOCAL, session.syncState)
        assertTrue(restarted.hasPendingCommands(owner))
        assertNull("Tras reiniciar, el cronómetro se reconstruye desde started_at", restarted.monotonicStartMillis(id))
    }

    @Test
    fun cadaDeportistaSoloVeSuParticion() = runBlocking {
        val repository = repository()
        repository.start(owner, TrainingSport.RUNNING)

        assertTrue(repository.observe(otherOwner).first().isEmpty())
        assertFalse(repository.hasPendingCommands(otherOwner))
    }

    @Test
    fun conLaClaveInvalidadaElRegistroQuedaIlegibleYNoSeInventanDatos() = runBlocking {
        repository().start(owner, TrainingSport.RUNNING)
        TestAppContainer.deleteTestKey()

        val session = repository().observe(owner).first().single()

        assertEquals(SyncState.SYNC_FAILED, session.syncState)
        assertEquals(SyncFailure.UNREADABLE, session.failure)
        assertNull(session.details)
    }

    @Test
    fun elEsquemaCreadoCoincideConElExportadoDeLaVersion1() {
        migrationHelper.createDatabase(MIGRATION_DB, 1).close()

        migrationHelper.runMigrationsAndValidate(MIGRATION_DB, 1, true).close()
    }

    @Test
    fun laBaseDeEntrenamientoSeExcluyeDelRespaldoYLaTransferencia() {
        val backup = excludedPaths(R.xml.backup_rules)
        val extraction = excludedPaths(R.xml.data_extraction_rules)

        listOf(TrainingDatabase.NAME, "${TrainingDatabase.NAME}-wal", "${TrainingDatabase.NAME}-shm").forEach { file ->
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
        const val DB_NAME = "sportapp_training_test.db"
        const val MIGRATION_DB = "sportapp_training_migration_test.db"
    }
}
