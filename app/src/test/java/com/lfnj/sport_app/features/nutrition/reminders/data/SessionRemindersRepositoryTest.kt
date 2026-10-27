package com.lfnj.sport_app.features.nutrition.reminders.data

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.nutrition.reminders.data.local.SessionRemindersCodec
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderPresentation
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderSetting
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderType
import com.lfnj.sport_app.features.training.session.model.TrainingOwner
import com.lfnj.sport_app.testing.FakeCipher
import com.lfnj.sport_app.testing.FakeTokenStore
import com.lfnj.sport_app.testing.InMemorySessionRemindersDao
import com.lfnj.sport_app.testing.MutableClock
import com.lfnj.sport_app.testing.SessionFixtures
import com.lfnj.sport_app.testing.SessionRemindersFixtures
import com.lfnj.sport_app.testing.TrainingFixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * HU038 contra un `bff-mobile` simulado con MockWebServer y almacenamiento en memoria (cifrado de
 * prueba). Contrato simulado según `bff-mobile` 0.18.0: **no** es un E2E.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRemindersRepositoryTest {
    private val server = MockWebServer().apply { start() }
    private val retrofit = BffMobileClient.retrofit(
        server.url("/").toString(),
        BffMobileClient.okHttp().newBuilder().retryOnConnectionFailure(false).build(),
    )
    private val sessionManager = SessionManager(retrofit.create(SessionApi::class.java), FakeTokenStore(), BffMobileClient.json, clock = { 1_000_000L })
    private val dao = InMemorySessionRemindersDao()
    private val cipher = FakeCipher()
    private val clock = MutableClock(Instant.parse("2026-10-26T15:00:00Z"), ZoneId.of("America/Bogota"))
    private val repository = SessionRemindersRepository(
        dao, SessionRemindersCodec(cipher, BffMobileClient.json), retrofit.create(SessionRemindersApi::class.java),
        sessionManager, BffMobileClient.json, clock,
    )
    private val owner = TrainingOwner.keyOf(TrainingFixtures.USER_ID)

    @After
    fun tearDown() = server.shutdown()

    private suspend fun login() {
        server.enqueue(SessionFixtures.ok(SessionFixtures.sessionBody()))
        sessionManager.login("ana@example.com", "Secreta-123")
        server.takeRequest()
    }

    private fun takeRequest(): RecordedRequest = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS)) { "No llegó la solicitud esperada" }

    private fun respond(body: String, status: Int = 200) = server.enqueue(MockResponse().setResponseCode(status).setBody(body))

    private suspend fun downloaded() {
        respond(SessionRemindersFixtures.rulesBody())
        respond(SessionRemindersFixtures.preferencesBody())
        assertEquals(ReminderRefresh.Updated, repository.refresh(owner))
        takeRequest()
        takeRequest()
    }

    // ----------------------------------------------------------------- reglas y preferencias

    @Test
    fun `descarga ruleset y preferencias con el token y guarda la configuracion cifrada`() = runTest {
        login()
        respond(SessionRemindersFixtures.rulesBody())
        respond(SessionRemindersFixtures.preferencesBody(version = 0))

        assertEquals(ReminderRefresh.Updated, repository.refresh(owner))

        val rules = takeRequest()
        assertEquals("GET", rules.method)
        assertEquals("/v1/session-reminder-rules", rules.requestUrl?.encodedPath)
        assertEquals("Bearer ${SessionFixtures.ACCESS_TOKEN}", rules.getHeader("Authorization"))
        val preferences = takeRequest()
        assertEquals("GET", preferences.method)
        assertEquals("/v1/me/session-reminder-preferences", preferences.requestUrl?.encodedPath)

        val config = assertNotNullValue(repository.observe(owner).first())
        assertEquals(SessionRemindersFixtures.RULESET, config.ruleset)
        assertEquals(SessionRemindersFixtures.DEFAULTS, config.preferences)
        assertEquals(clock.instant, config.syncedAt)
        // Cifrado: los mensajes no quedan en claro en el almacenamiento.
        val stored = dao.configs.value.getValue(owner).payload.toString(Charsets.UTF_8)
        assertFalse(stored.contains("Hidrátate"))
    }

    @Test
    fun `los mensajes y el aviso se conservan sin modificar`() = runTest {
        login()
        downloaded()

        val rules = assertNotNullValue(repository.observe(owner).first()).ruleset.rules
        assertEquals(listOf(SessionRemindersFixtures.HYDRATION_MESSAGE, SessionRemindersFixtures.NUTRITION_MESSAGE), rules.map { it.message })
        assertTrue(rules.all { it.disclaimer == SessionRemindersFixtures.DISCLAIMER })
    }

    @Test
    fun `un ruleset incompleto, con tipo desconocido o incoherente se rechaza completo`() = runTest {
        login()
        val invalid = listOf(
            SessionRemindersFixtures.rulesBody(rules = listOf(SessionRemindersFixtures.rule("HYDRATION", 15, 10, 60, 0, "x"))),
            SessionRemindersFixtures.rulesBody(
                rules = listOf(
                    SessionRemindersFixtures.rule("HYDRATION", 15, 10, 60, 0, "x"),
                    SessionRemindersFixtures.rule("CAFFEINE", 45, 30, 120, 60, "y"),
                )
            ),
            SessionRemindersFixtures.rulesBody(
                rules = listOf(
                    SessionRemindersFixtures.rule("HYDRATION", 5, 10, 60, 0, "x"),
                    SessionRemindersFixtures.rule("NUTRITION", 45, 30, 120, 60, "y"),
                )
            ),
        )
        invalid.forEach { body ->
            respond(body)
            assertEquals(ReminderRefresh.InvalidResponse, repository.refresh(owner))
            takeRequest()
        }
        assertNull(repository.observe(owner).first())
    }

    @Test
    fun `sin red conserva la ultima configuracion confirmada`() = runTest {
        login()
        downloaded()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        assertEquals(ReminderRefresh.NoConnection, repository.refresh(owner))
        assertNotNull(repository.observe(owner).first())
    }

    @Test
    fun `sin red y sin descarga previa no hay configuracion (CA5)`() = runTest {
        login()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        assertEquals(ReminderRefresh.NoConnection, repository.refresh(owner))
        assertNull(repository.observe(owner).first())
    }

    @Test
    fun `sin la capacidad se borra la configuracion para que el motor no evalue`() = runTest {
        login()
        downloaded()
        server.enqueue(SessionFixtures.error(403, "capability_required"))

        assertEquals(ReminderRefresh.CapabilityRequired, repository.refresh(owner))
        assertNull(repository.observe(owner).first())
    }

    @Test
    fun `sin permiso de deportista, servicio caido o preferencias fallidas se informa sin borrar`() = runTest {
        login()
        downloaded()
        server.enqueue(SessionFixtures.error(403, "permission_required"))
        assertEquals(ReminderRefresh.PermissionRequired, repository.refresh(owner))
        server.enqueue(SessionFixtures.error(503, "upstream_unavailable"))
        assertEquals(ReminderRefresh.Unavailable("8c1f2d3e-0000-4000-8000-000000000001"), repository.refresh(owner))
        respond(SessionRemindersFixtures.rulesBody())
        server.enqueue(SessionFixtures.error(504, "upstream_timeout"))
        assertTrue(repository.refresh(owner) is ReminderRefresh.Unavailable)
        assertNotNull(repository.observe(owner).first())
    }

    @Test
    fun `una configuracion que no puede descifrarse se trata como inexistente`() = runTest {
        login()
        downloaded()
        cipher.broken = true

        assertNull(repository.observe(owner).first())
    }

    @Test
    fun `una falla al guardar la descarga se informa`() = runTest {
        login()
        dao.failWrites = true
        respond(SessionRemindersFixtures.rulesBody())
        respond(SessionRemindersFixtures.preferencesBody())

        assertEquals(ReminderRefresh.StorageFailed, repository.refresh(owner))
    }

    // ----------------------------------------------------------------- guardar preferencias

    @Test
    fun `guardar envia ambas preferencias con expected_version y actualiza la configuracion`() = runTest {
        login()
        downloaded()
        respond(SessionRemindersFixtures.preferencesBody(hydrationInterval = 10, nutritionEnabled = false, nutritionInterval = 30, version = 1))

        val result = repository.save(owner, ReminderSetting(true, 10), ReminderSetting(false, 30), expectedVersion = 0)

        assertEquals(ReminderSave.Saved, result)
        val request = takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/v1/me/session-reminder-preferences", request.requestUrl?.encodedPath)
        assertEquals(
            """{"hydration":{"enabled":true,"interval_minutes":10},"nutrition":{"enabled":false,"interval_minutes":30},"expected_version":0}""",
            request.body.readUtf8(),
        )
        val config = assertNotNullValue(repository.observe(owner).first())
        assertEquals(1, config.preferences.version)
        assertEquals(ReminderSetting(false, 30), config.preferences.nutrition)
        assertEquals(SessionRemindersFixtures.RULESET, config.ruleset)
    }

    @Test
    fun `una version obsoleta recibe 409 y recarga las preferencias vigentes (CA5)`() = runTest {
        login()
        downloaded()
        respond(SessionRemindersFixtures.versionConflict(4), status = 409)
        respond(SessionRemindersFixtures.rulesBody())
        respond(SessionRemindersFixtures.preferencesBody(hydrationInterval = 20, version = 4))

        assertEquals(ReminderSave.VersionConflict, repository.save(owner, ReminderSetting(true, 10), ReminderSetting(true, 45), 0))

        assertEquals(4, assertNotNullValue(repository.observe(owner).first()).preferences.version)
        assertEquals(20, assertNotNullValue(repository.observe(owner).first()).preferences.hydration.intervalMinutes)
    }

    @Test
    fun `preferencias fuera de rango reciben 422 con el campo y sin cambios locales (CA5)`() = runTest {
        login()
        downloaded()
        respond(SessionRemindersFixtures.validationError(), status = 422)

        val result = repository.save(owner, ReminderSetting(true, 61), ReminderSetting(true, 45), 0)

        assertEquals(ReminderSave.Invalid(listOf("hydration.interval_minutes")), result)
        assertEquals(SessionRemindersFixtures.DEFAULTS, assertNotNullValue(repository.observe(owner).first()).preferences)
    }

    @Test
    fun `guardar sin red no encola ni cambia la configuracion`() = runTest {
        login()
        downloaded()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        assertEquals(ReminderSave.NoConnection, repository.save(owner, ReminderSetting(true, 10), ReminderSetting(true, 45), 0))
        assertEquals(SessionRemindersFixtures.DEFAULTS, assertNotNullValue(repository.observe(owner).first()).preferences)
    }

    @Test
    fun `guardar sin capacidad o sin permiso se informa`() = runTest {
        login()
        downloaded()
        server.enqueue(SessionFixtures.error(403, "permission_required"))
        assertEquals(ReminderSave.PermissionRequired, repository.save(owner, ReminderSetting(true, 10), ReminderSetting(true, 45), 0))
        server.enqueue(SessionFixtures.error(403, "capability_required"))
        assertEquals(ReminderSave.CapabilityRequired, repository.save(owner, ReminderSetting(true, 10), ReminderSetting(true, 45), 0))
        assertNull(repository.observe(owner).first())
    }

    @Test
    fun `sin sesion valida no llama al BFF`() = runTest {
        assertEquals(ReminderRefresh.SignedOut, repository.refresh(owner))
        assertEquals(ReminderSave.SignedOut, repository.save(owner, ReminderSetting(true, 10), ReminderSetting(true, 45), 0))
        assertEquals(0, server.requestCount)
    }

    // ----------------------------------------------------------------- registro de entregas

    @Test
    fun `una ocurrencia se registra una sola vez con delivered_at cifrado (CA2, CA3)`() = runTest {
        val at = Instant.parse("2026-10-26T13:15:00Z")
        assertTrue(repository.claim(owner, SessionRemindersFixtures.SESSION_ID, ReminderType.HYDRATION, 1, at, ReminderPresentation.NOTIFICATION_AND_BANNER))
        assertFalse(repository.claim(owner, SessionRemindersFixtures.SESSION_ID, ReminderType.HYDRATION, 1, at.plusSeconds(5), ReminderPresentation.BANNER_ONLY))
        assertTrue(repository.claim(owner, SessionRemindersFixtures.SESSION_ID, ReminderType.NUTRITION, 1, at, ReminderPresentation.BANNER_ONLY))

        val deliveries = repository.deliveries(owner, SessionRemindersFixtures.SESSION_ID)
        assertEquals(2, deliveries.size)
        val hydration = deliveries.single { it.type == ReminderType.HYDRATION }
        assertEquals(at, hydration.deliveredAt)
        assertEquals(ReminderPresentation.NOTIFICATION_AND_BANNER, hydration.presentation)
        assertEquals(setOf("HYDRATION:1", "NUTRITION:1"), dao.deliveries.keys.map { it.third }.toSet())
    }

    @Test
    fun `el janitor borra configuracion y registro al cerrar sesion y los de otros deportistas al entrar`() = runTest {
        val other = TrainingOwner.keyOf(TrainingFixtures.OTHER_USER_ID)
        login()
        downloaded()
        repository.claim(owner, "s1", ReminderType.HYDRATION, 1, Instant.EPOCH, ReminderPresentation.BANNER_ONLY)
        repository.claim(other, "s2", ReminderType.HYDRATION, 1, Instant.EPOCH, ReminderPresentation.BANNER_ONLY)
        val user = MutableStateFlow<SessionUser?>(SessionUser(TrainingFixtures.USER_ID, "Ana", "ana@example.com", ActorType.ATHLETE))
        val job = SessionRemindersJanitor(user, repository).start(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        assertTrue(dao.deliveries.keys.none { it.first == other })
        user.value = null
        assertNull(repository.observe(owner).first())
        assertTrue(dao.deliveries.isEmpty())
        job.cancelAndJoin()
    }

    private fun <T> assertNotNullValue(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
