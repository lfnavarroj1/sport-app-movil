package com.lfnj.sport_app.features.training.metrics

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
import com.lfnj.sport_app.features.training.metrics.sync.MetricsSyncEngine
import com.lfnj.sport_app.features.training.metrics.sync.MetricsSyncWorker
import com.lfnj.sport_app.features.training.metrics.sync.MetricsSyncWorkerFactory
import com.lfnj.sport_app.features.training.metrics.sync.WorkManagerMetricsSyncScheduler
import com.lfnj.sport_app.features.training.session.model.TrainingOwner
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
import java.util.concurrent.TimeUnit

/** HU034 CA8: trabajo durable de WorkManager (conectividad, reintento, unicidad y cancelación propia). */
@RunWith(AndroidJUnit4::class)
class TrainingMetricsWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val owner = TrainingOwner.keyOf("7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b")
    private lateinit var database: TrainingMetricsDatabase
    private lateinit var repository: TrainingMetricsRepository
    private lateinit var engine: MetricsSyncEngine
    private lateinit var sessionManager: SessionManager

    @Before
    fun setUp() {
        server.start()
        val retrofit = BffMobileClient.retrofit(server.url("/").toString())
        val json = BffMobileClient.json
        database = Room.inMemoryDatabaseBuilder(context, TrainingMetricsDatabase::class.java).build()
        val codec = TrainingMetricsCodec(KeystoreTokenCipher(TestAppContainer.METRICS_TEST_KEY_ALIAS), json)
        sessionManager = SessionManager(retrofit.create(SessionApi::class.java), InMemoryTokenStore(), json)
        val api = retrofit.create(TrainingMetricsApi::class.java)
        repository = TrainingMetricsRepository(database.trainingMetricsDao(), database.transactions, codec, api, sessionManager, RecordingScheduler(), json)
        engine = MetricsSyncEngine(database.trainingMetricsDao(), database.transactions, codec, api, sessionManager, json)
    }

    @After
    fun tearDown() {
        server.shutdown()
        database.close()
        TestAppContainer.deleteTestKey(TestAppContainer.METRICS_TEST_KEY_ALIAS)
    }

    private fun worker() = TestListenableWorkerBuilder<MetricsSyncWorker>(context)
        .setWorkerFactory(MetricsSyncWorkerFactory { engine })
        .setInputData(workDataOf(MetricsSyncWorker.KEY_OWNER to owner))
        .build()

    @Test
    fun elTrabajoReintentaAnteIndisponibilidadYTerminaAlConfirmar() = runBlocking {
        server.enqueue(MockResponse().setBody(TrainingMetricsLocalStorageTest.SESSION_BODY))
        sessionManager.login("ana@example.com", "Secreta-123")
        server.enqueue(MockResponse().setBody(TrainingMetricsLocalStorageTest.CATALOG))
        repository.refreshCatalog(owner)
        val id = (repository.submit(owner, "FTP_20_MIN_95_V1@1", listOf(MetricInputValue("mean_power_watts", 250, "W")), Instant.now().minusSeconds(60))
            as SubmitResult.Queued).calculationId
        repeat(2) { server.takeRequest(5, TimeUnit.SECONDS) }

        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        assertTrue(repository.hasPending(owner))

        server.enqueue(MockResponse().setResponseCode(201).setBody(result(id)))
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        val local = repository.observe(owner).first()
        assertTrue(local.pending.isEmpty())
        assertEquals("238", local.results.items.single().value.toPlainString())
    }

    @Test
    fun laSincronizacionEsUnicaExigeRedYSoloSeCancelaLaPropia() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.ERROR)
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(MetricsSyncWorkerFactory { engine })
                .build(),
        )
        val workManager = WorkManager.getInstance(context)
        val scheduler = WorkManagerMetricsSyncScheduler(context)

        scheduler.schedule(owner)

        val name = WorkManagerMetricsSyncScheduler.uniqueName(owner)
        val info = workManager.getWorkInfosForUniqueWork(name).get().single()
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertTrue(WorkManagerMetricsSyncScheduler.TAG in info.tags)
        assertTrue("El nombre del trabajo no lleva datos del cálculo", name.startsWith(WorkManagerMetricsSyncScheduler.TAG + "-"))

        WorkManagerTrainingSyncScheduler(context).cancelAll()
        assertEquals(WorkInfo.State.ENQUEUED, workManager.getWorkInfosForUniqueWork(name).get().single().state)

        scheduler.cancelAll()
        assertTrue(workManager.getWorkInfosForUniqueWork(name).get().all { it.state == WorkInfo.State.CANCELLED })
    }

    private fun result(id: String) =
        """{"calculation_id":"$id","athlete_id":"7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b",""" +
            """"metric_code":"FTP_ESTIMATED","method_code":"FTP_20_MIN_95_V1","method_version":1,"measured_at":"2026-10-12T15:30:00Z",""" +
            """"inputs":[{"code":"mean_power_watts","value":250,"unit":"W"}],"result":{"value":238,"unit":"W","scale":0},""" +
            """"status":"CONFIRMED","calculated_at":"2026-10-12T15:30:02Z","disclaimer_key":"estimated_not_diagnostic","version":1}"""
}
