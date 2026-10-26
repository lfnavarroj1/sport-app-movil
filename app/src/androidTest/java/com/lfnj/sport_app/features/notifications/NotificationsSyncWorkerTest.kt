package com.lfnj.sport_app.features.notifications

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.lfnj.sport_app.SportApp
import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.security.KeystoreTokenCipher
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.core.session.revocation.RevocationWorker
import com.lfnj.sport_app.features.notifications.data.NotificationAnnouncer
import com.lfnj.sport_app.features.notifications.data.NotificationsApi
import com.lfnj.sport_app.features.notifications.data.NotificationsRepository
import com.lfnj.sport_app.features.notifications.data.local.NotificationsCodec
import com.lfnj.sport_app.features.notifications.data.local.NotificationsDatabase
import com.lfnj.sport_app.features.notifications.model.NotificationChannels
import com.lfnj.sport_app.features.notifications.model.NotificationsOwner
import com.lfnj.sport_app.features.notifications.model.SystemNotificationsStatus
import com.lfnj.sport_app.features.notifications.sync.NotificationsSyncEngine
import com.lfnj.sport_app.features.notifications.sync.NotificationsSyncWorker
import com.lfnj.sport_app.features.notifications.sync.NotificationsSyncWorkerFactory
import com.lfnj.sport_app.features.notifications.sync.WorkManagerInboxSyncScheduler
import com.lfnj.sport_app.features.notifications.system.AndroidNotificationAnnouncer
import com.lfnj.sport_app.features.training.metrics.sync.MetricsSyncWorker
import com.lfnj.sport_app.features.training.session.sync.TrainingSyncWorker
import com.lfnj.sport_app.testing.InMemoryTokenStore
import com.lfnj.sport_app.testing.TestAppContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * HU039 en el dispositivo: trabajo periódico de WorkManager (15 min, con red, único), notificación
 * del sistema en el canal de su prioridad con una bandeja simulada (MockWebServer) y fábrica de
 * trabajos de [SportApp] que sigue creando los de HU032, HU034 y HU004 (impacto compartido).
 */
@RunWith(AndroidJUnit4::class)
class NotificationsSyncWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val owner = NotificationsOwner.keyOf(USER_ID)
    private val systemNotifications = context.getSystemService(NotificationManager::class.java)
    private lateinit var database: NotificationsDatabase
    private lateinit var sessionManager: SessionManager
    private lateinit var repository: NotificationsRepository
    private lateinit var announcer: AndroidNotificationAnnouncer

    /** `notification_id` que el repositorio entregó al sistema, en orden: prueba directa de la deduplicación. */
    private val announcedIds = java.util.Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun setUp() {
        server.start()
        val retrofit = BffMobileClient.retrofit(server.url("/").toString())
        val json = BffMobileClient.json
        database = Room.inMemoryDatabaseBuilder(context, NotificationsDatabase::class.java).build()
        sessionManager = SessionManager(retrofit.create(SessionApi::class.java), InMemoryTokenStore(), json)
        announcer = AndroidNotificationAnnouncer(context).apply { ensureChannels() }
        repository = NotificationsRepository(
            database.notificationsCacheDao(),
            NotificationsCodec(KeystoreTokenCipher(TestAppContainer.NOTIFICATIONS_TEST_KEY_ALIAS), json),
            retrofit.create(NotificationsApi::class.java),
            sessionManager,
            json,
            object : NotificationAnnouncer {
                override fun announce(items: List<com.lfnj.sport_app.features.notifications.model.InboxNotification>) {
                    announcedIds += items.map { it.notificationId }
                    announcer.announce(items)
                }

                override fun dismiss(notificationId: String) = announcer.dismiss(notificationId)
            },
        )
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setMinimumLoggingLevel(android.util.Log.ERROR).build(),
        )
    }

    @After
    fun tearDown() {
        listOf(NOTIFICATION_ID, REMINDER_ID).forEach { announcer.dismiss(it) }
        server.shutdown()
        database.close()
        TestAppContainer.deleteTestKey(TestAppContainer.NOTIFICATIONS_TEST_KEY_ALIAS)
    }

    private fun json(body: String) = MockResponse().setBody(body)

    private fun login() = runBlocking {
        server.enqueue(json(SESSION))
        sessionManager.login("ana@example.com", "Secreta-123")
        server.takeRequest(1, TimeUnit.SECONDS)
    }

    private fun worker() = TestListenableWorkerBuilder<NotificationsSyncWorker>(context)
        .setWorkerFactory(NotificationsSyncWorkerFactory { NotificationsSyncEngine(sessionManager, repository) })
        .build()

    private fun grantPostNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun posted(tag: String) = systemNotifications.activeNotifications.filter { it.tag == tag && it.id == AndroidNotificationAnnouncer.NOTIFICATION_ID }

    /**
     * `notify` y `cancel` llegan al sistema de forma asíncrona: `activeNotifications` puede no
     * reflejarlos aún al terminar el trabajo (intermitencia observada en G43 y G45). Se sondea hasta
     * que haya [expected] notificaciones o venza [POST_TIMEOUT_MS] y se devuelve la última lectura;
     * las aserciones no cambian.
     */
    private fun awaitPosted(tag: String, expected: Int): List<android.service.notification.StatusBarNotification> {
        val deadline = System.currentTimeMillis() + POST_TIMEOUT_MS
        var current = posted(tag)
        while (current.size != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
            current = posted(tag)
        }
        return current
    }

    @Test
    fun elTrabajoSincronizaLaBandejaYPublicaUnaNotificacionPorElementoNuevoEnSuCanal() {
        grantPostNotifications()
        assumeTrue("Notificaciones del sistema desactivadas en el dispositivo", announcer.status() == SystemNotificationsStatus.ENABLED)
        login()
        val page = """{"items":[${item(REMINDER_ID, "EVENT_REMINDER_24H", "HIGH")},${item(NOTIFICATION_ID, "EVENT_PUBLISHED_MATCH", "NORMAL")}],"next_cursor":null}"""
        server.enqueue(json(page))

        assertEquals(ListenableWorker.Result.success(), runBlocking { worker().doWork() })

        assertEquals("/v1/me/notifications", server.takeRequest(1, TimeUnit.SECONDS)?.requestUrl?.encodedPath)
        val reminder = awaitPosted(REMINDER_ID, 1).single()
        assertEquals(NotificationChannels.EVENT_ALERTS, reminder.notification.channelId)
        assertEquals(NotificationChannels.EVENT_UPDATES, awaitPosted(NOTIFICATION_ID, 1).single().notification.channelId)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, systemNotifications.getNotificationChannel(NotificationChannels.EVENT_ALERTS).importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, systemNotifications.getNotificationChannel(NotificationChannels.EVENT_UPDATES).importance)
        assertTrue(runBlocking { repository.observe(owner).first() }!!.items.size == 2)

        // Segunda corrida periódica con la misma bandeja: deduplicación local por notification_id.
        announcer.dismiss(REMINDER_ID)
        assertTrue("el retiro llegó al sistema", awaitPosted(REMINDER_ID, 0).isEmpty())
        server.enqueue(json(page))
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker().doWork() })
        // La segunda corrida no entrega nada al sistema: la deduplicación ocurre antes de `notify`.
        assertEquals("no se vuelve a anunciar", listOf(REMINDER_ID, NOTIFICATION_ID), announcedIds.toList())
        assertTrue("no se vuelve a publicar", posted(REMINDER_ID).isEmpty())
    }

    @Test
    fun sinRedReintentaYSinSesionTerminaSinConsultar() {
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker().doWork() })
        assertEquals(0, server.requestCount)

        login()
        server.shutdown()
        assertEquals(ListenableWorker.Result.retry(), runBlocking { worker().doWork() })
    }

    @Test
    fun elTrabajoEsPeriodicoUnicoConRedYSeCancelaAlCerrarSesion() {
        val workManager = WorkManager.getInstance(context)
        val scheduler = WorkManagerInboxSyncScheduler(context)

        scheduler.schedule()
        scheduler.schedule()

        val infos = workManager.getWorkInfosForUniqueWork(WorkManagerInboxSyncScheduler.UNIQUE_NAME).get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
        assertEquals(TimeUnit.MINUTES.toMillis(15), infos.single().periodicityInfo?.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, infos.single().constraints.requiredNetworkType)

        scheduler.cancel()
        assertEquals(WorkInfo.State.CANCELLED, workManager.getWorkInfosForUniqueWork(WorkManagerInboxSyncScheduler.UNIQUE_NAME).get().single().state)
    }

    @Test
    fun laFabricaDeSportAppSigueCreandoLosTrabajosDeHu032Hu034YHu004() {
        val factory = (context as SportApp).workManagerConfiguration.workerFactory

        fun created(workerClass: Class<out ListenableWorker>): ListenableWorker =
            TestListenableWorkerBuilder.from(context, workerClass).setWorkerFactory(factory).build()

        assertTrue(created(TrainingSyncWorker::class.java) is TrainingSyncWorker)
        assertTrue(created(MetricsSyncWorker::class.java) is MetricsSyncWorker)
        assertTrue(created(RevocationWorker::class.java) is RevocationWorker)
        assertTrue(created(NotificationsSyncWorker::class.java) is NotificationsSyncWorker)
    }

    private companion object {
        const val USER_ID = "7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b"
        const val NOTIFICATION_ID = "5b0e7c1a-2d3f-4a5b-8c6d-7e8f9a0b1c2d"
        const val REMINDER_ID = "4a9d6b09-1c2e-4f4a-9b5c-6d7e8f9a0b1c"
        const val POST_TIMEOUT_MS = 5_000L
        const val POLL_MS = 100L

        fun item(id: String, type: String, priority: String) =
            """{"notification_id":"$id","type":"$type","priority":"$priority","event_id":"683f4e38-6bb3-4c47-a6dc-9bb1588c67ef",""" +
                """"title":"Evento sintético HU039","body":"Prueba instrumentada de notificaciones.",""" +
                """"created_at":"2026-10-25T13:00:00Z","read_at":null}"""

        const val SESSION =
            """{"token_type":"Bearer","access_token":"access-sintetico","expires_in":900,""" +
                """"refresh_token":"refresh-token-sintetico-000000000001","refresh_expires_in":604800,""" +
                """"user":{"user_id":"$USER_ID","full_name":"Ana Deportista",""" +
                """"email":"ana@example.com","actor_type":"athlete"}}"""
    }
}
