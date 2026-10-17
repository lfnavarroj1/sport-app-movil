package com.lfnj.sport_app.core.session.revocation

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
import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.security.KeystoreTokenCipher
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.features.training.session.sync.WorkManagerTrainingSyncScheduler
import com.lfnj.sport_app.testing.TestAppContainer
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** HU004 CA7: trabajo durable de revocación con WorkManager (conectividad, reintento y unicidad). */
@RunWith(AndroidJUnit4::class)
class RevocationWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private var now = System.currentTimeMillis()
    private lateinit var database: RevocationDatabase
    private lateinit var queue: RevocationQueue

    @Before
    fun setUp() {
        server.start()
        database = Room.inMemoryDatabaseBuilder(context, RevocationDatabase::class.java).build()
        queue = RevocationQueue(
            dao = database.revocationDao(),
            cipher = KeystoreTokenCipher(TestAppContainer.REVOCATION_TEST_KEY_ALIAS),
            api = BffMobileClient.retrofit(server.url("/").toString()).create(SessionApi::class.java),
            json = BffMobileClient.json,
            scheduler = {},
            clock = { now },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        database.close()
        TestAppContainer.deleteTestKey(TestAppContainer.REVOCATION_TEST_KEY_ALIAS)
    }

    private fun worker() = TestListenableWorkerBuilder<RevocationWorker>(context)
        .setWorkerFactory(RevocationWorkerFactory { queue })
        .build()

    @Test
    fun ca7ElTrabajoReintentaAnteIndisponibilidadYTerminaAlConfirmar() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        queue.revoke(REFRESH_TOKEN, now + 604_800_000L)
        now += RevocationQueue.INITIAL_BACKOFF_MILLIS

        server.enqueue(MockResponse().setResponseCode(504))
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        assertTrue(queue.hasPending())

        now += RevocationQueue.backoffMillis(2)
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertFalse(queue.hasPending())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun ca7UnaEntradaVencidaTerminaElTrabajoSinEnviarla() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        queue.revoke(REFRESH_TOKEN, now + 604_800_000L)
        now += 604_800_000L

        assertEquals(ListenableWorker.Result.success(), worker().doWork())

        assertEquals(1, server.requestCount)
        assertFalse(queue.hasPending())
    }

    @Test
    fun elTrabajoEsUnicoExigeRedYNoLoCancelaLaSincronizacionDeEntrenamientos() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.ERROR)
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(RevocationWorkerFactory { queue })
                .build(),
        )
        val workManager = WorkManager.getInstance(context)
        val scheduler = WorkManagerRevocationScheduler(context)

        scheduler.schedule()
        scheduler.schedule()

        val infos = workManager.getWorkInfosForUniqueWork(WorkManagerRevocationScheduler.UNIQUE_NAME).get()
        assertEquals("KEEP conserva un único trabajo", 1, infos.size)
        val info = infos.single()
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertTrue(WorkManagerRevocationScheduler.TAG in info.tags)
        assertEquals(WorkInfo.State.ENQUEUED, info.state)

        // Cerrar sesión cancela la sincronización de HU032, pero no la revocación pendiente.
        WorkManagerTrainingSyncScheduler(context).cancelAll()

        assertEquals(
            WorkInfo.State.ENQUEUED,
            workManager.getWorkInfosForUniqueWork(WorkManagerRevocationScheduler.UNIQUE_NAME).get().single().state,
        )
    }

    private companion object {
        const val REFRESH_TOKEN = "refresh-token-sintetico-000000000001"
    }
}
