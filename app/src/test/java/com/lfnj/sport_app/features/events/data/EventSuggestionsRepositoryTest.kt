package com.lfnj.sport_app.features.events.data

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.events.data.local.EventSuggestionsCacheEntity
import com.lfnj.sport_app.features.events.data.local.EventSuggestionsCodec
import com.lfnj.sport_app.features.events.model.EventMissingContext
import com.lfnj.sport_app.features.events.model.EventPlace
import com.lfnj.sport_app.features.events.model.EventPrice
import com.lfnj.sport_app.features.events.model.EventSuggestionReason
import com.lfnj.sport_app.features.events.model.EventSuggestionsOwner
import com.lfnj.sport_app.features.events.model.MyRegistration
import com.lfnj.sport_app.features.recommendations.model.ContextSourceStatus
import com.lfnj.sport_app.testing.EventSuggestionsFixtures
import com.lfnj.sport_app.testing.FakeCipher
import com.lfnj.sport_app.testing.FakeTokenStore
import com.lfnj.sport_app.testing.InMemoryEventSuggestionsDao
import com.lfnj.sport_app.testing.MutableClock
import com.lfnj.sport_app.testing.SessionFixtures
import com.lfnj.sport_app.testing.TrainingFixtures
import com.lfnj.sport_app.core.session.ActorType
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
 * HU027 contra un `bff-mobile` simulado con MockWebServer y la caché en memoria (cifrado de
 * prueba). Contrato simulado según `bff-mobile` 0.15.0: **no** es un E2E.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventSuggestionsRepositoryTest {
    private val server = MockWebServer().apply { start() }
    private val retrofit = BffMobileClient.retrofit(
        server.url("/").toString(),
        BffMobileClient.okHttp().newBuilder().retryOnConnectionFailure(false).build(),
    )
    private val sessionManager = SessionManager(retrofit.create(SessionApi::class.java), FakeTokenStore(), BffMobileClient.json, clock = { 1_000_000L })
    private val dao = InMemoryEventSuggestionsDao()
    private val cipher = FakeCipher()
    private val clock = MutableClock(Instant.parse("2026-10-25T15:00:00Z"), ZoneId.of("America/Bogota"))
    private val repository = EventSuggestionsRepository(
        dao, EventSuggestionsCodec(cipher, BffMobileClient.json), retrofit.create(EventSuggestionsApi::class.java),
        sessionManager, BffMobileClient.json, clock,
    )
    private val owner = EventSuggestionsOwner.keyOf(TrainingFixtures.USER_ID)

    @After
    fun tearDown() = server.shutdown()

    private suspend fun login() {
        server.enqueue(SessionFixtures.ok(SessionFixtures.sessionBody()))
        sessionManager.login("ana@example.com", "Secreta-123")
        server.takeRequest()
    }

    private fun takeRequest(): RecordedRequest = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS)) { "No llegó la solicitud esperada" }

    private fun respond(body: String, status: Int = 200) = server.enqueue(MockResponse().setResponseCode(status).setBody(body))

    private fun uncached() = EventSuggestionsRepository(dao, EventSuggestionsCodec(cipher, BffMobileClient.json), null, sessionManager, BffMobileClient.json, clock)

    // ----------------------------------------------------------------- consulta (CA1)

    @Test
    fun `consulta con el token y el limite y guarda la lista en el orden del BFF (CA1)`() = runTest {
        login()
        respond(EventSuggestionsFixtures.body())

        assertEquals(EventSuggestionsRefresh.Updated, repository.refresh(owner))

        val request = takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/me/suggestions/events", request.requestUrl?.encodedPath)
        assertEquals("20", request.requestUrl?.queryParameter("limit"))
        assertEquals("Bearer ${SessionFixtures.ACCESS_TOKEN}", request.getHeader("Authorization"))

        val cached = checkNotNull(repository.observe(owner).first())
        assertTrue(cached.personalized)
        assertEquals(emptyList<String>(), cached.missingContext)
        assertEquals(ContextSourceStatus.OK, cached.usersSource)
        assertEquals(Instant.parse("2026-10-25T13:00:00Z"), cached.generatedAt)
        assertEquals(clock.instant, cached.syncedAt)
        // El cliente no reordena: deporte y ciudad primero, aunque el inscrito empiece antes.
        assertEquals(
            listOf(EventSuggestionsFixtures.MATCH_EVENT_ID, EventSuggestionsFixtures.SPORT_EVENT_ID, EventSuggestionsFixtures.REGISTERED_EVENT_ID),
            cached.items.map { it.eventId },
        )
        assertEquals(listOf(EventSuggestionReason.SPORT_MATCH, EventSuggestionReason.CITY_MATCH), cached.items[0].knownReasons)
        assertEquals(listOf(EventSuggestionReason.SPORT_MATCH), cached.items[1].knownReasons)
    }

    @Test
    fun `cada evento conserva fechas, zona, lugar, cupo declarado y tarifa informativa (CA4)`() = runTest {
        login()
        respond(EventSuggestionsFixtures.body())
        repository.refresh(owner)

        val items = checkNotNull(repository.observe(owner).first()).items
        val item = items[0]
        assertEquals("Media maratón de Bogotá", item.title)
        assertEquals("Recorrido urbano de 21 km.", item.summary)
        assertEquals("Atletismo", item.discipline)
        assertEquals(EventPlace("Parque Simón Bolívar", "Bogotá", "CO"), item.location)
        assertEquals(Instant.parse("2026-11-15T12:00:00Z"), item.startsAt)
        assertEquals(Instant.parse("2026-11-15T17:00:00Z"), item.endsAt)
        assertEquals("America/Bogota", item.timeZone)
        assertEquals(500, item.capacity)
        assertEquals(EventPrice("45000.00", "COP"), item.fee)
        assertFalse(item.free)
        assertNull(item.myRegistration)
        // Sin tarifa: `null`, nunca un valor inventado.
        assertNull(items[1].fee)
        assertFalse(items[1].free)
        assertTrue(items[2].free)
    }

    @Test
    fun `el evento inscrito llega marcado con my_registration (CA3)`() = runTest {
        login()
        respond(EventSuggestionsFixtures.body())
        repository.refresh(owner)

        val items = checkNotNull(repository.observe(owner).first()).items
        val registered = items.single { it.eventId == EventSuggestionsFixtures.REGISTERED_EVENT_ID }
        assertTrue(registered.registered)
        assertEquals(MyRegistration(EventSuggestionsFixtures.REGISTRATION_ID, Instant.parse("2026-10-21T10:00:00Z")), registered.myRegistration)
        assertEquals(listOf(registered), items.filter { it.registered })
    }

    @Test
    fun `sin deportes ni ciudad la lista no personalizada declara missing_context y UPCOMING (CA2)`() = runTest {
        login()
        respond(EventSuggestionsFixtures.notPersonalized())

        assertEquals(EventSuggestionsRefresh.Updated, repository.refresh(owner))
        val cached = checkNotNull(repository.observe(owner).first())
        assertFalse(cached.personalized)
        assertEquals(listOf(EventMissingContext.SPORTS, EventMissingContext.CITY), cached.knownMissingContext)
        assertEquals(listOf(EventSuggestionReason.UPCOMING), cached.items.single().knownReasons)
        assertFalse(cached.contextUnavailable)
    }

    @Test
    fun `sin ciudad la lista no es personalizada y declara city (CA2)`() = runTest {
        login()
        respond(EventSuggestionsFixtures.notPersonalized(listOf("city")))

        repository.refresh(owner)
        val cached = checkNotNull(repository.observe(owner).first())
        assertFalse(cached.personalized)
        assertEquals(listOf(EventMissingContext.CITY), cached.knownMissingContext)
    }

    @Test
    fun `con Usuarios caido la lista no es personalizada y se marca la fuente no disponible (CA2)`() = runTest {
        login()
        respond(EventSuggestionsFixtures.notPersonalized(usersStatus = "UNAVAILABLE"))

        repository.refresh(owner)
        val cached = checkNotNull(repository.observe(owner).first())
        assertFalse(cached.personalized)
        assertTrue(cached.contextUnavailable)
    }

    @Test
    fun `una nueva consulta reemplaza la lista y un evento que dejo de sugerirse desaparece (CA3)`() = runTest {
        login()
        respond(EventSuggestionsFixtures.body())
        repository.refresh(owner)
        respond(EventSuggestionsFixtures.body(items = listOf(EventSuggestionsFixtures.item(eventId = EventSuggestionsFixtures.SPORT_EVENT_ID))))
        clock.instant = clock.instant.plusSeconds(60)

        repository.refresh(owner)

        val cached = checkNotNull(repository.observe(owner).first())
        assertEquals(listOf(EventSuggestionsFixtures.SPORT_EVENT_ID), cached.items.map { it.eventId })
        assertEquals(clock.instant, cached.syncedAt)
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun `sin eventos es una lista confirmada vacia, no un error`() = runTest {
        login()
        respond(EventSuggestionsFixtures.body(items = emptyList()))

        assertEquals(EventSuggestionsRefresh.Updated, repository.refresh(owner))
        assertTrue(checkNotNull(repository.observe(owner).first()).items.isEmpty())
    }

    @Test
    fun `codigos futuros de motivo o contexto se conservan sin interpretarse`() = runTest {
        login()
        respond(
            EventSuggestionsFixtures.body(
                items = listOf(EventSuggestionsFixtures.item(reasons = listOf("CITY_MATCH", "NEARBY"))),
                missingContext = listOf("city", "country_code"),
            )
        )

        repository.refresh(owner)
        val cached = checkNotNull(repository.observe(owner).first())
        assertEquals(listOf("CITY_MATCH", "NEARBY"), cached.items.single().reasons)
        assertEquals(listOf(EventSuggestionReason.CITY_MATCH), cached.items.single().knownReasons)
        assertEquals(listOf("city", "country_code"), cached.missingContext)
    }

    // ----------------------------------------------------------------- errores

    @Test
    fun `sin la capacidad el BFF responde 403 capability_required`() = runTest {
        login()
        server.enqueue(SessionFixtures.error(403, "capability_required"))

        assertEquals(EventSuggestionsRefresh.CapabilityRequired, repository.refresh(owner))
    }

    @Test
    fun `un actor distinto de deportista recibe 403 permission_required`() = runTest {
        login()
        server.enqueue(SessionFixtures.error(403, "permission_required"))

        assertEquals(EventSuggestionsRefresh.PermissionRequired, repository.refresh(owner))
    }

    @Test
    fun `un limite rechazado con 422 se informa sin datos`() = runTest {
        login()
        respond(EventSuggestionsFixtures.validationError(), 422)

        assertEquals(EventSuggestionsRefresh.InvalidRequest, repository.refresh(owner))
        assertNull(repository.observe(owner).first())
    }

    @Test
    fun `con Eventos caido 503 y 504 no reemplazan la cache (CA5)`() = runTest {
        login()
        respond(EventSuggestionsFixtures.body())
        repository.refresh(owner)

        server.enqueue(SessionFixtures.error(503, "upstream_unavailable"))
        assertEquals(EventSuggestionsRefresh.Unavailable("8c1f2d3e-0000-4000-8000-000000000001"), repository.refresh(owner))
        server.enqueue(SessionFixtures.error(504, "upstream_timeout"))
        assertTrue(repository.refresh(owner) is EventSuggestionsRefresh.Unavailable)

        assertEquals(3, checkNotNull(repository.observe(owner).first()).items.size)
    }

    @Test
    fun `un 401 renueva la sesion una vez y repite la consulta`() = runTest {
        login()
        server.enqueue(SessionFixtures.error(401, "invalid_access_token"))
        server.enqueue(SessionFixtures.ok(SessionFixtures.sessionBody(accessToken = "access-token-sintetico-2", refreshToken = SessionFixtures.ROTATED_REFRESH_TOKEN)))
        respond(EventSuggestionsFixtures.body())

        assertEquals(EventSuggestionsRefresh.Updated, repository.refresh(owner))
        takeRequest()
        assertEquals("/v1/sessions/refresh", takeRequest().path)
        assertEquals("Bearer access-token-sintetico-2", takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `sin sesion no consulta y pide volver al acceso`() = runTest {
        assertEquals(EventSuggestionsRefresh.SignedOut, repository.refresh(owner))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `sin red conserva la cache y lo informa`() = runTest {
        login()
        respond(EventSuggestionsFixtures.body())
        repository.refresh(owner)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        assertEquals(EventSuggestionsRefresh.NoConnection, repository.refresh(owner))
        assertEquals(3, checkNotNull(repository.observe(owner).first()).items.size)
    }

    @Test
    fun `sin URL del BFF configurada no consulta`() = runTest {
        assertEquals(EventSuggestionsRefresh.Unavailable(null), uncached().refresh(owner))
    }

    @Test
    fun `una respuesta fuera de contrato no se guarda ni se muestra parcialmente`() = runTest {
        login()
        val invalid = listOf(
            EventSuggestionsFixtures.item(startsAt = "mañana"),
            EventSuggestionsFixtures.item(endsAt = "2026-11-15 17:00"),
            EventSuggestionsFixtures.item(referenceType = "route"),
            EventSuggestionsFixtures.item(referenceId = EventSuggestionsFixtures.SPORT_EVENT_ID),
            EventSuggestionsFixtures.item(eventId = "evento-1"),
            EventSuggestionsFixtures.item(myRegistration = EventSuggestionsFixtures.registration(status = "CANCELLED")),
            EventSuggestionsFixtures.item(myRegistration = EventSuggestionsFixtures.registration(registeredAt = "ayer")),
        )
        invalid.forEach { item ->
            respond(EventSuggestionsFixtures.body(items = listOf(EventSuggestionsFixtures.item(eventId = EventSuggestionsFixtures.SPORT_EVENT_ID), item)))
            assertEquals(item, EventSuggestionsRefresh.InvalidResponse, repository.refresh(owner))
        }
        respond("""{"items":[],"personalized":true}""")
        assertEquals(EventSuggestionsRefresh.InvalidResponse, repository.refresh(owner))
        respond(EventSuggestionsFixtures.body(generatedAt = "hoy"))
        assertEquals(EventSuggestionsRefresh.InvalidResponse, repository.refresh(owner))

        assertNull(repository.observe(owner).first())
    }

    @Test
    fun `si no se puede guardar se informa sin perder la cache anterior`() = runTest {
        login()
        respond(EventSuggestionsFixtures.body())
        repository.refresh(owner)
        dao.failWrites = true
        respond(EventSuggestionsFixtures.body(items = emptyList()))

        assertEquals(EventSuggestionsRefresh.StorageFailed, repository.refresh(owner))
        assertEquals(3, checkNotNull(repository.observe(owner).first()).items.size)
    }

    // ----------------------------------------------------------------- caché cifrada

    @Test
    fun `la cache solo guarda texto cifrado y la particion`() = runTest {
        login()
        respond(EventSuggestionsFixtures.body())
        repository.refresh(owner)

        val row = dao.rows.value.getValue(owner)
        val stored = row.payload.toString(Charsets.ISO_8859_1)
        listOf("Media marat", "Parque", "Bogot", "45000", EventSuggestionsFixtures.MATCH_EVENT_ID, EventSuggestionsFixtures.REGISTRATION_ID)
            .forEach { assertFalse(it, it in stored) }
        assertFalse(TrainingFixtures.USER_ID in row.ownerKey)
    }

    @Test
    fun `la cache descifrada reproduce la lista confirmada`() = runTest {
        login()
        respond(EventSuggestionsFixtures.body())
        repository.refresh(owner)
        val first = checkNotNull(repository.observe(owner).first())

        assertEquals(first, uncached().observe(owner).first())
    }

    @Test
    fun `una cache ilegible se trata como inexistente y no inventa eventos`() = runTest {
        dao.replace(EventSuggestionsCacheEntity(owner, byteArrayOf(1, 2, 3)))
        assertNull(repository.observe(owner).first())

        login()
        respond(EventSuggestionsFixtures.body())
        repository.refresh(owner)
        cipher.broken = true
        assertNull(repository.observe(owner).first())
    }

    @Test
    fun `la cache conserva la antiguedad y se marca desactualizada a las 24 horas`() = runTest {
        login()
        respond(EventSuggestionsFixtures.body())
        repository.refresh(owner)
        val cached = checkNotNull(repository.observe(owner).first())

        assertFalse(cached.isStale(clock.instant.plusSeconds(24 * 3600)))
        assertTrue(cached.isStale(clock.instant.plusSeconds(24 * 3600 + 1)))
    }

    @Test
    fun `cada deportista solo ve su particion y la limpieza respeta las demas`() = runTest {
        login()
        respond(EventSuggestionsFixtures.body())
        repository.refresh(owner)
        val other = EventSuggestionsOwner.keyOf(TrainingFixtures.OTHER_USER_ID)

        assertNull(repository.observe(other).first())
        repository.clearAllExcept(other)
        assertNull(repository.observe(owner).first())
    }

    // ----------------------------------------------------------------- limpieza al cerrar sesión

    @Test
    fun `el limpiador borra la cache al cerrar sesion y la de otros deportistas al entrar`() = runTest {
        val user = MutableStateFlow<SessionUser?>(null)
        val other = EventSuggestionsOwner.keyOf(TrainingFixtures.OTHER_USER_ID)
        dao.replace(EventSuggestionsCacheEntity(other, byteArrayOf(9)))
        dao.replace(EventSuggestionsCacheEntity(owner, byteArrayOf(7)))
        val job = EventSuggestionsCacheJanitor(user, repository).start(TestScope(UnconfinedTestDispatcher(testScheduler)))

        user.value = SessionUser(TrainingFixtures.USER_ID, "Ana Deportista", "ana@example.com", ActorType.ATHLETE)
        assertEquals(setOf(owner), dao.rows.value.keys)

        user.value = null
        assertTrue(dao.rows.value.isEmpty())
        job.cancelAndJoin()
    }
}
