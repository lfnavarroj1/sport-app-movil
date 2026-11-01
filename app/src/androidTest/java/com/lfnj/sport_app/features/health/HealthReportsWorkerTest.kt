package com.lfnj.sport_app.features.health

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.lfnj.sport_app.SportApp
import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.security.KeystoreTokenCipher
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.core.session.revocation.RevocationWorker
import com.lfnj.sport_app.features.health.data.HealthReportsApi
import com.lfnj.sport_app.features.health.data.HealthReportsRepository
import com.lfnj.sport_app.features.health.data.HealthSubmitResult
import com.lfnj.sport_app.features.health.data.local.HealthReportsCodec
import com.lfnj.sport_app.features.health.data.local.HealthReportsDatabase
import com.lfnj.sport_app.features.health.model.BodyArea
import com.lfnj.sport_app.features.health.model.HealthOwner
import com.lfnj.sport_app.features.health.model.HealthReportDetails
import com.lfnj.sport_app.features.health.model.HealthReportType
import com.lfnj.sport_app.features.health.model.HealthSeverity
import com.lfnj.sport_app.features.health.model.HealthSyncState
import com.lfnj.sport_app.features.health.sync.HealthReportsSyncEngine
import com.lfnj.sport_app.features.health.sync.HealthReportsSyncWorker
import com.lfnj.sport_app.features.health.sync.HealthReportsSyncWorkerFactory
import com.lfnj.sport_app.features.health.sync.WorkManagerHealthReportsSyncScheduler
import com.lfnj.sport_app.features.notifications.sync.NotificationsSyncWorker
import com.lfnj.sport_app.features.training.metrics.sync.MetricsSyncWorker
import com.lfnj.sport_app.features.training.session.sync.TrainingSyncWorker
import com.lfnj.sport_app.features.training.session.sync.WorkManagerTrainingSyncScheduler
import com.lfnj.sport_app.testing.InMemoryTokenStore
import com.lfnj.sport_app.testing.RecordingScheduler
import com.lfnj.sport_app.testing.TestAppContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/** HU042 CA5: trabajo durable de WorkManager (conectividad, reintento, unicidad, cancelación propia y fábrica). */
@RunWith(AndroidJUnit4::class)
class HealthReportsWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val owner = HealthOwner.keyOf(USER_ID)
    private lateinit var database: HealthReportsDatabase
    private lateinit var repository: HealthReportsRepository
    private lateinit var engine: HealthReportsSyncEngine
    private lateinit var sessionManager: SessionManager

    @Before
    fun setUp() {
        server.start()
        val retrofit = BffMobileClient.retrofit(server.url("/").toString())
        val json = BffMobileClient.json
        database = Room.inMemoryDatabaseBuilder(context, HealthReportsDatabase::class.java).build()
        val codec = HealthReportsCodec(KeystoreTokenCipher(TestAppContainer.HEALTH_REPORTS_TEST_KEY_ALIAS), json)
        sessionManager = SessionManager(retrofit.create(SessionApi::class.java), InMemoryTokenStore(), json)
        repository = HealthReportsRepository(database.healthReportsDao(), database.transactions, codec, RecordingScheduler(), "local-dev")
        engine = HealthReportsSyncEngine(database.healthReportsDao(), database.transactions, codec, retrofit.create(HealthReportsApi::class.java), sessionManager, json)
    }

    @After
    fun tearDown() {
        server.shutdown()
        database.close()
        TestAppContainer.deleteTestKey(TestAppContainer.HEALTH_REPORTS_TEST_KEY_ALIAS)
    }

    private fun worker() = TestListenableWorkerBuilder<HealthReportsSyncWorker>(context)
        .setWorkerFactory(HealthReportsSyncWorkerFactory { engine })
        .setInputData(workDataOf(HealthReportsSyncWorker.KEY_OWNER to owner))
        .build()

    @Test
    fun elTrabajoReintentaAnteIndisponibilidadYTerminaAlConfirmar() = runBlocking {
        server.enqueue(MockResponse().setBody(SESSION_BODY))
        sessionManager.login("ana@example.com", "Secreta-123")
        server.takeRequest(5, TimeUnit.SECONDS)
        val occurredAt = Instant.now().minusSeconds(120).truncatedTo(ChronoUnit.SECONDS)
        val id = (repository.submit(owner, HealthReportDetails(HealthReportType.DISCOMFORT, BodyArea.KNEE, HealthSeverity.LOW, occurredAt, null, null))
            as HealthSubmitResult.Queued).reportId

        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        assertTrue(repository.hasPending(owner))

        server.enqueue(MockResponse().setResponseCode(201).setBody(response(id, occurredAt.toString())))
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(HealthSyncState.SYNCED, repository.observe(owner).first().single().state)
    }

    @Test
    fun laSincronizacionEsUnicaExigeRedYSoloSeCancelaLaPropia() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.ERROR)
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(HealthReportsSyncWorkerFactory { engine })
                .build(),
        )
        val workManager = WorkManager.getInstance(context)
        val scheduler = WorkManagerHealthReportsSyncScheduler(context)

        scheduler.schedule(owner)

        val name = WorkManagerHealthReportsSyncScheduler.uniqueName(owner)
        val info = workManager.getWorkInfosForUniqueWork(name).get().single()
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertTrue(WorkManagerHealthReportsSyncScheduler.TAG in info.tags)
        assertTrue("El nombre del trabajo no lleva datos del reporte", name.startsWith(WorkManagerHealthReportsSyncScheduler.TAG + "-"))

        WorkManagerTrainingSyncScheduler(context).cancelAll()
        assertEquals(WorkInfo.State.ENQUEUED, workManager.getWorkInfosForUniqueWork(name).get().single().state)

        scheduler.cancelAll()
        assertTrue(workManager.getWorkInfosForUniqueWork(name).get().all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun laFabricaDeSportAppCreaHu042YSigueCreandoLosTrabajosDeHu032Hu034Hu004YHu039() {
        val factory = (context as SportApp).workManagerConfiguration.workerFactory

        fun created(workerClass: Class<out ListenableWorker>): ListenableWorker =
            TestListenableWorkerBuilder.from(context, workerClass).setWorkerFactory(factory).build()

        assertTrue(created(HealthReportsSyncWorker::class.java) is HealthReportsSyncWorker)
        assertTrue(created(TrainingSyncWorker::class.java) is TrainingSyncWorker)
        assertTrue(created(MetricsSyncWorker::class.java) is MetricsSyncWorker)
        assertTrue(created(RevocationWorker::class.java) is RevocationWorker)
        assertTrue(created(NotificationsSyncWorker::class.java) is NotificationsSyncWorker)
    }

    private fun response(id: String, occurredAt: String) =
        """{"report_id":"$id","athlete_id":"$USER_ID","type":"DISCOMFORT","body_area":"KNEE","severity":"LOW",""" +
            """"occurred_at":"$occurredAt","session_id":null,"expected_recovery_days":null,"consent":{"version":"local-dev"},""" +
            """"status":"OPEN","reported_at":"2026-10-24T18:35:02Z","version":1}"""

    private companion object {
        const val USER_ID = "7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b"
        const val SESSION_BODY =
            """{"token_type":"Bearer","access_token":"access-sintetico","expires_in":900,""" +
                """"refresh_token":"refresh-token-sintetico-000000000001","refresh_expires_in":604800,""" +
                """"user":{"user_id":"7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b","full_name":"Ana Deportista",""" +
                """"email":"ana@example.com","actor_type":"athlete"}}"""
    }
}
