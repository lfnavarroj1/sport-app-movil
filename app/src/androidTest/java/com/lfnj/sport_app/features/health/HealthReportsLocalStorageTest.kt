package com.lfnj.sport_app.features.health

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lfnj.sport_app.R
import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.security.KeystoreTokenCipher
import com.lfnj.sport_app.features.health.data.HealthReportsRepository
import com.lfnj.sport_app.features.health.data.HealthSubmitResult
import com.lfnj.sport_app.features.health.data.local.HealthReportsCodec
import com.lfnj.sport_app.features.health.data.local.HealthReportsDatabase
import com.lfnj.sport_app.features.health.model.BodyArea
import com.lfnj.sport_app.features.health.model.HealthOwner
import com.lfnj.sport_app.features.health.model.HealthReportDetails
import com.lfnj.sport_app.features.health.model.HealthReportType
import com.lfnj.sport_app.features.health.model.HealthSeverity
import com.lfnj.sport_app.features.health.model.HealthSyncFailure
import com.lfnj.sport_app.features.health.model.HealthSyncState
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
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * HU042 en el dispositivo: Room real, cifrado AES-GCM con Android Keystore, partición por deportista,
 * reinicio del proceso, limpieza al terminar la sesión, esquema exportado y exclusión del respaldo.
 */
@RunWith(AndroidJUnit4::class)
class HealthReportsLocalStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val owner = HealthOwner.keyOf("7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b")
    private val other = HealthOwner.keyOf("0f0e0d0c-0b0a-4908-8706-050403020100")
    private val scheduler = RecordingScheduler()
    private var database = openDatabase()

    @get:Rule
    val migrationHelper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), HealthReportsDatabase::class.java)

    private fun openDatabase() = Room.databaseBuilder(context, HealthReportsDatabase::class.java, DB_NAME).build()

    /** Un repositorio nuevo sobre la misma base equivale a un proceso nuevo. */
    private fun repository(db: HealthReportsDatabase = database) = HealthReportsRepository(
        dao = db.healthReportsDao(),
        transactions = db.transactions,
        codec = HealthReportsCodec(KeystoreTokenCipher(TestAppContainer.HEALTH_REPORTS_TEST_KEY_ALIAS), BffMobileClient.json),
        scheduler = scheduler,
        consentVersion = "local-dev",
    )

    private val details = HealthReportDetails(
        HealthReportType.INJURY, BodyArea.ANKLE_FOOT, HealthSeverity.HIGH,
        Instant.now().minusSeconds(600).truncatedTo(ChronoUnit.SECONDS), "4c3b2a19-0f1e-4d2c-8b3a-293847566574", 21,
    )

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DB_NAME)
        context.deleteDatabase(MIGRATION_DB)
        TestAppContainer.deleteTestKey(TestAppContainer.HEALTH_REPORTS_TEST_KEY_ALIAS)
    }

    private suspend fun capture(repository: HealthReportsRepository = repository(), owner: String = this.owner): String =
        (repository.submit(owner, details) as HealthSubmitResult.Queued).reportId

    @Test
    fun laBaseSoloGuardaTextoCifradoYMetadatosTecnicos() = runBlocking {
        capture()

        val db = database.openHelper.readableDatabase
        val blob = db.query("SELECT payload FROM health_report").use { c -> c.moveToFirst(); String(c.getBlob(0), Charsets.ISO_8859_1) }
        listOf("ANKLE_FOOT", "INJURY", "HIGH", "4c3b2a19", "local-dev", "occurred_at", "expected_recovery_days").forEach {
            assertFalse("«$it» en claro", blob.contains(it))
        }
        val columns = db.query("SELECT * FROM health_report").use { it.columnNames.toSet() }
        assertEquals(
            setOf(
                "report_id", "owner_key", "idempotency_key", "sync_state", "failure_reason", "failure_field",
                "failure_issue", "created_at_ms", "attempts", "last_attempt_at_ms", "payload",
            ),
            columns,
        )
    }

    @Test
    fun ca5ElReportePendienteSobreviveAlReinicioDelProceso() = runBlocking {
        val id = capture()
        database.close()

        database = openDatabase()
        val restarted = repository()
        val local = restarted.observe(owner).first().single()

        assertEquals(id, local.reportId)
        assertEquals(HealthSyncState.PENDING_SYNC, local.state)
        assertEquals(details, local.details)
        assertNull(local.reportedAt)
        assertTrue(restarted.hasPending(owner))
    }

    @Test
    fun cadaDeportistaSoloVeSuParticion() = runBlocking {
        capture()

        assertTrue(repository().observe(other).first().isEmpty())
    }

    @Test
    fun alTerminarLaSesionSeBorranLosResueltosYSeConservanLosPendientes() = runBlocking {
        val pending = capture()
        val failed = capture()
        val dao = database.healthReportsDao()
        dao.replaceReport(checkNotNull(dao.report(owner, failed)).failed(HealthSyncFailure.REJECTED.name))
        val othersPending = capture(owner = other)

        repository().clearSettled(owner)

        assertEquals(listOf(pending), repository().observe(owner).first().map { it.reportId })
        assertEquals(listOf(othersPending), repository().observe(other).first().map { it.reportId })
    }

    @Test
    fun conLaClaveInvalidadaNoSeInventanDatos() = runBlocking {
        capture()
        TestAppContainer.deleteTestKey(TestAppContainer.HEALTH_REPORTS_TEST_KEY_ALIAS)

        val local = repository().observe(owner).first().single()

        assertEquals(HealthSyncState.SYNC_FAILED, local.state)
        assertEquals(HealthSyncFailure.UNREADABLE, local.failure)
        assertNull(local.details)
    }

    @Test
    fun elEsquemaCreadoCoincideConElExportadoDeLaVersion1() {
        migrationHelper.createDatabase(MIGRATION_DB, 1).close()

        migrationHelper.runMigrationsAndValidate(MIGRATION_DB, 1, true).close()
    }

    @Test
    fun laBaseDeReportesSeExcluyeDelRespaldoYLaTransferencia() {
        val backup = excludedPaths(R.xml.backup_rules)
        val extraction = excludedPaths(R.xml.data_extraction_rules)

        listOf(HealthReportsDatabase.NAME, "${HealthReportsDatabase.NAME}-wal", "${HealthReportsDatabase.NAME}-shm", "${HealthReportsDatabase.NAME}-journal").forEach { file ->
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
        const val DB_NAME = "sportapp_health_reports_test.db"
        const val MIGRATION_DB = "sportapp_health_reports_migration_test.db"
    }
}
