package com.lfnj.sport_app.features.training.dashboard

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
import com.lfnj.sport_app.features.training.dashboard.data.TrainingDashboardCacheJanitor
import com.lfnj.sport_app.features.training.dashboard.data.TrainingDashboardRepository
import com.lfnj.sport_app.features.training.dashboard.data.local.TrainingDashboardCacheEntity
import com.lfnj.sport_app.features.training.dashboard.data.local.TrainingDashboardCodec
import com.lfnj.sport_app.features.training.dashboard.data.local.TrainingDashboardDatabase
import com.lfnj.sport_app.features.training.dashboard.model.DashboardRange
import com.lfnj.sport_app.features.training.dashboard.model.DashboardSnapshot
import com.lfnj.sport_app.features.training.dashboard.model.DashboardSourceState
import com.lfnj.sport_app.features.training.dashboard.model.DashboardSourceStatus
import com.lfnj.sport_app.features.training.dashboard.model.DashboardTotals
import com.lfnj.sport_app.features.training.dashboard.model.LatestMetric
import com.lfnj.sport_app.features.training.dashboard.model.SourceTotal
import com.lfnj.sport_app.features.training.dashboard.model.SourceTotals
import com.lfnj.sport_app.features.training.dashboard.model.SportTotal
import com.lfnj.sport_app.features.training.dashboard.model.WeekTotal
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
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * HU033 en el dispositivo: Room real, cifrado AES-GCM con Android Keystore, partición por
 * deportista y opción, reinicio del proceso, limpieza al cerrar sesión, esquema exportado y
 * exclusión del respaldo (ADR-043 §5, ADR-026).
 */
@RunWith(AndroidJUnit4::class)
class TrainingDashboardLocalStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val ana = SessionUser("7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b", "Ana Deportista", "ana@example.com", ActorType.ATHLETE)
    private val owner = TrainingOwner.keyOf(ana.userId)
    private val otherOwner = TrainingOwner.keyOf("0f0e0d0c-0b0a-4908-8706-050403020100")
    private var database = openDatabase()

    @get:Rule
    val migrationHelper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TrainingDashboardDatabase::class.java)

    private fun openDatabase() = Room.databaseBuilder(context, TrainingDashboardDatabase::class.java, DB_NAME).build()

    private fun codec() = TrainingDashboardCodec(KeystoreTokenCipher(TestAppContainer.DASHBOARD_TEST_KEY_ALIAS), BffMobileClient.json)

    /** Un repositorio nuevo equivale a un proceso nuevo: no conserva nada en memoria. */
    private fun repository(db: TrainingDashboardDatabase = database) = TrainingDashboardRepository(
        dao = db.trainingDashboardCacheDao(),
        codec = codec(),
        api = null,
        sessionManager = SessionManager(null, InMemoryTokenStore(), BffMobileClient.json),
        json = BffMobileClient.json,
    )

    private val snapshot = DashboardSnapshot(
        range = DashboardRange.LAST_30,
        from = Instant.parse("2026-09-26T05:00:00Z"),
        to = Instant.parse("2026-10-26T05:00:00Z"),
        hasData = true,
        totals = DashboardTotals(2, 95, 48, null),
        bySport = listOf(SportTotal("running", 1, 45, null), SportTotal("cycling", 1, 50, 20_500)),
        byWeek = listOf(WeekTotal(LocalDate.of(2026, 10, 12), 1, 45), WeekTotal(LocalDate.of(2026, 10, 19), 1, 50)),
        bySource = SourceTotals(SourceTotal(2, 95), SourceTotal(0, 0)),
        latestMetrics = listOf(
            LatestMetric("FTP_ESTIMATED", BigDecimal("238"), "W", "FTP_20_MIN_95_V1", 1, Instant.parse("2026-10-20T12:30:00Z"), "estimated_not_diagnostic"),
            LatestMetric("VO2MAX_ESTIMATED", BigDecimal("47.25"), "ml/kg/min", "COOPER_12_MIN_V1", 1, Instant.parse("2026-10-21T12:30:00Z"), "estimated_not_diagnostic"),
        ),
        sources = listOf(DashboardSourceState("training_sessions", DashboardSourceStatus.OK), DashboardSourceState("training_metrics", DashboardSourceStatus.UNAVAILABLE)),
        syncedAt = Instant.parse("2026-10-25T14:00:05Z"),
    )

    private suspend fun save(target: String = owner, range: DashboardRange = DashboardRange.LAST_30, db: TrainingDashboardDatabase = database) =
        db.trainingDashboardCacheDao().replace(TrainingDashboardCacheEntity(target, range.cacheKey, codec().encode(snapshot.copy(range = range))))

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DB_NAME)
        context.deleteDatabase(MIGRATION_DB)
        TestAppContainer.deleteTestKey(TestAppContainer.DASHBOARD_TEST_KEY_ALIAS)
    }

    @Test
    fun laCacheSoloGuardaTextoCifradoLaParticionYLaOpcion() = runBlocking {
        save()

        val db = database.openHelper.readableDatabase
        val columns = db.query("SELECT * FROM training_dashboard_cache").use { it.columnNames.toSet() }
        assertEquals(setOf("owner_key", "period_key", "payload"), columns)
        val blob = db.query("SELECT payload FROM training_dashboard_cache").use { it.moveToFirst(); it.getBlob(0) }
        val text = String(blob, Charsets.ISO_8859_1)
        listOf("FTP_ESTIMATED", "running", "238", "47.25", "2026", "last_synced_at", "training_sessions").forEach {
            assertFalse("«$it» en claro", text.contains(it))
        }
        assertEquals(snapshot, repository().observe(owner, DashboardRange.LAST_30).first())
    }

    @Test
    fun elTableroConfirmadoSobreviveAlReinicioConSuAntiguedad() = runBlocking {
        save()
        database.close()

        database = openDatabase()
        val restored = checkNotNull(repository().observe(owner, DashboardRange.LAST_30).first())

        assertEquals(snapshot.syncedAt, restored.syncedAt)
        assertEquals(snapshot.latestMetrics, restored.latestMetrics)
        assertEquals(BigDecimal("47.25"), restored.latestMetrics[1].value)
        assertEquals(listOf("training_metrics"), restored.incompleteSources)
    }

    @Test
    fun cadaDeportistaYCadaOpcionSoloVenSuFila() = runBlocking {
        save()
        save(range = DashboardRange.LAST_7)

        assertNull(repository().observe(otherOwner, DashboardRange.LAST_30).first())
        assertNull(repository().observe(owner, DashboardRange.LAST_90).first())
        assertEquals(DashboardRange.LAST_7, repository().observe(owner, DashboardRange.LAST_7).first()?.range)
    }

    @Test
    fun conLaClaveInvalidadaLaCacheSeIgnoraSinInventarDatos() = runBlocking {
        save()
        TestAppContainer.deleteTestKey(TestAppContainer.DASHBOARD_TEST_KEY_ALIAS)

        assertNull(repository().observe(owner, DashboardRange.LAST_30).first())
    }

    @Test
    fun cerrarSesionEliminaLaCacheYOtroDeportistaNoReutilizaLaAnterior() = runBlocking {
        save(owner)
        save(otherOwner)
        val user = MutableStateFlow<SessionUser?>(ana)
        val scope = CoroutineScope(Dispatchers.Default)
        TrainingDashboardCacheJanitor(user, repository()).start(scope)
        val dao = database.trainingDashboardCacheDao()

        // Al estar autenticada Ana, se elimina la partición que otro deportista dejó en el dispositivo.
        withTimeout(5_000) { while (dao.observe(otherOwner, "LAST_30").first() != null) delay(50) }
        assertTrue(dao.observe(owner, "LAST_30").first() != null)

        user.value = null
        withTimeout(5_000) { while (dao.observe(owner, "LAST_30").first() != null) delay(50) }
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
        val name = TrainingDashboardDatabase.NAME

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
        const val DB_NAME = "sportapp_training_dashboard_test.db"
        const val MIGRATION_DB = "sportapp_training_dashboard_migration_test.db"
    }
}
