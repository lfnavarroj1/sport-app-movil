package com.lfnj.sport_app.core.session

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.testing.FakeTokenStore
import com.lfnj.sport_app.testing.SessionFixtures
import com.lfnj.sport_app.testing.SessionFixtures.ACCESS_TOKEN
import com.lfnj.sport_app.testing.SessionFixtures.REFRESH_TOKEN
import com.lfnj.sport_app.testing.SessionFixtures.ROTATED_REFRESH_TOKEN
import com.lfnj.sport_app.testing.SessionFixtures.capabilitiesBody
import com.lfnj.sport_app.testing.SessionFixtures.error
import com.lfnj.sport_app.testing.SessionFixtures.ok
import com.lfnj.sport_app.testing.SessionFixtures.sessionBody
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SessionManagerTest {
    private val server = MockWebServer().apply { start() }
    private val store = FakeTokenStore()
    private var now = 1_000_000L
    private val manager = SessionManager(
        api = BffMobileClient.retrofit(server.url("/").toString()).create(SessionApi::class.java),
        store = store,
        json = BffMobileClient.json,
        clock = { now },
    )

    @After
    fun tearDown() = server.shutdown()

    private suspend fun loginAthlete() {
        server.enqueue(ok(sessionBody()))
        manager.login("ana@example.com", "Secreta-123")
        server.takeRequest()
    }

    // ------------------------------------------------------------------ inicio de sesión

    @Test
    fun `CA1 y CA6 un deportista inicia sesion y solo el refresh token se guarda de forma segura`() = runTest {
        server.enqueue(ok(sessionBody()))

        val outcome = manager.login("ana@example.com", "Secreta-123")

        assertTrue(outcome is LoginOutcome.Authenticated)
        assertEquals(ActorType.ATHLETE, (outcome as LoginOutcome.Authenticated).user.actorType)
        assertEquals(REFRESH_TOKEN, store.value)
        assertEquals(outcome.user, manager.currentUser.value)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/sessions", request.path)
        assertEquals("""{"email":"ana@example.com","password":"Secreta-123"}""", request.body.readUtf8())
    }

    @Test
    fun `CA2 y CA3 credenciales rechazadas no crean sesion ni guardan tokens`() = runTest {
        server.enqueue(error(401, "invalid_credentials"))

        assertEquals(LoginOutcome.InvalidCredentials, manager.login("ana@example.com", "mala"))
        assertNull(store.value)
        assertNull(manager.currentUser.value)
    }

    @Test
    fun `CA4 el control de abuso informa la espera de Retry-After`() = runTest {
        server.enqueue(error(429, "too_many_attempts", retryAfter = 120))

        assertEquals(LoginOutcome.TooManyAttempts(120), manager.login("ana@example.com", "x"))
        assertNull(store.value)
    }

    @Test
    fun `validacion del BFF`() = runTest {
        server.enqueue(error(422, "validation_error"))

        assertEquals(LoginOutcome.InvalidInput, manager.login("ana@example.com", "x"))
    }

    @Test
    fun `CA11 un organizador no abre sesion movil y sus tokens se descartan`() = runTest {
        store.value = "token-de-una-sesion-anterior-000000"
        server.enqueue(ok(sessionBody(actorType = "organizer")))

        assertEquals(LoginOutcome.ActorNotAllowedInChannel, manager.login("org@example.com", "Secreta-123"))
        assertNull(store.value)
        assertNull(manager.currentUser.value)
    }

    @Test
    fun `CA11 un profesional de servicios no abre sesion movil`() = runTest {
        server.enqueue(ok(sessionBody(actorType = "service_provider")))

        assertEquals(LoginOutcome.ActorNotAllowedInChannel, manager.login("pro@example.com", "Secreta-123"))
        assertNull(store.value)
        assertEquals(0, store.writes)
    }

    @Test
    fun `CA10 un actor desconocido no se convierte en deportista`() = runTest {
        server.enqueue(ok(sessionBody(actorType = "admin")))

        assertEquals(LoginOutcome.UnsupportedActor, manager.login("x@example.com", "Secreta-123"))
        assertNull(store.value)
        assertNull(manager.currentUser.value)
    }

    @Test
    fun `CA10 un actor ausente no se convierte en deportista`() = runTest {
        server.enqueue(ok(sessionBody(actorType = null)))

        assertEquals(LoginOutcome.UnsupportedActor, manager.login("x@example.com", "Secreta-123"))
        assertEquals(0, store.writes)
    }

    @Test
    fun `BFF no disponible conserva la referencia de correlacion`() = runTest {
        server.enqueue(error(503, "upstream_unavailable"))

        assertEquals(
            LoginOutcome.Unavailable("8c1f2d3e-0000-4000-8000-000000000001"),
            manager.login("ana@example.com", "x"),
        )
    }

    @Test
    fun `sin conexion`() = runTest {
        server.shutdown()

        assertEquals(LoginOutcome.NoConnection, manager.login("ana@example.com", "x"))
    }

    @Test
    fun `variante sin BFF configurado`() = runTest {
        val unconfigured = SessionManager(null, store, BffMobileClient.json)

        assertEquals(LoginOutcome.NotConfigured, unconfigured.login("ana@example.com", "x"))
        assertEquals(RestoreOutcome.NotConfigured, unconfigured.restore())
    }

    // ------------------------------------------------------------------ recuperación

    @Test
    fun `sin refresh token guardado no hay sesion que recuperar`() = runTest {
        assertEquals(RestoreOutcome.NoSession, manager.restore())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `recupera la sesion y guarda el refresh token rotado`() = runTest {
        store.value = REFRESH_TOKEN
        server.enqueue(ok(sessionBody(refreshToken = ROTATED_REFRESH_TOKEN)))

        val outcome = manager.restore()

        assertTrue(outcome is RestoreOutcome.Restored)
        assertEquals(ROTATED_REFRESH_TOKEN, store.value)
        val request = server.takeRequest()
        assertEquals("/v1/sessions/refresh", request.path)
        assertEquals("""{"refresh_token":"$REFRESH_TOKEN"}""", request.body.readUtf8())
    }

    @Test
    fun `un refresh token rechazado se elimina`() = runTest {
        store.value = REFRESH_TOKEN
        server.enqueue(error(401, "invalid_refresh_token"))

        assertEquals(RestoreOutcome.Expired, manager.restore())
        assertNull(store.value)
    }

    @Test
    fun `sin conexion se conserva el refresh token para reintentar`() = runTest {
        store.value = REFRESH_TOKEN
        server.shutdown()

        assertEquals(RestoreOutcome.NoConnection, manager.restore())
        assertEquals(REFRESH_TOKEN, store.value)
    }

    @Test
    fun `una renovacion que devuelve un actor no movil elimina la sesion`() = runTest {
        store.value = REFRESH_TOKEN
        server.enqueue(ok(sessionBody(actorType = "organizer", refreshToken = ROTATED_REFRESH_TOKEN)))

        assertEquals(RestoreOutcome.UnsupportedActor, manager.restore())
        assertNull(store.value)
    }

    // ------------------------------------------------------------------ capacidades

    @Test
    fun `CA7 carga las capacidades vigentes con el access token en memoria`() = runTest {
        loginAthlete()
        server.enqueue(ok(capabilitiesBody()))

        val outcome = manager.loadAccess() as AccessOutcome.Loaded

        assertEquals(SubscriptionStatus.ACTIVE, outcome.access.subscriptionStatus)
        assertEquals(SessionFixtures.FREE_CAPABILITIES.toSet(), outcome.access.capabilities)
        val request = server.takeRequest()
        assertEquals("/v1/me/capabilities", request.path)
        assertEquals("Bearer $ACCESS_TOKEN", request.getHeader("Authorization"))
    }

    @Test
    fun `renueva antes de consultar si el access token esta por vencer`() = runTest {
        loginAthlete()
        now += 900_000
        server.enqueue(ok(sessionBody(accessToken = "access-token-sintetico-2", refreshToken = ROTATED_REFRESH_TOKEN)))
        server.enqueue(ok(capabilitiesBody()))

        assertTrue(manager.loadAccess() is AccessOutcome.Loaded)
        assertEquals("/v1/sessions/refresh", server.takeRequest().path)
        assertEquals("Bearer access-token-sintetico-2", server.takeRequest().getHeader("Authorization"))
        assertEquals(ROTATED_REFRESH_TOKEN, store.value)
    }

    @Test
    fun `ante 401 renueva una vez y reintenta`() = runTest {
        loginAthlete()
        server.enqueue(error(401, "invalid_access_token"))
        server.enqueue(ok(sessionBody(accessToken = "access-token-sintetico-2", refreshToken = ROTATED_REFRESH_TOKEN)))
        server.enqueue(ok(capabilitiesBody()))

        assertTrue(manager.loadAccess() is AccessOutcome.Loaded)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `si la renovacion falla la sesion expira y se elimina`() = runTest {
        loginAthlete()
        server.enqueue(error(401, "invalid_access_token"))
        server.enqueue(error(401, "invalid_refresh_token"))

        assertEquals(AccessOutcome.SessionExpired, manager.loadAccess())
        assertNull(store.value)
        assertNull(manager.currentUser.value)
    }

    @Test
    fun `CA10 403 unsupported_actor_type elimina la sesion`() = runTest {
        loginAthlete()
        server.enqueue(error(403, "unsupported_actor_type"))

        assertEquals(AccessOutcome.Unauthorized, manager.loadAccess())
        assertNull(store.value)
        assertNull(manager.currentUser.value)
    }

    @Test
    fun `CA10 un actor de capacidades distinto del firmado elimina la sesion`() = runTest {
        loginAthlete()
        server.enqueue(ok(capabilitiesBody(actorType = "organizer", status = "NOT_APPLICABLE", planCode = null, capabilities = emptyList())))

        assertEquals(AccessOutcome.Unauthorized, manager.loadAccess())
        assertNull(store.value)
    }

    @Test
    fun `capacidades no disponibles conservan la sesion`() = runTest {
        loginAthlete()
        server.enqueue(error(503, "upstream_unavailable"))

        assertTrue(manager.loadAccess() is AccessOutcome.Unavailable)
        assertEquals(REFRESH_TOKEN, store.value)
    }

    @Test
    fun `las renovaciones concurrentes se serializan para no revocar la familia de tokens`() = runTest {
        loginAthlete()
        now += 900_000
        val refreshes = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/v1/sessions/refresh" -> {
                    val n = refreshes.incrementAndGet()
                    ok(sessionBody(accessToken = "access-$n", refreshToken = "refresh-token-sintetico-rotado-00000$n"))
                }
                else -> ok(capabilitiesBody())
            }
        }

        val results = List(3) { async { manager.loadAccess() } }.awaitAll()

        assertTrue(results.all { it is AccessOutcome.Loaded })
        assertEquals(1, refreshes.get())
    }

    // ------------------------------------------------------------------ seguridad

    @Test
    fun `los modelos con credenciales no exponen tokens ni contrasenas al convertirse en texto`() {
        val response = BffMobileClient.json.decodeFromString(SessionResponseDto.serializer(), sessionBody())
        val texts = listOf(
            response.toString(),
            LoginRequestDto("ana@example.com", "Secreta-123").toString(),
            RefreshSessionRequestDto(REFRESH_TOKEN).toString(),
            AuthSession(SessionUser("u", "Ana", "ana@example.com", ActorType.ATHLETE), ACCESS_TOKEN, 0, REFRESH_TOKEN).toString(),
        )
        texts.forEach { text ->
            listOf(ACCESS_TOKEN, REFRESH_TOKEN, "Secreta-123", "ana@example.com").forEach { secret ->
                assertTrue("«$text» expone un dato sensible", secret !in text)
            }
        }
    }
}
