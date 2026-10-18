package com.lfnj.sport_app.features.training.metrics

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lfnj.sport_app.R
import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.security.KeystoreTokenCipher
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.features.training.metrics.data.SubmitResult
import com.lfnj.sport_app.features.training.metrics.data.TrainingMetricsApi
import com.lfnj.sport_app.features.training.metrics.data.TrainingMetricsRepository
import com.lfnj.sport_app.features.training.metrics.data.local.TrainingMetricsCodec
import com.lfnj.sport_app.features.training.metrics.data.local.TrainingMetricsDatabase
import com.lfnj.sport_app.features.training.metrics.model.MetricInputValue
import com.lfnj.sport_app.features.training.metrics.model.MetricSyncFailure
import com.lfnj.sport_app.features.training.metrics.model.MetricSyncState
import com.lfnj.sport_app.features.training.session.model.TrainingOwner
import com.lfnj.sport_app.testing.InMemoryTokenStore
import com.lfnj.sport_app.testing.RecordingScheduler
import com.lfnj.sport_app.testing.TestAppContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * HU034 en el dispositivo: Room real, cifrado AES-GCM con Android Keystore, partición por deportista,
 * reinicio del proceso, esquema exportado y exclusión del respaldo (ADR-030, política offline).
 */
@RunWith(AndroidJUnit4::class)
class TrainingMetricsLocalStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val owner = TrainingOwner.keyOf("7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b")
    private val server = MockWebServer()
    private val scheduler = RecordingScheduler()
    private var database = openDatabase()
    private val sessionManager by lazy {
        SessionManager(BffMobileClient.retrofit(server.url("/").toString()).create(SessionApi::class.java), InMemoryTokenStore(), BffMobileClient.json)
    }

    @get:Rule
    val migrationHelper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TrainingMetricsDatabase::class.java)

    private fun openDatabase() = Room.databaseBuilder(context, TrainingMetricsDatabase::class.java, DB_NAME).build()

    /** Un repositorio nuevo sobre la misma base equivale a un proceso nuevo. */
    private fun repository(db: TrainingMetricsDatabase = database) = TrainingMetricsRepository(
        dao = db.trainingMetricsDao(),
        transactions = db.transactions,
        codec = TrainingMetricsCodec(KeystoreTokenCipher(TestAppContainer.METRICS_TEST_KEY_ALIAS), BffMobileClient.json),
        api = BffMobileClient.retrofit(server.url("/").toString()).create(TrainingMetricsApi::class.java),
        sessionManager = sessionManager,
        scheduler = scheduler,
        json = BffMobileClient.json,
    )

    @Before
    fun setUp() {
        server.start()
        server.enqueue(MockResponse().setBody(SESSION_BODY))
        runBlocking { sessionManager.login("ana@example.com", "Secreta-123") }
        server.takeRequest(5, TimeUnit.SECONDS)
    }

    @After
    fun tearDown() {
        server.shutdown()
        database.close()
        context.deleteDatabase(DB_NAME)
        context.deleteDatabase(MIGRATION_DB)
        TestAppContainer.deleteTestKey(TestAppContainer.METRICS_TEST_KEY_ALIAS)
    }

    private suspend fun cacheCatalogAndCapture(repository: TrainingMetricsRepository = repository()): String {
        server.enqueue(MockResponse().setBody(CATALOG).setHeader("ETag", "\"training-metrics-2026-10-12.v1\""))
        repository.refreshCatalog(owner)
        server.takeRequest(5, TimeUnit.SECONDS)
        val queued = repository.submit(owner, "FTP_20_MIN_95_V1@1", listOf(MetricInputValue("mean_power_watts", 250, "W")), Instant.now().minusSeconds(60))
        return (queued as SubmitResult.Queued).calculationId
    }

    private fun rawBlobs(): List<String> {
        val blobs = mutableListOf<String>()
        val db = database.openHelper.readableDatabase
        for (table in listOf("metric_catalog_cache", "metric_result_cache", "metric_command")) {
            db.query("SELECT payload FROM $table").use { cursor ->
                while (cursor.moveToNext()) blobs += String(cursor.getBlob(0), Charsets.ISO_8859_1)
            }
        }
        return blobs
    }

    @Test
    fun laBaseSoloGuardaTextoCifradoYMetadatosTecnicos() = runBlocking {
        cacheCatalogAndCapture()

        val blobs = rawBlobs()
        assertEquals(2, blobs.size) // catálogo + cálculo pendiente
        blobs.forEach { blob ->
            listOf("mean_power_watts", "FTP_20_MIN_95_V1", "training-metrics-2026", "\"value\":250", "measured_at").forEach {
                assertFalse("«$it» en claro", blob.contains(it))
            }
        }
        val columns = database.openHelper.readableDatabase.query("SELECT * FROM metric_command").use { it.columnNames.toSet() }
        assertEquals(
            setOf(
                "calculation_id", "owner_key", "idempotency_key", "sync_state", "failure_reason", "failure_field",
                "failure_issue", "created_at_ms", "attempts", "last_attempt_at_ms", "payload",
            ),
            columns,
        )
    }

    @Test
    fun ca8ElCalculoPendienteSobreviveAlReinicioDelProceso() = runBlocking {
        val id = cacheCatalogAndCapture()
        database.close()

        database = openDatabase()
        val restarted = repository()
        val local = restarted.observe(owner).first()

        assertEquals(id, local.pending.single().calculationId)
        assertEquals(MetricSyncState.PENDING_SYNC, local.pending.single().state)
        assertEquals(250L, local.pending.single().request?.inputs?.single()?.value)
        assertEquals(2, local.catalog?.methods?.size)
        assertTrue(restarted.hasPending(owner))
    }

    @Test
    fun cadaDeportistaSoloVeSuParticion() = runBlocking {
        cacheCatalogAndCapture()

        val other = repository().observe(TrainingOwner.keyOf("0f0e0d0c-0b0a-4908-8706-050403020100")).first()
        assertNull(other.catalog)
        assertTrue(other.pending.isEmpty())
    }

    @Test
    fun conLaClaveInvalidadaNoSeInventanDatos() = runBlocking {
        cacheCatalogAndCapture()
        TestAppContainer.deleteTestKey(TestAppContainer.METRICS_TEST_KEY_ALIAS)

        val local = repository().observe(owner).first()

        assertNull("Un catálogo ilegible no habilita métodos", local.catalog)
        assertEquals(MetricSyncFailure.UNREADABLE, local.pending.single().failure)
        assertNull(local.pending.single().request)
    }

    @Test
    fun elEsquemaCreadoCoincideConElExportadoDeLaVersion1() {
        migrationHelper.createDatabase(MIGRATION_DB, 1).close()

        migrationHelper.runMigrationsAndValidate(MIGRATION_DB, 1, true).close()
    }

    @Test
    fun laBaseDeIndicadoresSeExcluyeDelRespaldoYLaTransferencia() {
        val backup = excludedPaths(R.xml.backup_rules)
        val extraction = excludedPaths(R.xml.data_extraction_rules)

        listOf(TrainingMetricsDatabase.NAME, "${TrainingMetricsDatabase.NAME}-wal", "${TrainingMetricsDatabase.NAME}-shm").forEach { file ->
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

    companion object {
        const val DB_NAME = "sportapp_training_metrics_test.db"
        const val MIGRATION_DB = "sportapp_training_metrics_migration_test.db"
        const val SESSION_BODY =
            """{"token_type":"Bearer","access_token":"access-sintetico","expires_in":900,""" +
                """"refresh_token":"refresh-token-sintetico-000000000001","refresh_expires_in":604800,""" +
                """"user":{"user_id":"7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b","full_name":"Ana Deportista",""" +
                """"email":"ana@example.com","actor_type":"athlete"}}"""

        /** Respuesta real del catálogo de la composición local (2026-10-18). */
        const val CATALOG =
            """{"catalog_version":"training-metrics-2026-10-12.v1","methods":[""" +
                """{"metric_code":"FTP_ESTIMATED","label_key":"training_metric.ftp_estimated","result_unit":"W","result_scale":0,""" +
                """"method_code":"FTP_20_MIN_95_V1","method_version":1,"method_label_key":"training_metric.method.ftp_20_min_95_v1",""" +
                """"disclaimer_key":"estimated_not_diagnostic","inputs":[{"code":"mean_power_watts",""" +
                """"label_key":"training_metric.input.mean_power_watts","value_type":"INTEGER","unit":"W","minimum":1,""" +
                """"maximum":2500,"scale":0,"required":true}]},""" +
                """{"metric_code":"VO2MAX_ESTIMATED","label_key":"training_metric.vo2max_estimated","result_unit":"mL/kg/min",""" +
                """"result_scale":1,"method_code":"COOPER_12_MIN_V1","method_version":1,""" +
                """"method_label_key":"training_metric.method.cooper_12_min_v1","disclaimer_key":"estimated_not_diagnostic",""" +
                """"inputs":[{"code":"distance_meters","label_key":"training_metric.input.distance_meters","value_type":"INTEGER",""" +
                """"unit":"m","minimum":600,"maximum":6000,"scale":0,"required":true}]}]}"""
    }
}
