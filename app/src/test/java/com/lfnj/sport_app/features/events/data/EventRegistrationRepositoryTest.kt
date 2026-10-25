package com.lfnj.sport_app.features.events.data

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.features.events.data.local.EventRegistrationsCacheEntity
import com.lfnj.sport_app.features.events.data.local.EventRegistrationsCodec
import com.lfnj.sport_app.features.events.model.EventPrice
import com.lfnj.sport_app.features.events.model.EventRegistrationsOwner
import com.lfnj.sport_app.testing.EventRegistrationFixtures
import com.lfnj.sport_app.testing.EventRegistrationFixtures.EVENT_ID
import com.lfnj.sport_app.testing.EventRegistrationFixtures.OTHER_REGISTRATION_ID
import com.lfnj.sport_app.testing.EventRegistrationFixtures.REGISTRATION_ID
import com.lfnj.sport_app.testing.FakeCipher
import com.lfnj.sport_app.testing.FakeTokenStore
import com.lfnj.sport_app.testing.InMemoryEventRegistrationsDao
import com.lfnj.sport_app.testing.MutableClock
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
 * HU041 contra un `bff-mobile` simulado con MockWebServer y la caché en memoria (cifrado de
 * prueba). Contrato simulado según ADR-032: **no** es un E2E.
 */
class EventRegistrationRepositoryTest {
    private val server = MockWebServer().apply { start() }
    private val retrofit = BffMobileClient.retrofit(
        server.url("/").toString(),
        BffMobileClient.okHttp().newBuilder().retryOnConnectionFailure(false).build(),
    )
    private val sessionManager = SessionManager(retrofit.create(SessionApi::class.java), FakeTokenStore(), BffMobileClient.json, clock = { 1_000_000L })
    private val dao = InMemoryEventRegistrationsDao()
    private val cipher = FakeCipher()
    private val clock = MutableClock(Instant.parse("2026-11-10T15:00:00Z"), ZoneId.of("America/Bogota"))
    private val delays = mutableListOf<Int>()
    private val repository = EventRegistrationRepository(
        dao, EventRegistrationsCodec(cipher, BffMobileClient.json), retrofit.create(EventRegistrationApi::class.java),
        sessionManager, BffMobileClient.json, clock, retryDelay = { delays += it },
    )
    private val owner = EventRegistrationsOwner.keyOf(TrainingFixtures.USER_ID)
    private val key = "1f0e2d3c-4b5a-4968-8776-a5b4c3d2e1f0"

    @After
    fun tearDown() = server.shutdown()

    private suspend fun login() {
        server.enqueue(SessionFixtures.ok(SessionFixtures.sessionBody()))
        sessionManager.login("ana@example.com", "Secreta-123")
        server.takeRequest()
    }

    private fun takeRequest(): RecordedRequest = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS)) { "No llegó la solicitud esperada" }

    private fun respond(body: String, status: Int = 200) = server.enqueue(MockResponse().setResponseCode(status).setBody(body))

    // ----------------------------------------------------------------- detalle (CA2)

    @Test
    fun `consulta el detalle publicado con el token y conserva tarifa y mi inscripcion`() = runTest {
        login()
        respond(
            EventRegistrationFixtures.detailBody(
                fee = """{"amount":"85000.00","currency":"COP"}""",
                myRegistration = EventRegistrationFixtures.myRegistration(),
            )
        )

        val result = repository.detail(EVENT_ID) as EventDetailResult.Loaded

        val request = takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/events/$EVENT_ID", request.requestUrl?.encodedPath)
        assertEquals("Bearer ${SessionFixtures.ACCESS_TOKEN}", request.getHeader("Authorization"))
        assertEquals(EventPrice("85000.00", "COP"), result.detail.fee)
        assertEquals(REGISTRATION_ID, result.detail.myRegistration?.registrationId)
        assertEquals(Instant.parse("2026-11-15T12:00:00Z"), result.detail.startsAt)
        assertEquals("America/Bogota", result.detail.timeZone)
        assertEquals(500, result.detail.capacity)
    }

    @Test
    fun `sin tarifa ni inscripcion ambos campos quedan vacios`() = runTest {
        login()
        respond(EventRegistrationFixtures.detailBody())

        val detail = (repository.detail(EVENT_ID) as EventDetailResult.Loaded).detail

        assertNull(detail.fee)
        assertNull(detail.myRegistration)
    }

    @Test
    fun `404 event_not_found no revela un borrador`() = runTest {
        login()
        server.enqueue(EventRegistrationFixtures.error(404, "event_not_found"))

        assertEquals(EventDetailResult.NotFound, repository.detail(EVENT_ID))
    }

    @Test
    fun `un estado distinto de PUBLISHED se rechaza como respuesta invalida`() = runTest {
        login()
        respond(EventRegistrationFixtures.detailBody(status = "DRAFT"))

        assertEquals(EventDetailResult.InvalidResponse, repository.detail(EVENT_ID))
    }

    @Test
    fun `un identificador que no es UUID no se envia al BFF`() = runTest {
        assertEquals(EventDetailResult.NotFound, repository.detail("../v1/me"))
        assertEquals(RegisterResult.NotFound, repository.register("no-es-uuid", key))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `detalle 403 503 y sin red`() = runTest {
        login()
        server.enqueue(EventRegistrationFixtures.error(403, "permission_required"))
        assertEquals(EventDetailResult.PermissionRequired, repository.detail(EVENT_ID))

        server.enqueue(EventRegistrationFixtures.error(503, "upstream_unavailable"))
        assertEquals(EventDetailResult.Unavailable("8c1f2d3e-0000-4000-8000-000000000041"), repository.detail(EVENT_ID))

        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals(EventDetailResult.NoConnection, repository.detail(EVENT_ID))
    }

    @Test
    fun `sin sesion el detalle exige iniciar sesion`() = runTest {
        assertEquals(EventDetailResult.SignedOut, repository.detail(EVENT_ID))
    }

    // ----------------------------------------------------------------- inscripción (CA1, CA3–CA6)

    @Test
    fun `201 inscribe sin cuerpo con la clave de idempotencia y la identidad del token`() = runTest {
        login()
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setHeader("Location", "/v1/me/event-registrations/$REGISTRATION_ID")
                .setBody(EventRegistrationFixtures.registration())
        )

        val result = repository.register(EVENT_ID, key) as RegisterResult.Registered

        val request = takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/events/$EVENT_ID/registrations", request.requestUrl?.encodedPath)
        assertEquals(key, request.getHeader("Idempotency-Key"))
        assertEquals("Bearer ${SessionFixtures.ACCESS_TOKEN}", request.getHeader("Authorization"))
        assertEquals(0L, request.bodySize)
        assertFalse(request.body.readUtf8().contains("athlete_id"))
        assertEquals(REGISTRATION_ID, result.registration.registrationId)
        assertEquals(1, result.registration.version)
        assertNull(result.registration.appliedFee)
        assertTrue(delays.isEmpty())
    }

    @Test
    fun `los reintentos automaticos ante 503 y 504 conservan la misma clave`() = runTest {
        login()
        server.enqueue(EventRegistrationFixtures.error(503, "upstream_unavailable"))
        server.enqueue(EventRegistrationFixtures.error(504, "upstream_timeout"))
        server.enqueue(MockResponse().setResponseCode(201).setBody(EventRegistrationFixtures.registration()))

        assertTrue(repository.register(EVENT_ID, key) is RegisterResult.Registered)

        val keys = List(3) { takeRequest().getHeader("Idempotency-Key") }
        assertEquals(List(3) { key }, keys)
        assertEquals(listOf(1, 2), delays)
    }

    @Test
    fun `un 502 y la falta de red tambien se reintentan con la misma clave`() = runTest {
        login()
        server.enqueue(MockResponse().setResponseCode(502))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setResponseCode(201).setBody(EventRegistrationFixtures.registration()))

        assertTrue(repository.register(EVENT_ID, key) is RegisterResult.Registered)
        assertEquals(List(3) { key }, List(3) { takeRequest().getHeader("Idempotency-Key") })
    }

    @Test
    fun `tras tres reintentos fallidos informa no disponible sin presentarlo como inscrito`() = runTest {
        login()
        repeat(4) { server.enqueue(EventRegistrationFixtures.error(503, "upstream_unavailable")) }

        val result = repository.register(EVENT_ID, key)

        assertTrue(result is RegisterResult.Unavailable)
        assertEquals(1 + EventDetailGateway.MAX_AUTOMATIC_RETRIES, server.requestCount - 1)
        assertEquals(listOf(1, 2, 3), delays)
    }

    @Test
    fun `sin red tras los reintentos informa sin conexion`() = runTest {
        login()
        repeat(4) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) }

        assertEquals(RegisterResult.NoConnection, repository.register(EVENT_ID, key))
        assertEquals(3, delays.size)
    }

    @Test
    fun `event_registration_exists devuelve la inscripcion existente sin reintentar`() = runTest {
        login()
        server.enqueue(EventRegistrationFixtures.exists(OTHER_REGISTRATION_ID))

        assertEquals(RegisterResult.AlreadyRegistered(OTHER_REGISTRATION_ID), repository.register(EVENT_ID, key))
        assertEquals(2, server.requestCount)
        assertTrue(delays.isEmpty())
    }

    @Test
    fun `los demas 409 tienen su propio resultado y nunca se repiten`() = runTest {
        login()
        server.enqueue(EventRegistrationFixtures.error(409, "event_full"))
        assertEquals(RegisterResult.Full, repository.register(EVENT_ID, key))
        server.enqueue(EventRegistrationFixtures.error(409, "event_registration_closed"))
        assertEquals(RegisterResult.Closed, repository.register(EVENT_ID, key))
        server.enqueue(EventRegistrationFixtures.error(409, "idempotency_key_reused"))
        assertEquals(RegisterResult.KeyReused, repository.register(EVENT_ID, key))

        assertEquals(4, server.requestCount)
        assertTrue(delays.isEmpty())
    }

    @Test
    fun `404 403 y 422 del comando`() = runTest {
        login()
        server.enqueue(EventRegistrationFixtures.error(404, "event_not_found"))
        assertEquals(RegisterResult.NotFound, repository.register(EVENT_ID, key))
        server.enqueue(EventRegistrationFixtures.error(403, "permission_required"))
        assertEquals(RegisterResult.PermissionRequired, repository.register(EVENT_ID, key))
        server.enqueue(
            EventRegistrationFixtures.error(422, "validation_error", """[{"field":"Idempotency-Key","issue":"invalid_uuid"}]""")
        )
        assertEquals(RegisterResult.Invalid(listOf("invalid_uuid")), repository.register(EVENT_ID, key))
        assertTrue(delays.isEmpty())
    }

    @Test
    fun `una respuesta 201 que no es REGISTERED no se muestra como inscrita`() = runTest {
        login()
        server.enqueue(MockResponse().setResponseCode(201).setBody(EventRegistrationFixtures.registration().replace("REGISTERED", "PENDING")))

        assertEquals(RegisterResult.InvalidResponse, repository.register(EVENT_ID, key))
    }

    // ----------------------------------------------------------------- mis inscripciones (CA8, CA10)

    @Test
    fun `consulta la primera pagina y guarda la lista confirmada cifrada con su sincronizacion`() = runTest {
        login()
        respond(EventRegistrationFixtures.page(listOf(EventRegistrationFixtures.registration()), nextCursor = "c2"))

        assertEquals(RegistrationsRefresh.Updated, repository.refresh(owner))

        val request = takeRequest()
        assertEquals("/v1/me/event-registrations", request.requestUrl?.encodedPath)
        assertEquals("20", request.requestUrl?.queryParameter("limit"))
        assertNull(request.requestUrl?.queryParameter("cursor"))
        assertNull(request.requestUrl?.queryParameter("athlete_id"))
        val cached = checkNotNull(repository.observe(owner).first())
        assertEquals(listOf(REGISTRATION_ID), cached.items.map { it.registrationId })
        assertEquals("c2", cached.nextCursor)
        assertEquals(clock.instant, cached.syncedAt)
        val stored = dao.rows.value.getValue(owner).payload.toString(Charsets.ISO_8859_1)
        assertFalse("la caché no guarda texto en claro", stored.contains("Carrera"))
    }

    @Test
    fun `la pagina siguiente usa el cursor guardado y se agrega sin repetir`() = runTest {
        login()
        respond(EventRegistrationFixtures.page(listOf(EventRegistrationFixtures.registration()), nextCursor = "c2"))
        repository.refresh(owner)
        takeRequest()
        respond(
            EventRegistrationFixtures.page(
                listOf(
                    EventRegistrationFixtures.registration(),
                    EventRegistrationFixtures.registration(OTHER_REGISTRATION_ID, startsAt = "2026-12-01T12:00:00Z"),
                )
            )
        )

        assertEquals(RegistrationsRefresh.Updated, repository.loadMore(owner))

        assertEquals("c2", takeRequest().requestUrl?.queryParameter("cursor"))
        val cached = checkNotNull(repository.observe(owner).first())
        assertEquals(listOf(REGISTRATION_ID, OTHER_REGISTRATION_ID), cached.items.map { it.registrationId })
        assertNull(cached.nextCursor)
    }

    @Test
    fun `sin pagina siguiente no se consulta de nuevo`() = runTest {
        login()
        respond(EventRegistrationFixtures.page(emptyList()))
        repository.refresh(owner)
        val before = server.requestCount

        assertEquals(RegistrationsRefresh.Updated, repository.loadMore(owner))
        assertEquals(before, server.requestCount)
    }

    @Test
    fun `un cursor rechazado se reemplaza consultando desde el principio`() = runTest {
        login()
        respond(EventRegistrationFixtures.page(listOf(EventRegistrationFixtures.registration()), nextCursor = "viejo"))
        repository.refresh(owner)
        takeRequest()
        server.enqueue(EventRegistrationFixtures.error(422, "validation_error", """[{"field":"cursor","issue":"invalid_cursor"}]"""))
        respond(EventRegistrationFixtures.page(listOf(EventRegistrationFixtures.registration())))

        assertEquals(RegistrationsRefresh.Updated, repository.loadMore(owner))

        assertEquals("viejo", takeRequest().requestUrl?.queryParameter("cursor"))
        assertNull(takeRequest().requestUrl?.queryParameter("cursor"))
        assertNull(checkNotNull(repository.observe(owner).first()).nextCursor)
    }

    @Test
    fun `sin inscripciones se guarda una coleccion vacia confirmada`() = runTest {
        login()
        respond(EventRegistrationFixtures.page(emptyList()))

        assertEquals(RegistrationsRefresh.Updated, repository.refresh(owner))
        assertTrue(checkNotNull(repository.observe(owner).first()).items.isEmpty())
    }

    @Test
    fun `sin red conserva la ultima lista confirmada`() = runTest {
        login()
        respond(EventRegistrationFixtures.page(listOf(EventRegistrationFixtures.registration())))
        repository.refresh(owner)
        val syncedAt = clock.instant
        clock.instant = clock.instant.plusSeconds(3600)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        assertEquals(RegistrationsRefresh.NoConnection, repository.refresh(owner))

        val cached = checkNotNull(repository.observe(owner).first())
        assertEquals(syncedAt, cached.syncedAt)
        assertEquals(1, cached.items.size)
    }

    @Test
    fun `errores de la lista`() = runTest {
        login()
        server.enqueue(EventRegistrationFixtures.error(403, "permission_required"))
        assertEquals(RegistrationsRefresh.PermissionRequired, repository.refresh(owner))
        server.enqueue(EventRegistrationFixtures.error(504, "upstream_timeout"))
        assertTrue(repository.refresh(owner) is RegistrationsRefresh.Unavailable)
        respond("""{"items":[{"registration_id":"x"}],"next_cursor":null}""")
        assertEquals(RegistrationsRefresh.InvalidResponse, repository.refresh(owner))
        assertNull(repository.observe(owner).first())
    }

    @Test
    fun `una lista que no puede guardarse se informa`() = runTest {
        login()
        dao.failWrites = true
        respond(EventRegistrationFixtures.page(emptyList()))

        assertEquals(RegistrationsRefresh.StorageFailed, repository.refresh(owner))
    }

    @Test
    fun `una cache que no puede descifrarse se trata como inexistente`() = runTest {
        dao.replace(EventRegistrationsCacheEntity(owner, byteArrayOf(1, 2, 3)))

        assertNull(repository.observe(owner).first())
    }

    @Test
    fun `cada deportista ve solo su particion`() = runTest {
        login()
        respond(EventRegistrationFixtures.page(listOf(EventRegistrationFixtures.registration())))
        repository.refresh(owner)

        assertNull(repository.observe(EventRegistrationsOwner.keyOf(TrainingFixtures.OTHER_USER_ID)).first())
    }

    // ----------------------------------------------------------------- limpieza al cerrar sesión

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `el janitor elimina la cache al cerrar sesion y la de otros deportistas al entrar`() = runTest {
        val other = EventRegistrationsOwner.keyOf(TrainingFixtures.OTHER_USER_ID)
        dao.replace(EventRegistrationsCacheEntity(owner, byteArrayOf(1)))
        dao.replace(EventRegistrationsCacheEntity(other, byteArrayOf(2)))
        val user = MutableStateFlow<SessionUser?>(SessionUser(TrainingFixtures.USER_ID, "Ana", "ana@example.com", ActorType.ATHLETE))
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val job = EventRegistrationsCacheJanitor(user, repository).start(scope)

        assertEquals(setOf(owner), dao.rows.value.keys)
        user.value = null
        assertTrue(dao.rows.value.isEmpty())
        job.cancelAndJoin()
    }
}
