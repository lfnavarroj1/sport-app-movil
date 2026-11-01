package com.lfnj.sport_app.features.notifications.sync

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.notifications.data.InboxGateway
import com.lfnj.sport_app.features.notifications.data.InboxRefresh
import com.lfnj.sport_app.features.notifications.data.MarkReadResult
import com.lfnj.sport_app.features.notifications.model.InboxSnapshot
import com.lfnj.sport_app.features.notifications.model.NotificationsOwner
import com.lfnj.sport_app.testing.FakeTokenStore
import com.lfnj.sport_app.testing.SessionFixtures
import com.lfnj.sport_app.testing.TrainingFixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

/** HU039: corrida periódica de la bandeja (ADR-045 §1) y su activación por sesión. */
class NotificationsSyncTest {
    private val server = MockWebServer().apply { start() }
    private val retrofit = BffMobileClient.retrofit(
        server.url("/").toString(),
        BffMobileClient.okHttp().newBuilder().retryOnConnectionFailure(false).build(),
    )
    private val tokenStore = FakeTokenStore()
    private val sessionManager = SessionManager(retrofit.create(SessionApi::class.java), tokenStore, BffMobileClient.json, clock = { 1_000_000L })

    private class FakeInbox(var result: InboxRefresh = InboxRefresh.Updated) : InboxGateway {
        val owners = mutableListOf<String>()
        override fun observe(owner: String): Flow<InboxSnapshot?> = flowOf(null)
        override suspend fun refresh(owner: String): InboxRefresh {
            owners += owner
            return result
        }
        override suspend fun loadMore(owner: String): InboxRefresh = result
        override suspend fun markRead(owner: String, notificationId: String): MarkReadResult = MarkReadResult.NoConnection
    }

    @After
    fun tearDown() = server.shutdown()

    private suspend fun login() {
        server.enqueue(SessionFixtures.ok(SessionFixtures.sessionBody()))
        sessionManager.login("ana@example.com", "Secreta-123")
        server.takeRequest(1, TimeUnit.SECONDS)
    }

    @Test
    fun `sincroniza la particion del deportista autenticado`() = runTest {
        login()
        val inbox = FakeInbox()

        assertEquals(InboxSyncRun.COMPLETED, NotificationsSyncEngine(sessionManager, inbox).run())
        assertEquals(listOf(NotificationsOwner.keyOf(TrainingFixtures.USER_ID)), inbox.owners)
    }

    @Test
    fun `fallas transitorias se reintentan y las definitivas esperan al siguiente periodo`() = runTest {
        login()
        val inbox = FakeInbox(InboxRefresh.NoConnection)
        val engine = NotificationsSyncEngine(sessionManager, inbox)

        assertEquals(InboxSyncRun.RETRY, engine.run())
        inbox.result = InboxRefresh.Unavailable(null)
        assertEquals(InboxSyncRun.RETRY, engine.run())
        inbox.result = InboxRefresh.InvalidResponse
        assertEquals(InboxSyncRun.COMPLETED, engine.run())
        inbox.result = InboxRefresh.SignedOut
        assertEquals(InboxSyncRun.SUSPENDED, engine.run())
    }

    @Test
    fun `sin sesion guardada no consulta la bandeja`() = runTest {
        val inbox = FakeInbox()

        assertEquals(InboxSyncRun.SUSPENDED, NotificationsSyncEngine(sessionManager, inbox).run())
        assertEquals(emptyList<String>(), inbox.owners)
    }

    private class RecordingScheduler : InboxSyncScheduler {
        val calls = mutableListOf<String>()
        override fun schedule() {
            calls += "schedule"
        }
        override fun cancel() {
            calls += "cancel"
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `programa con sesion, no cancela al arrancar sin ella y cancela al cerrar sesion`() = runTest {
        val user = MutableStateFlow<SessionUser?>(null)
        val scheduler = RecordingScheduler()
        val job = InboxSyncCoordinator(user, scheduler).start(TestScope(UnconfinedTestDispatcher(testScheduler)))

        assertEquals(emptyList<String>(), scheduler.calls)
        user.value = SessionUser(TrainingFixtures.USER_ID, "Ana Prueba", "ana@example.com", ActorType.ATHLETE)
        user.value = null
        assertEquals(listOf("schedule", "cancel"), scheduler.calls)
        job.cancelAndJoin()
    }

    @Test
    fun `el trabajo es periodico de 15 minutos con red y sin datos de entrada`() {
        val request = WorkManagerInboxSyncScheduler.request()

        assertEquals(TimeUnit.MINUTES.toMillis(15), request.workSpec.intervalDuration)
        assertEquals(androidx.work.NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(androidx.work.BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(0, request.workSpec.input.keyValueMap.size)
        assertEquals(true, WorkManagerInboxSyncScheduler.TAG in request.tags)
    }
}
