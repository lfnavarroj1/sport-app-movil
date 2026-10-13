package com.lfnj.sport_app.features.training.session

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
import com.lfnj.sport_app.features.training.session.data.StartResult
import com.lfnj.sport_app.features.training.session.data.TrainingSessionApi
import com.lfnj.sport_app.features.training.session.data.TrainingSessionRepository
import com.lfnj.sport_app.features.training.session.data.local.TrainingDatabase
import com.lfnj.sport_app.features.training.session.data.local.TrainingRecordCodec
import com.lfnj.sport_app.features.training.session.model.SyncState
import com.lfnj.sport_app.features.training.session.model.TrainingOwner
import com.lfnj.sport_app.features.training.session.model.TrainingSport
import com.lfnj.sport_app.features.training.session.sync.TrainingSyncEngine
import com.lfnj.sport_app.features.training.session.sync.TrainingSyncWorker
import com.lfnj.sport_app.features.training.session.sync.TrainingSyncWorkerFactory
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

/** HU032 CA7: trabajo durable con WorkManager (conectividad, reintento y cancelación al cerrar sesión). */
@RunWith(AndroidJUnit4::class)
class TrainingSyncWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val owner = TrainingOwner.keyOf("7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b")
    private lateinit var database: TrainingDatabase
    private lateinit var sessionManager: SessionManager
    private lateinit var repository: TrainingSessionRepository
    private lateinit var engine: TrainingSyncEngine

    @Before
    fun setUp() {
        server.start()
        val retrofit = BffMobileClient.retrofit(server.url("/").toString())
        val json = BffMobileClient.json
        database = Room.inMemoryDatabaseBuilder(context, TrainingDatabase::class.java).build()
        val codec = TrainingRecordCodec(KeystoreTokenCipher(TestAppContainer.TEST_KEY_ALIAS), json)
        sessionManager = SessionManager(retrofit.create(SessionApi::class.java), InMemoryTokenStore(), json)
        val api = retrofit.create(TrainingSessionApi::class.java)
        repository = TrainingSessionRepository(
            database.trainingSessionDao(), database.transactions, codec, RecordingScheduler(), api, sessionManager, json,
        )
        engine = TrainingSyncEngine(database.trainingSessionDao(), database.transactions, codec, api, sessionManager, json)
    }

    @After
    fun tearDown() {
        server.shutdown()
        database.close()
        TestAppContainer.deleteTestKey()
    }

    private fun worker() = TestListenableWorkerBuilder<TrainingSyncWorker>(context)
        .setWorkerFactory(TrainingSyncWorkerFactory { engine })
        .setInputData(workDataOf(TrainingSyncWorker.KEY_OWNER to owner))
        .build()

    @Test
    fun elTrabajoReintentaAnteIndisponibilidadYTerminaAlConfirmar() = runBlocking {
        server.enqueue(MockResponse().setBody(SESSION_BODY))
        sessionManager.login("ana@example.com", "Secreta-123")
        val id = (repository.start(owner, TrainingSport.RUNNING) as StartResult.Started).sessionId

        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        assertEquals(SyncState.PENDING_SYNC, repository.observe(owner).first().single().syncState)

        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"session_id":"$id","athlete_id":"7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b","sport":"running",""" +
                    """"started_at":"2026-10-12T12:30:00Z","status":"ACTIVE","version":1,""" +
                    """"created_at":"2026-10-12T12:30:01Z","updated_at":"2026-10-12T12:30:01Z"}"""
            )
        )
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(SyncState.SYNCED, repository.observe(owner).first().single().syncState)
    }

    @Test
    fun sinSesionElTrabajoTerminaSinEnviarYConservaLaCola() = runBlocking {
        repository.start(owner, TrainingSport.RUNNING)

        assertEquals(ListenableWorker.Result.success(), worker().doWork())

        assertEquals(0, server.requestCount)
        assertTrue(repository.hasPendingCommands(owner))
    }

    @Test
    fun laSincronizacionEsUnicaPorParticionExigeRedYSeCancelaAlCerrarSesion() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.ERROR)
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(TrainingSyncWorkerFactory { engine })
                .build(),
        )
        val workManager = WorkManager.getInstance(context)
        val scheduler = WorkManagerTrainingSyncScheduler(context)

        scheduler.schedule(owner)
        scheduler.schedule(owner)

        val infos = workManager.getWorkInfosForUniqueWork(WorkManagerTrainingSyncScheduler.uniqueName(owner)).get()
        assertEquals(2, infos.size) // la segunda ejecución queda encadenada detrás de la primera
        infos.forEach { info ->
            assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
            assertTrue(WorkManagerTrainingSyncScheduler.TAG in info.tags)
        }
        assertTrue(infos.none { it.state == WorkInfo.State.SUCCEEDED || it.state == WorkInfo.State.RUNNING })

        scheduler.cancelAll()

        assertTrue(
            workManager.getWorkInfosForUniqueWork(WorkManagerTrainingSyncScheduler.uniqueName(owner)).get()
                .all { it.state == WorkInfo.State.CANCELLED }
        )
    }

    private companion object {
        const val SESSION_BODY =
            """{"token_type":"Bearer","access_token":"access-sintetico","expires_in":900,""" +
                """"refresh_token":"refresh-token-sintetico-000000000001","refresh_expires_in":604800,""" +
                """"user":{"user_id":"7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b","full_name":"Ana Deportista",""" +
                """"email":"ana@example.com","actor_type":"athlete"}}"""
    }
}
