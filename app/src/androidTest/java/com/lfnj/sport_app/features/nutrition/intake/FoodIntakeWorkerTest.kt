package com.lfnj.sport_app.features.nutrition.intake

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
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.security.KeystoreTokenCipher
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.features.health.sync.HealthReportsSyncWorker
import com.lfnj.sport_app.features.health.sync.HealthReportsSyncWorkerFactory
import com.lfnj.sport_app.features.notifications.sync.NotificationsSyncWorkerFactory
import com.lfnj.sport_app.features.nutrition.intake.data.FoodIntakeApi
import com.lfnj.sport_app.features.nutrition.intake.data.FoodIntakeDraftItem
import com.lfnj.sport_app.features.nutrition.intake.data.FoodIntakeRepository
import com.lfnj.sport_app.features.nutrition.intake.data.FoodSubmitResult
import com.lfnj.sport_app.features.nutrition.intake.data.local.FoodIntakeCodec
import com.lfnj.sport_app.features.nutrition.intake.data.local.FoodIntakeDatabase
import com.lfnj.sport_app.features.nutrition.intake.model.FoodOwner
import com.lfnj.sport_app.features.nutrition.intake.model.FoodSyncState
import com.lfnj.sport_app.features.nutrition.intake.model.FoodUnit
import com.lfnj.sport_app.features.nutrition.intake.model.MealType
import com.lfnj.sport_app.features.nutrition.intake.sync.FoodIntakeSyncEngine
import com.lfnj.sport_app.features.nutrition.intake.sync.FoodIntakeSyncWorker
import com.lfnj.sport_app.features.nutrition.intake.sync.FoodIntakeSyncWorkerFactory
import com.lfnj.sport_app.features.nutrition.intake.sync.WorkManagerFoodIntakeSyncScheduler
import com.lfnj.sport_app.features.training.metrics.sync.MetricsSyncWorker
import com.lfnj.sport_app.features.training.metrics.sync.MetricsSyncWorkerFactory
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/** HU043 CA5: trabajo durable de WorkManager (conectividad, reintento, unicidad, cancelación y fábrica propias). */
@RunWith(AndroidJUnit4::class)
class FoodIntakeWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val owner = FoodOwner.keyOf(FoodIntakeLocalStorageTest.USER_ID)
    private lateinit var database: FoodIntakeDatabase
    private lateinit var repository: FoodIntakeRepository
    private lateinit var engine: FoodIntakeSyncEngine
    private lateinit var sessionManager: SessionManager

    @Before
    fun setUp() {
        server.start()
        val retrofit = BffMobileClient.retrofit(server.url("/").toString())
        val json = BffMobileClient.json
        database = Room.inMemoryDatabaseBuilder(context, FoodIntakeDatabase::class.java).build()
        val codec = FoodIntakeCodec(KeystoreTokenCipher(TestAppContainer.FOOD_INTAKES_TEST_KEY_ALIAS), json)
        sessionManager = SessionManager(retrofit.create(SessionApi::class.java), InMemoryTokenStore(), json)
        val api = retrofit.create(FoodIntakeApi::class.java)
        repository = FoodIntakeRepository(database.foodIntakeDao(), database.transactions, codec, api, sessionManager, RecordingScheduler(), json)
        engine = FoodIntakeSyncEngine(database.foodIntakeDao(), database.transactions, codec, api, sessionManager, json)
    }

    @After
    fun tearDown() {
        server.shutdown()
        database.close()
        TestAppContainer.deleteTestKey(TestAppContainer.FOOD_INTAKES_TEST_KEY_ALIAS)
    }

    private fun worker() = TestListenableWorkerBuilder<FoodIntakeSyncWorker>(context)
        .setWorkerFactory(FoodIntakeSyncWorkerFactory { engine })
        .setInputData(workDataOf(FoodIntakeSyncWorker.KEY_OWNER to owner))
        .build()

    @Test
    fun elTrabajoReintentaAnteIndisponibilidadYTerminaAlConfirmarUnaSolaVez() = runBlocking {
        server.enqueue(MockResponse().setBody(FoodIntakeLocalStorageTest.SESSION_BODY))
        sessionManager.login("ana@example.com", "Secreta-123")
        server.enqueue(MockResponse().setBody(FoodIntakeLocalStorageTest.CATALOG))
        repository.refreshCatalog(owner)
        val consumedAt = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.SECONDS)
        val id = (repository.submit(owner, MealType.HYDRATION, consumedAt, listOf(FoodIntakeDraftItem("WATER", "500.0", FoodUnit.ML)))
            as FoodSubmitResult.Queued).intakeId
        repeat(2) { server.takeRequest(5, TimeUnit.SECONDS) }

        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        assertTrue(repository.hasPending(owner))
        val first = server.takeRequest(5, TimeUnit.SECONDS)

        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                FoodIntakeLocalStorageTest.response(id, """[{"food_code":"WATER","quantity":"500.0","unit":"ML"}]""", consumedAt.toString(), "HYDRATION"),
            ),
        )
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        val second = server.takeRequest(5, TimeUnit.SECONDS)
        assertEquals(first?.getHeader("Idempotency-Key"), second?.getHeader("Idempotency-Key"))
        assertEquals(FoodSyncState.SYNCED, repository.observe(owner).first().intakes.single().state)

        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertNull("Confirmado no se vuelve a enviar", server.takeRequest(500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun laSincronizacionEsUnicaExigeRedYSoloSeCancelaLaPropia() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.ERROR)
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(FoodIntakeSyncWorkerFactory { engine })
                .build(),
        )
        val workManager = WorkManager.getInstance(context)
        val scheduler = WorkManagerFoodIntakeSyncScheduler(context)

        scheduler.schedule(owner)

        val name = WorkManagerFoodIntakeSyncScheduler.uniqueName(owner)
        val info = workManager.getWorkInfosForUniqueWork(name).get().single()
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertTrue(WorkManagerFoodIntakeSyncScheduler.TAG in info.tags)
        assertTrue("El nombre del trabajo no lleva datos del consumo", name.startsWith(WorkManagerFoodIntakeSyncScheduler.TAG + "-"))

        WorkManagerTrainingSyncScheduler(context).cancelAll()
        assertEquals(WorkInfo.State.ENQUEUED, workManager.getWorkInfosForUniqueWork(name).get().single().state)

        scheduler.cancelAll()
        assertTrue(workManager.getWorkInfosForUniqueWork(name).get().all { it.state == WorkInfo.State.CANCELLED })
    }

    /** IMPACTO_COMPARTIDO: la fábrica de HU043 no captura los trabajos de HU032, HU034 ni HU042, y las de HU034, HU039 y HU042 no capturan el de HU043. */
    @Test
    fun lasFabricasNoCapturanTrabajosAjenos() {
        var params: WorkerParameters? = null
        TestListenableWorkerBuilder<FoodIntakeSyncWorker>(context).setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker {
                params = workerParameters
                return FoodIntakeSyncWorker(appContext, workerParameters, engine)
            }
        }).build()
        val parameters = checkNotNull(params)
        val factory = FoodIntakeSyncWorkerFactory { error("no debe crear el motor") }

        listOf(TrainingSyncWorker::class.java.name, MetricsSyncWorker::class.java.name, HealthReportsSyncWorker::class.java.name).forEach { name ->
            assertNull(name, factory.createWorker(context, name, parameters))
        }
        listOf(
            MetricsSyncWorkerFactory { error("no debe crear el motor") },
            NotificationsSyncWorkerFactory { error("no debe crear el motor") },
            HealthReportsSyncWorkerFactory { error("no debe crear el motor") },
        ).forEach { other -> assertNull(other.createWorker(context, FoodIntakeSyncWorker::class.java.name, parameters)) }
    }
}
