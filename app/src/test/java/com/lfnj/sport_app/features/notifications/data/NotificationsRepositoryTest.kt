package com.lfnj.sport_app.features.notifications.data

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.notifications.data.local.NotificationsCodec
import com.lfnj.sport_app.features.notifications.model.NotificationPreferences
import com.lfnj.sport_app.features.notifications.model.NotificationPriority
import com.lfnj.sport_app.features.notifications.model.NotificationType
import com.lfnj.sport_app.features.notifications.model.NotificationsOwner
import com.lfnj.sport_app.testing.FakeCipher
import com.lfnj.sport_app.testing.FakeTokenStore
import com.lfnj.sport_app.testing.InMemoryNotificationsDao
import com.lfnj.sport_app.testing.MutableClock
import com.lfnj.sport_app.testing.NotificationFixtures
import com.lfnj.sport_app.testing.NotificationFixtures.NOTIFICATION_ID
import com.lfnj.sport_app.testing.NotificationFixtures.OTHER_NOTIFICATION_ID
import com.lfnj.sport_app.testing.NotificationFixtures.THIRD_NOTIFICATION_ID
import com.lfnj.sport_app.testing.RecordingAnnouncer
import com.lfnj.sport_app.testing.SessionFixtures
import com.lfnj.sport_app.testing.TrainingFixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * HU039 contra un `bff-mobile` simulado con MockWebServer y la bandeja en memoria (cifrado de
 * prueba). Contrato simulado según ADR-045 §11: **no** es un E2E.
 */
class NotificationsRepositoryTest {
    private val server = MockWebServer().apply { start() }
    private val retrofit = BffMobileClient.retrofit(
        server.url("/").toString(),
        BffMobileClient.okHttp().newBuilder().retryOnConnectionFailure(false).build(),
    )
    private val sessionManager = SessionManager(retrofit.create(SessionApi::class.java), FakeTokenStore(), BffMobileClient.json, clock = { 1_000_000L })
    private val dao = InMemoryNotificationsDao()
    private val cipher = FakeCipher()
    private val announcer = RecordingAnnouncer()
    private val clock = MutableClock(Instant.parse("2026-10-25T15:00:00Z"), ZoneId.of("America/Bogota"))
    private val codec = NotificationsCodec(cipher, BffMobileClient.json)
    private val repository = NotificationsRepository(
        dao, codec, retrofit.create(NotificationsApi::class.java), sessionManager, BffMobileClient.json, announcer, clock,
    )
    private val owner = NotificationsOwner.keyOf(TrainingFixtures.USER_ID)

    @After
    fun tearDown() = server.shutdown()

    private suspend fun login() {
        server.enqueue(SessionFixtures.ok(SessionFixtures.sessionBody()))
        sessionManager.login("ana@example.com", "Secreta-123")
        server.takeRequest()
    }

    private fun takeRequest(): RecordedRequest = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS)) { "No llegó la solicitud esperada" }

    private fun respond(body: String, status: Int = 200) = server.enqueue(MockResponse().setResponseCode(status).setBody(body))

    private suspend fun cached() = dao.read(owner)?.let(codec::decode)

    // ----------------------------------------------------------------- bandeja

    @Test
    fun `consulta la primera pagina con el token y guarda la bandeja cifrada`() = runTest {
        login()
        respond(NotificationFixtures.page(listOf(NotificationFixtures.reminder(), NotificationFixtures.item()), nextCursor = "c2"))

        assertEquals(InboxRefresh.Updated, repository.refresh(owner))

        val request = takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/me/notifications", request.requestUrl?.encodedPath)
        assertEquals("20", request.requestUrl?.queryParameter("limit"))
        assertNull(request.requestUrl?.queryParameter("cursor"))
        assertEquals("Bearer ${SessionFixtures.ACCESS_TOKEN}", request.getHeader("Authorization"))
        val snapshot = checkNotNull(cached())
        assertEquals(listOf(OTHER_NOTIFICATION_ID, NOTIFICATION_ID), snapshot.ordered.map { it.notificationId })
        assertEquals("c2", snapshot.nextCursor)
        assertEquals(clock.instant(), snapshot.syncedAt)
        val reminder = snapshot.items.first { it.notificationId == OTHER_NOTIFICATION_ID }
        assertEquals(NotificationType.EVENT_REMINDER_24H, reminder.type)
        assertEquals(NotificationPriority.HIGH, reminder.priority)
        assertEquals(NotificationFixtures.EVENT_ID, reminder.eventId)
        // En el dispositivo solo queda texto cifrado: ni título ni evento en claro.
        val raw = dao.read(owner)!!.payload.toString(Charsets.UTF_8)
        assertFalse(raw.contains("Bogotá"))
        assertFalse(raw.contains(NotificationFixtures.EVENT_ID))
    }

    @Test
    fun `anuncia una sola vez cada notificacion nueva sin leer aunque se sincronice de nuevo`() = runTest {
        login()
        val firstPage = NotificationFixtures.page(listOf(NotificationFixtures.item(), NotificationFixtures.reminder(readAt = "2026-10-25T14:30:00Z")))
        respond(firstPage)
        repository.refresh(owner)
        assertEquals(listOf(NOTIFICATION_ID), announcer.announced.map { it.notificationId })

        // Reintento de la sincronización con la misma página (periódica y al abrir): sin duplicados.
        respond(firstPage)
        repository.refresh(owner)
        assertEquals(1, announcer.announced.size)

        // Llega una notificación nueva: solo ella se anuncia.
        respond(
            NotificationFixtures.page(
                listOf(
                    NotificationFixtures.item(THIRD_NOTIFICATION_ID, createdAt = "2026-10-25T14:50:00Z"),
                    NotificationFixtures.item(),
                    NotificationFixtures.reminder(readAt = "2026-10-25T14:30:00Z"),
                )
            )
        )
        repository.refresh(owner)
        assertEquals(listOf(NOTIFICATION_ID, THIRD_NOTIFICATION_ID), announcer.announced.map { it.notificationId })
    }

    @Test
    fun `una notificacion repetida dentro de la misma pagina se guarda y anuncia una vez`() = runTest {
        login()
        respond(NotificationFixtures.page(listOf(NotificationFixtures.item(), NotificationFixtures.item())))

        repository.refresh(owner)

        assertEquals(1, cached()!!.items.size)
        assertEquals(1, announcer.announced.size)
    }

    @Test
    fun `si la bandeja no se guarda no se anuncia para no perder la deduplicacion`() = runTest {
        login()
        dao.failWrites = true
        respond(NotificationFixtures.page(listOf(NotificationFixtures.item())))

        assertEquals(InboxRefresh.StorageFailed, repository.refresh(owner))
        assertTrue(announcer.announced.isEmpty())
    }

    @Test
    fun `una falla del sistema de notificaciones no afecta la bandeja`() = runTest {
        login()
        announcer.fail = true
        respond(NotificationFixtures.page(listOf(NotificationFixtures.item())))

        assertEquals(InboxRefresh.Updated, repository.refresh(owner))
        assertEquals(1, cached()!!.items.size)
    }

    @Test
    fun `cargar mas agrega la pagina anterior sin anunciarla`() = runTest {
        login()
        respond(NotificationFixtures.page(listOf(NotificationFixtures.item()), nextCursor = "c2"))
        repository.refresh(owner)
        respond(NotificationFixtures.page(listOf(NotificationFixtures.item(OTHER_NOTIFICATION_ID, createdAt = "2026-10-20T10:00:00Z"))))

        assertEquals(InboxRefresh.Updated, repository.loadMore(owner))

        takeRequest()
        assertEquals("c2", takeRequest().requestUrl?.queryParameter("cursor"))
        val snapshot = cached()!!
        assertEquals(listOf(NOTIFICATION_ID, OTHER_NOTIFICATION_ID), snapshot.ordered.map { it.notificationId })
        assertNull(snapshot.nextCursor)
        assertEquals(listOf(NOTIFICATION_ID), announcer.announced.map { it.notificationId })
    }

    @Test
    fun `un cursor rechazado vuelve a consultar desde el principio`() = runTest {
        login()
        respond(NotificationFixtures.page(listOf(NotificationFixtures.item()), nextCursor = "viejo"))
        repository.refresh(owner)
        server.enqueue(NotificationFixtures.error(422, "validation_error", """[{"field":"cursor","issue":"invalid"}]"""))
        respond(NotificationFixtures.page(listOf(NotificationFixtures.item())))

        assertEquals(InboxRefresh.Updated, repository.loadMore(owner))

        takeRequest()
        takeRequest()
        assertNull(takeRequest().requestUrl?.queryParameter("cursor"))
        assertNull(cached()!!.nextCursor)
    }

    @Test
    fun `sin red conserva la bandeja anterior`() = runTest {
        login()
        respond(NotificationFixtures.page(listOf(NotificationFixtures.item())))
        repository.refresh(owner)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        assertEquals(InboxRefresh.NoConnection, repository.refresh(owner))
        assertEquals(1, cached()!!.items.size)
    }

    @Test
    fun `los errores del contrato se traducen sin datos`() = runTest {
        login()
        server.enqueue(NotificationFixtures.error(403, "permission_required"))
        assertEquals(InboxRefresh.PermissionRequired, repository.refresh(owner))
        server.enqueue(NotificationFixtures.error(503, "upstream_unavailable"))
        assertEquals(InboxRefresh.Unavailable("8c1f2d3e-0000-4000-8000-000000000039"), repository.refresh(owner))
        server.enqueue(NotificationFixtures.error(504, "upstream_timeout"))
        assertTrue(repository.refresh(owner) is InboxRefresh.Unavailable)
        respond(NotificationFixtures.page(listOf(NotificationFixtures.item(type = "EVENT_CANCELLED"))))
        assertEquals(InboxRefresh.InvalidResponse, repository.refresh(owner))
        respond(NotificationFixtures.page(listOf(NotificationFixtures.item(priority = "URGENT"))))
        assertEquals(InboxRefresh.InvalidResponse, repository.refresh(owner))
        respond(NotificationFixtures.page(listOf(NotificationFixtures.item(createdAt = "ayer"))))
        assertEquals(InboxRefresh.InvalidResponse, repository.refresh(owner))
        assertNull(cached())
    }

    @Test
    fun `sin sesion no consulta el BFF`() = runTest {
        assertEquals(InboxRefresh.SignedOut, repository.refresh(owner))
        assertEquals(0, server.requestCount)
    }

    // ----------------------------------------------------------------- marcar como leída

    @Test
    fun `marcar como leida actualiza la bandeja y retira la notificacion del sistema`() = runTest {
        login()
        respond(NotificationFixtures.page(listOf(NotificationFixtures.item())))
        repository.refresh(owner)
        respond(NotificationFixtures.item(readAt = "2026-10-25T15:01:00Z"))

        val result = repository.markRead(owner, NOTIFICATION_ID)

        takeRequest()
        val request = takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/me/notifications/$NOTIFICATION_ID/read", request.requestUrl?.encodedPath)
        assertEquals(0L, request.bodySize)
        assertTrue(result is MarkReadResult.Marked)
        assertEquals(Instant.parse("2026-10-25T15:01:00Z"), cached()!!.items.single().readAt)
        assertEquals(listOf(NOTIFICATION_ID), announcer.dismissed)
    }

    @Test
    fun `marcar como leida traduce 404, sin red y respuestas no leidas`() = runTest {
        login()
        server.enqueue(NotificationFixtures.error(404, "notification_not_found"))
        assertEquals(MarkReadResult.NotFound, repository.markRead(owner, NOTIFICATION_ID))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals(MarkReadResult.NoConnection, repository.markRead(owner, NOTIFICATION_ID))
        respond(NotificationFixtures.item())
        assertEquals(MarkReadResult.InvalidResponse, repository.markRead(owner, NOTIFICATION_ID))
        // Un identificador que no es UUID no se envía.
        val before = server.requestCount
        assertEquals(MarkReadResult.NotFound, repository.markRead(owner, "../otra"))
        assertEquals(before, server.requestCount)
    }

    // ----------------------------------------------------------------- preferencias

    @Test
    fun `lee y reemplaza las preferencias con expected_version`() = runTest {
        login()
        respond(NotificationFixtures.preferences(version = 0))
        val loaded = (repository.preferences() as PreferencesResult.Loaded).preferences
        assertEquals(NotificationPreferences(eventMatches = true, eventReminders = true, pushEnabled = true, version = 0), loaded)
        respond(NotificationFixtures.preferences(eventMatches = false, version = 1))

        val saved = repository.savePreferences(loaded.copy(eventMatches = false))

        takeRequest()
        val put = takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/v1/me/notification-preferences", put.requestUrl?.encodedPath)
        assertEquals(
            """{"event_matches":false,"event_reminders":true,"push_enabled":true,"expected_version":0}""",
            put.body.readUtf8(),
        )
        assertEquals(SavePreferencesResult.Saved(NotificationPreferences(false, true, true, 1)), saved)
    }

    @Test
    fun `un conflicto de version no se sobrescribe`() = runTest {
        login()
        server.enqueue(NotificationFixtures.error(409, "version_conflict", """[{"field":"expected_version","issue":"3"}]"""))
        assertEquals(SavePreferencesResult.Conflict, repository.savePreferences(NotificationPreferences(true, false, true, 2)))
        server.enqueue(NotificationFixtures.error(422, "validation_error", """[{"field":"event_matches","issue":"missing"}]"""))
        assertEquals(SavePreferencesResult.Invalid(listOf("missing")), repository.savePreferences(NotificationPreferences(true, false, true, 2)))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals(SavePreferencesResult.NoConnection, repository.savePreferences(NotificationPreferences(true, false, true, 2)))
        server.enqueue(NotificationFixtures.error(403, "permission_required"))
        assertEquals(PreferencesResult.PermissionRequired, repository.preferences())
    }

    // ----------------------------------------------------------------- limpieza

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `al cerrar sesion se elimina la bandeja y al entrar otro deportista se eliminan las ajenas`() = runTest {
        val user = MutableStateFlow<SessionUser?>(SessionUser(TrainingFixtures.USER_ID, "Ana Prueba", "ana@example.com", ActorType.ATHLETE))
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        dao.replace(com.lfnj.sport_app.features.notifications.data.local.NotificationsCacheEntity(owner, byteArrayOf(1)))
        val other = NotificationsOwner.keyOf(TrainingFixtures.OTHER_USER_ID)
        dao.replace(com.lfnj.sport_app.features.notifications.data.local.NotificationsCacheEntity(other, byteArrayOf(2)))
        val job = NotificationsCacheJanitor(user, repository).start(scope)

        assertEquals(setOf(owner), dao.rows.value.keys)
        user.value = null
        assertTrue(dao.rows.first().isEmpty())
        job.cancelAndJoin()
    }
}
