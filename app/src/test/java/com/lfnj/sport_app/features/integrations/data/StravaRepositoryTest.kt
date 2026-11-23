package com.lfnj.sport_app.features.integrations.data

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.integrations.data.local.StravaCacheEntity
import com.lfnj.sport_app.features.integrations.data.local.StravaCodec
import com.lfnj.sport_app.features.integrations.model.StravaLinkStatus
import com.lfnj.sport_app.features.integrations.model.StravaSyncError
import com.lfnj.sport_app.features.integrations.model.StravaSyncStatus
import com.lfnj.sport_app.features.training.session.model.TrainingOwner
import com.lfnj.sport_app.testing.FakeCipher
import com.lfnj.sport_app.testing.FakeTokenStore
import com.lfnj.sport_app.testing.InMemoryStravaDao
import com.lfnj.sport_app.testing.MutableClock
import com.lfnj.sport_app.testing.SessionFixtures
import com.lfnj.sport_app.testing.StravaFixtures
import com.lfnj.sport_app.testing.TrainingFixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/**
 * HU031 contra un `bff-mobile` simulado con MockWebServer (contrato 0.22.0) y la caché en memoria con
 * cifrado de prueba. Contrato simulado según ADR-042: **no** es un E2E ni usa Strava real.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StravaRepositoryTest {
    private val server = MockWebServer().apply { start() }
    private val retrofit = BffMobileClient.retrofit(
        server.url("/").toString(),
        BffMobileClient.okHttp().newBuilder().retryOnConnectionFailure(false).build(),
    )
    private val sessionManager = SessionManager(retrofit.create(SessionApi::class.java), FakeTokenStore(), BffMobileClient.json, clock = { 1_000_000L })
    private val dao = InMemoryStravaDao()
    private val cipher = FakeCipher()
    private val clock = MutableClock(Instant.parse("2026-11-15T15:00:00Z"), ZoneOffset.UTC)
    private val repository = StravaRepository(
        dao, StravaCodec(cipher, BffMobileClient.json), retrofit.create(StravaApi::class.java), sessionManager, BffMobileClient.json, clock,
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

    private fun disconnect() = server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

    private fun assertBearer(request: RecordedRequest) = assertEquals("Bearer ${SessionFixtures.ACCESS_TOKEN}", request.getHeader("Authorization"))

    // ----------------------------------------------------------------- estado

    @Test
    fun `consulta el estado con el token y guarda solo lo confirmado, sin el atleta de Strava`() = runTest {
        login()
        respond(StravaFixtures.integrationBody())

        val outcome = repository.refresh(owner)

        val request = takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/me/integrations/strava", request.requestUrl?.encodedPath)
        assertBearer(request)
        val integration = (outcome as StravaOutcome.Done).value
        assertEquals(StravaLinkStatus.ACTIVE, integration.status)
        assertEquals(Instant.parse("2026-11-10T12:00:00Z"), integration.linkedAt)
        assertEquals(listOf("read", "activity:read"), integration.scopes)
        assertEquals(StravaSyncStatus.SUCCEEDED, integration.lastSync?.status)
        assertEquals(3L, integration.lastSync?.importedCount)
        assertEquals(clock.instant, integration.syncedAt)
        assertEquals(integration, repository.observe(owner).first())
        val stored = cipher.decrypt(dao.rows.value.getValue(owner).payload).toString(Charsets.UTF_8)
        assertFalse(stored, "98765" in stored)
    }

    @Test
    fun `NOT_LINKED sin corridas y una corrida fallida con su error_code se conservan`() = runTest {
        login()
        respond(StravaFixtures.notLinkedBody())
        val notLinked = (repository.refresh(owner) as StravaOutcome.Done).value
        assertEquals(StravaLinkStatus.NOT_LINKED, notLinked.status)
        assertNull(notLinked.linkedAt)
        assertNull(notLinked.lastSync)

        respond(StravaFixtures.integrationBody(lastSync = StravaFixtures.lastSync("RATE_LIMITED", importedCount = 0, errorCode = "rate_limited")))
        val limited = (repository.refresh(owner) as StravaOutcome.Done).value.lastSync
        assertEquals(StravaSyncStatus.RATE_LIMITED, limited?.status)
        assertEquals(StravaSyncError.RATE_LIMITED, limited?.error)
    }

    @Test
    fun `una respuesta fuera del contrato no reemplaza la cache`() = runTest {
        login()
        respond(StravaFixtures.integrationBody())
        repository.refresh(owner)
        val before = dao.rows.value

        respond(StravaFixtures.integrationBody(status = "PENDING"))
        assertEquals(StravaOutcome.Failed(StravaFailure.INVALID_RESPONSE), repository.refresh(owner))
        respond(StravaFixtures.integrationBody(linkedAt = "ayer"))
        assertEquals(StravaOutcome.Failed(StravaFailure.INVALID_RESPONSE), repository.refresh(owner))
        respond(StravaFixtures.integrationBody(lastSync = StravaFixtures.lastSync(errorCode = "otro")))
        assertEquals(StravaOutcome.Failed(StravaFailure.INVALID_RESPONSE), repository.refresh(owner))
        respond(StravaFixtures.integrationBody(lastSync = StravaFixtures.lastSync(importedCount = -1)))
        assertEquals(StravaOutcome.Failed(StravaFailure.INVALID_RESPONSE), repository.refresh(owner))
        respond("""{"status":"ACTIVE"}""")
        assertEquals(StravaOutcome.Failed(StravaFailure.INVALID_RESPONSE), repository.refresh(owner))

        assertEquals(before, dao.rows.value)
    }

    @Test
    fun `errores documentados del estado se traducen sin datos`() = runTest {
        login()
        server.enqueue(SessionFixtures.error(403, "capability_required"))
        assertEquals(StravaOutcome.Failed(StravaFailure.CAPABILITY_REQUIRED), repository.refresh(owner))
        server.enqueue(SessionFixtures.error(403, "permission_required"))
        assertEquals(StravaOutcome.Failed(StravaFailure.PERMISSION_REQUIRED), repository.refresh(owner))
        server.enqueue(SessionFixtures.error(503, "upstream_unavailable"))
        assertEquals(StravaOutcome.Failed(StravaFailure.UNAVAILABLE), repository.refresh(owner))
        server.enqueue(SessionFixtures.error(504, "upstream_timeout"))
        assertEquals(StravaOutcome.Failed(StravaFailure.UNAVAILABLE), repository.refresh(owner))
        disconnect()
        assertEquals(StravaOutcome.Failed(StravaFailure.NO_CONNECTION), repository.refresh(owner))
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun `si el estado no puede guardarse se informa`() = runTest {
        login()
        dao.failWrites = true
        respond(StravaFixtures.integrationBody())

        assertEquals(StravaOutcome.Failed(StravaFailure.STORAGE), repository.refresh(owner))
    }

    @Test
    fun `sin sesion no se llama al BFF y se pide volver a iniciar sesion`() = runTest {
        assertEquals(StravaOutcome.Failed(StravaFailure.SIGNED_OUT), repository.refresh(owner))
        assertEquals(StravaOutcome.Failed(StravaFailure.SIGNED_OUT), repository.startAuthorization())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `sin URL del BFF ninguna operacion se intenta`() = runTest {
        val offline = StravaRepository(dao, StravaCodec(cipher, BffMobileClient.json), null, sessionManager, BffMobileClient.json, clock)

        assertEquals(StravaOutcome.Failed(StravaFailure.UNAVAILABLE), offline.refresh(owner))
        assertEquals(StravaOutcome.Failed(StravaFailure.UNAVAILABLE), offline.startAuthorization())
        assertEquals(StravaOutcome.Failed(StravaFailure.UNAVAILABLE), offline.requestSync("clave"))
        assertEquals(StravaOutcome.Failed(StravaFailure.UNAVAILABLE), offline.unlink())
    }

    // ----------------------------------------------------------------- vinculación (CA1)

    @Test
    fun `pide la URL del consentimiento oficial y la entrega sin guardarla (CA1)`() = runTest {
        login()
        respond(StravaFixtures.authorizationBody(), status = 201)

        assertEquals(StravaOutcome.Done(StravaFixtures.AUTHORIZATION_URL), repository.startAuthorization())

        val request = takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/me/integrations/strava/authorization", request.requestUrl?.encodedPath)
        assertBearer(request)
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun `solo se acepta una URL https absoluta con vencimiento valido`() = runTest {
        login()
        listOf(
            StravaFixtures.authorizationBody(url = "http://www.strava.com/oauth/mobile/authorize?state=x"),
            StravaFixtures.authorizationBody(url = "intent://strava#Intent;end"),
            StravaFixtures.authorizationBody(url = "javascript:alert(1)"),
            StravaFixtures.authorizationBody(url = "https:///sin-host"),
            StravaFixtures.authorizationBody(url = "https://www.strava.com/ con espacio"),
            StravaFixtures.authorizationBody(expiresAt = "pronto"),
        ).forEach { body ->
            respond(body, status = 201)
            assertEquals(body, StravaOutcome.Failed(StravaFailure.INVALID_RESPONSE), repository.startAuthorization())
        }
    }

    @Test
    fun `sin red o con el servicio caido no se abre nada`() = runTest {
        login()
        disconnect()
        assertEquals(StravaOutcome.Failed(StravaFailure.NO_CONNECTION), repository.startAuthorization())
        server.enqueue(SessionFixtures.error(503, "upstream_unavailable"))
        assertEquals(StravaOutcome.Failed(StravaFailure.UNAVAILABLE), repository.startAuthorization())
        server.enqueue(SessionFixtures.error(403, "capability_required"))
        assertEquals(StravaOutcome.Failed(StravaFailure.CAPABILITY_REQUIRED), repository.startAuthorization())
    }

    // ----------------------------------------------------------------- sincronización (CA4, CA6)

    @Test
    fun `solicita la sincronizacion con la clave de idempotencia y acepta el 202 (CA4)`() = runTest {
        login()
        server.enqueue(MockResponse().setResponseCode(202).setBody(StravaFixtures.syncBody()))

        val outcome = repository.requestSync("clave-intento-1")

        val request = takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/me/integrations/strava/syncs", request.requestUrl?.encodedPath)
        assertEquals("clave-intento-1", request.getHeader("Idempotency-Key"))
        assertBearer(request)
        assertEquals(StravaOutcome.Done(StravaSyncAcceptance.Accepted(StravaSyncStatus.QUEUED)), outcome)
    }

    @Test
    fun `la corrida original repetida con la misma clave se acepta con su estado actual`() = runTest {
        login()
        server.enqueue(MockResponse().setResponseCode(202).setHeader("Idempotent-Replayed", "true").setBody(StravaFixtures.syncBody("SUCCEEDED")))

        assertEquals(StravaOutcome.Done(StravaSyncAcceptance.Accepted(StravaSyncStatus.SUCCEEDED)), repository.requestSync("clave"))
    }

    @Test
    fun `los 409 documentados se distinguen (CA4)`() = runTest {
        login()
        server.enqueue(SessionFixtures.error(409, "sync_in_progress"))
        assertEquals(StravaOutcome.Done(StravaSyncAcceptance.InProgress), repository.requestSync("a"))
        server.enqueue(SessionFixtures.error(409, "integration_not_linked"))
        assertEquals(StravaOutcome.Done(StravaSyncAcceptance.NotLinked), repository.requestSync("b"))
        server.enqueue(SessionFixtures.error(409, "otro_conflicto"))
        assertEquals(StravaOutcome.Failed(StravaFailure.INVALID_RESPONSE), repository.requestSync("c"))
    }

    @Test
    fun `fallos de la solicitud de sincronizacion`() = runTest {
        login()
        server.enqueue(SessionFixtures.error(422, "validation_error"))
        assertEquals(StravaOutcome.Failed(StravaFailure.INVALID_RESPONSE), repository.requestSync("a"))
        server.enqueue(MockResponse().setResponseCode(202).setBody(StravaFixtures.syncBody("DESCONOCIDO")))
        assertEquals(StravaOutcome.Failed(StravaFailure.INVALID_RESPONSE), repository.requestSync("b"))
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"sync_run_id":"","status":"QUEUED"}"""))
        assertEquals(StravaOutcome.Failed(StravaFailure.INVALID_RESPONSE), repository.requestSync("c"))
        server.enqueue(SessionFixtures.error(504, "upstream_timeout"))
        assertEquals(StravaOutcome.Failed(StravaFailure.UNAVAILABLE), repository.requestSync("d"))
        server.enqueue(SessionFixtures.error(403, "permission_required"))
        assertEquals(StravaOutcome.Failed(StravaFailure.PERMISSION_REQUIRED), repository.requestSync("e"))
        disconnect()
        assertEquals(StravaOutcome.Failed(StravaFailure.NO_CONNECTION), repository.requestSync("f"))
    }

    // ----------------------------------------------------------------- desvinculación (CA9)

    @Test
    fun `desvincula con DELETE y acepta el 204 (CA9)`() = runTest {
        login()
        server.enqueue(MockResponse().setResponseCode(204))

        assertEquals(StravaOutcome.Done(Unit), repository.unlink())

        val request = takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/v1/me/integrations/strava", request.requestUrl?.encodedPath)
        assertBearer(request)
    }

    @Test
    fun `una desvinculacion no confirmada se informa`() = runTest {
        login()
        server.enqueue(SessionFixtures.error(503, "upstream_unavailable"))
        assertEquals(StravaOutcome.Failed(StravaFailure.UNAVAILABLE), repository.unlink())
        disconnect()
        assertEquals(StravaOutcome.Failed(StravaFailure.NO_CONNECTION), repository.unlink())
    }

    // ----------------------------------------------------------------- limpieza (ADR-026 §9)

    @Test
    fun `al cerrar sesion se borra el estado del deportista y al entrar otro se borra el ajeno`() = runTest {
        val other = TrainingOwner.keyOf("otro-deportista")
        dao.replace(StravaCacheEntity(owner, byteArrayOf(1)))
        dao.replace(StravaCacheEntity(other, byteArrayOf(2)))
        val user = MutableStateFlow<SessionUser?>(null)
        val job = StravaCacheJanitor(user, repository).start(TestScope(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle()
        assertEquals(setOf(owner, other), dao.rows.value.keys)

        user.value = SessionUser(TrainingFixtures.USER_ID, "Ana", "ana@example.com", ActorType.ATHLETE)
        advanceUntilIdle()
        assertEquals(setOf(owner), dao.rows.value.keys)

        user.value = null
        advanceUntilIdle()
        assertTrue(dao.rows.value.isEmpty())
        job.cancelAndJoin()
    }
}
