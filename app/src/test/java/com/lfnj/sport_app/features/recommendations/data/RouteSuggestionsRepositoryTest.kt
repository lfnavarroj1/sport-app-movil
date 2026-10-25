package com.lfnj.sport_app.features.recommendations.data

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.recommendations.data.local.RouteSuggestionsCacheEntity
import com.lfnj.sport_app.features.recommendations.data.local.RouteSuggestionsCodec
import com.lfnj.sport_app.features.recommendations.model.ContextSourceStatus
import com.lfnj.sport_app.features.recommendations.model.RouteMissingContext
import com.lfnj.sport_app.features.recommendations.model.RoutePoint
import com.lfnj.sport_app.features.recommendations.model.RouteSuggestionReason
import com.lfnj.sport_app.features.recommendations.model.RouteSuggestionsOwner
import com.lfnj.sport_app.testing.FakeCipher
import com.lfnj.sport_app.testing.FakeTokenStore
import com.lfnj.sport_app.testing.InMemoryRouteSuggestionsDao
import com.lfnj.sport_app.testing.MutableClock
import com.lfnj.sport_app.testing.RouteSuggestionsFixtures
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
 * HU026 contra un `bff-mobile` simulado con MockWebServer y la caché en memoria (cifrado de
 * prueba). Contrato simulado según `bff-mobile` 0.15.0: **no** es un E2E.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteSuggestionsRepositoryTest {
    private val server = MockWebServer().apply { start() }
    private val retrofit = BffMobileClient.retrofit(
        server.url("/").toString(),
        BffMobileClient.okHttp().newBuilder().retryOnConnectionFailure(false).build(),
    )
    private val sessionManager = SessionManager(retrofit.create(SessionApi::class.java), FakeTokenStore(), BffMobileClient.json, clock = { 1_000_000L })
    private val dao = InMemoryRouteSuggestionsDao()
    private val cipher = FakeCipher()
    private val clock = MutableClock(Instant.parse("2026-10-25T15:00:00Z"), ZoneId.of("America/Bogota"))
    private val repository = RouteSuggestionsRepository(
        dao, RouteSuggestionsCodec(cipher, BffMobileClient.json), retrofit.create(RouteSuggestionsApi::class.java),
        sessionManager, BffMobileClient.json, clock,
    )
    private val owner = RouteSuggestionsOwner.keyOf(TrainingFixtures.USER_ID)

    @After
    fun tearDown() = server.shutdown()

    private suspend fun login() {
        server.enqueue(SessionFixtures.ok(SessionFixtures.sessionBody()))
        sessionManager.login("ana@example.com", "Secreta-123")
        server.takeRequest()
    }

    private fun takeRequest(): RecordedRequest = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS)) { "No llegó la solicitud esperada" }

    private fun respond(body: String, status: Int = 200) = server.enqueue(MockResponse().setResponseCode(status).setBody(body))

    // ----------------------------------------------------------------- consulta (CA3)

    @Test
    fun `consulta con el token y el limite y guarda la lista confirmada en el orden del BFF (CA3)`() = runTest {
        login()
        respond(RouteSuggestionsFixtures.body())

        assertEquals(RouteSuggestionsRefresh.Updated, repository.refresh(owner))

        val request = takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/me/suggestions/routes", request.requestUrl?.encodedPath)
        assertEquals("20", request.requestUrl?.queryParameter("limit"))
        assertEquals("Bearer ${SessionFixtures.ACCESS_TOKEN}", request.getHeader("Authorization"))

        val cached = checkNotNull(repository.observe(owner).first())
        assertTrue(cached.personalized)
        assertEquals(emptyList<String>(), cached.missingContext)
        assertEquals(ContextSourceStatus.OK, cached.usersSource)
        assertEquals(Instant.parse("2026-10-25T13:00:00Z"), cached.generatedAt)
        assertEquals(clock.instant, cached.syncedAt)
        // Primero la de su ciudad, como la ordenó el BFF: el cliente no reordena.
        assertEquals(listOf(RouteSuggestionsFixtures.CITY_ROUTE_ID, RouteSuggestionsFixtures.OTHER_CITY_ROUTE_ID), cached.items.map { it.routeId })
        assertEquals(listOf(RouteSuggestionReason.SPORT_MATCH, RouteSuggestionReason.CITY_MATCH), cached.items[0].knownReasons)
        assertEquals(listOf(RouteSuggestionReason.SPORT_MATCH), cached.items[1].knownReasons)
    }

    @Test
    fun `cada ruta conserva distancia, dificultad, autor, lugar, punto de inicio y puntos intermedios`() = runTest {
        login()
        respond(RouteSuggestionsFixtures.body())
        repository.refresh(owner)

        val items = checkNotNull(repository.observe(owner).first()).items
        val item = items[0]
        assertEquals("Circuito Parque Simón Bolívar", item.title)
        assertEquals("Recorrido plano alrededor del lago.", item.summary)
        assertEquals("Carlos Ruiz", item.authorName)
        assertEquals("running", item.sport)
        assertEquals("5.0", item.distanceKm)
        assertEquals("LOW", item.difficulty)
        assertEquals("Bogotá", item.city)
        assertEquals("CO", item.countryCode)
        assertEquals(RoutePoint(4.6097, -74.0817), item.startPoint)
        assertEquals(listOf(RoutePoint(4.6584, -74.0937), RoutePoint(4.6601, -74.0952)), item.waypoints)
        assertEquals(Instant.parse("2026-10-20T14:00:00Z"), item.publishedAt)
        assertEquals("12.5", items[1].distanceKm)
        assertTrue(items[1].waypoints.isEmpty())
    }

    @Test
    fun `sin respuesta de Usuarios el autor llega nulo y una ruta sin descripcion no la inventa`() = runTest {
        login()
        respond(RouteSuggestionsFixtures.body(items = listOf(RouteSuggestionsFixtures.item(authorName = null, summary = null))))

        assertEquals(RouteSuggestionsRefresh.Updated, repository.refresh(owner))
        val item = checkNotNull(repository.observe(owner).first()).items.single()
        assertNull(item.authorName)
        assertNull(item.summary)
    }

    @Test
    fun `sin deportes la lista no personalizada declara missing_context y no trae motivos (CA3)`() = runTest {
        login()
        respond(RouteSuggestionsFixtures.notPersonalized(listOf("sports")))

        assertEquals(RouteSuggestionsRefresh.Updated, repository.refresh(owner))
        val cached = checkNotNull(repository.observe(owner).first())
        assertFalse(cached.personalized)
        assertEquals(listOf(RouteMissingContext.SPORTS), cached.knownMissingContext)
        assertTrue(cached.items.single().reasons.isEmpty())
        assertFalse(cached.contextUnavailable)
    }

    @Test
    fun `con Usuarios caido la lista no es personalizada y se marca la fuente no disponible (CA3)`() = runTest {
        login()
        respond(RouteSuggestionsFixtures.notPersonalized(usersStatus = "UNAVAILABLE"))

        repository.refresh(owner)
        val cached = checkNotNull(repository.observe(owner).first())
        assertFalse(cached.personalized)
        assertTrue(cached.contextUnavailable)
        assertEquals(listOf(RouteMissingContext.SPORTS, RouteMissingContext.CITY), cached.knownMissingContext)
    }

    @Test
    fun `con deportes y sin ciudad sigue personalizada por deporte con missing_context city`() = runTest {
        login()
        respond(RouteSuggestionsFixtures.body(missingContext = listOf("city")))

        repository.refresh(owner)
        val cached = checkNotNull(repository.observe(owner).first())
        assertTrue(cached.personalized)
        assertEquals(listOf(RouteMissingContext.CITY), cached.knownMissingContext)
    }

    @Test
    fun `una nueva consulta reemplaza la lista y una ruta retirada deja de mostrarse (CA4)`() = runTest {
        login()
        respond(RouteSuggestionsFixtures.body())
        repository.refresh(owner)
        respond(RouteSuggestionsFixtures.body(items = listOf(RouteSuggestionsFixtures.item(routeId = RouteSuggestionsFixtures.OTHER_CITY_ROUTE_ID))))
        clock.instant = clock.instant.plusSeconds(60)

        repository.refresh(owner)

        val cached = checkNotNull(repository.observe(owner).first())
        assertEquals(listOf(RouteSuggestionsFixtures.OTHER_CITY_ROUTE_ID), cached.items.map { it.routeId })
        assertNull(cached.find(RouteSuggestionsFixtures.CITY_ROUTE_ID))
        assertEquals(clock.instant, cached.syncedAt)
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun `sin rutas es una lista confirmada vacia, no un error`() = runTest {
        login()
        respond(RouteSuggestionsFixtures.body(items = emptyList()))

        assertEquals(RouteSuggestionsRefresh.Updated, repository.refresh(owner))
        assertTrue(checkNotNull(repository.observe(owner).first()).items.isEmpty())
    }

    @Test
    fun `codigos futuros de motivo, contexto, deporte o dificultad se conservan sin interpretarse`() = runTest {
        login()
        respond(
            RouteSuggestionsFixtures.body(
                items = listOf(RouteSuggestionsFixtures.item(reasons = listOf("CITY_MATCH", "NEARBY"), difficulty = "EXTREME", sport = "hiking")),
                missingContext = listOf("city", "country_code"),
            )
        )

        repository.refresh(owner)
        val cached = checkNotNull(repository.observe(owner).first())
        val item = cached.items.single()
        assertEquals(listOf("CITY_MATCH", "NEARBY"), item.reasons)
        assertEquals(listOf(RouteSuggestionReason.CITY_MATCH), item.knownReasons)
        assertEquals("EXTREME", item.difficulty)
        assertEquals("hiking", item.sport)
        assertEquals(listOf("city", "country_code"), cached.missingContext)
    }

    // ----------------------------------------------------------------- errores

    @Test
    fun `sin la capacidad el BFF responde 403 capability_required`() = runTest {
        login()
        server.enqueue(SessionFixtures.error(403, "capability_required"))

        assertEquals(RouteSuggestionsRefresh.CapabilityRequired, repository.refresh(owner))
    }

    @Test
    fun `un actor distinto de deportista recibe 403 permission_required`() = runTest {
        login()
        server.enqueue(SessionFixtures.error(403, "permission_required"))

        assertEquals(RouteSuggestionsRefresh.PermissionRequired, repository.refresh(owner))
    }

    @Test
    fun `un limite rechazado con 422 se informa sin datos`() = runTest {
        login()
        respond(RouteSuggestionsFixtures.validationError(), 422)

        assertEquals(RouteSuggestionsRefresh.InvalidRequest, repository.refresh(owner))
        assertNull(repository.observe(owner).first())
    }

    @Test
    fun `503 y 504 no reemplazan la cache`() = runTest {
        login()
        respond(RouteSuggestionsFixtures.body())
        repository.refresh(owner)

        server.enqueue(SessionFixtures.error(503, "upstream_unavailable"))
        assertEquals(RouteSuggestionsRefresh.Unavailable("8c1f2d3e-0000-4000-8000-000000000001"), repository.refresh(owner))
        server.enqueue(SessionFixtures.error(504, "upstream_timeout"))
        assertTrue(repository.refresh(owner) is RouteSuggestionsRefresh.Unavailable)

        assertEquals(2, checkNotNull(repository.observe(owner).first()).items.size)
    }

    @Test
    fun `un 401 renueva la sesion una vez y repite la consulta`() = runTest {
        login()
        server.enqueue(SessionFixtures.error(401, "invalid_access_token"))
        server.enqueue(SessionFixtures.ok(SessionFixtures.sessionBody(accessToken = "access-token-sintetico-2", refreshToken = SessionFixtures.ROTATED_REFRESH_TOKEN)))
        respond(RouteSuggestionsFixtures.body())

        assertEquals(RouteSuggestionsRefresh.Updated, repository.refresh(owner))
        takeRequest()
        assertEquals("/v1/sessions/refresh", takeRequest().path)
        assertEquals("Bearer access-token-sintetico-2", takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `sin sesion no consulta y pide volver al acceso`() = runTest {
        assertEquals(RouteSuggestionsRefresh.SignedOut, repository.refresh(owner))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `sin red conserva la cache y lo informa`() = runTest {
        login()
        respond(RouteSuggestionsFixtures.body())
        repository.refresh(owner)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        assertEquals(RouteSuggestionsRefresh.NoConnection, repository.refresh(owner))
        assertEquals(2, checkNotNull(repository.observe(owner).first()).items.size)
    }

    @Test
    fun `sin URL del BFF configurada no consulta`() = runTest {
        val unconfigured = RouteSuggestionsRepository(dao, RouteSuggestionsCodec(cipher, BffMobileClient.json), null, sessionManager, BffMobileClient.json, clock)

        assertEquals(RouteSuggestionsRefresh.Unavailable(null), unconfigured.refresh(owner))
    }

    @Test
    fun `una respuesta fuera de contrato no se guarda ni se muestra parcialmente`() = runTest {
        login()
        val invalid = listOf(
            RouteSuggestionsFixtures.item(publishedAt = "ayer"),
            RouteSuggestionsFixtures.item(referenceType = "training_plan_template"),
            RouteSuggestionsFixtures.item(source = "ATHLETE"),
            RouteSuggestionsFixtures.item(distanceKm = "5"),
            RouteSuggestionsFixtures.item(distanceKm = "cinco"),
            RouteSuggestionsFixtures.item(startPoint = RouteSuggestionsFixtures.point(lat = 91.0)),
            RouteSuggestionsFixtures.item(waypoints = listOf(RouteSuggestionsFixtures.point(lng = -180.5))),
        )
        invalid.forEach { item ->
            respond(RouteSuggestionsFixtures.body(items = listOf(RouteSuggestionsFixtures.item(routeId = RouteSuggestionsFixtures.OTHER_CITY_ROUTE_ID), item)))
            assertEquals(item, RouteSuggestionsRefresh.InvalidResponse, repository.refresh(owner))
        }
        respond("""{"items":[],"personalized":true}""")
        assertEquals(RouteSuggestionsRefresh.InvalidResponse, repository.refresh(owner))

        assertNull(repository.observe(owner).first())
    }

    @Test
    fun `si no se puede guardar se informa sin perder la cache anterior`() = runTest {
        login()
        respond(RouteSuggestionsFixtures.body())
        repository.refresh(owner)
        dao.failWrites = true
        respond(RouteSuggestionsFixtures.body(items = emptyList()))

        assertEquals(RouteSuggestionsRefresh.StorageFailed, repository.refresh(owner))
        assertEquals(2, checkNotNull(repository.observe(owner).first()).items.size)
    }

    // ----------------------------------------------------------------- caché cifrada

    @Test
    fun `la cache solo guarda texto cifrado y la particion`() = runTest {
        login()
        respond(RouteSuggestionsFixtures.body())
        repository.refresh(owner)

        val row = dao.rows.value.getValue(owner)
        val stored = row.payload.toString(Charsets.ISO_8859_1)
        listOf("Circuito", "Carlos", "4.6097", "Bogot", RouteSuggestionsFixtures.CITY_ROUTE_ID).forEach { assertFalse(it, it in stored) }
        assertFalse(TrainingFixtures.USER_ID in row.ownerKey)
    }

    @Test
    fun `la cache descifrada reproduce la lista confirmada`() = runTest {
        login()
        respond(RouteSuggestionsFixtures.body(items = listOf(RouteSuggestionsFixtures.item(summary = null, authorName = null))))
        repository.refresh(owner)
        val first = checkNotNull(repository.observe(owner).first())

        val reread = RouteSuggestionsRepository(dao, RouteSuggestionsCodec(cipher, BffMobileClient.json), null, sessionManager, BffMobileClient.json, clock)
        assertEquals(first, reread.observe(owner).first())
    }

    @Test
    fun `una cache ilegible se trata como inexistente y no inventa rutas`() = runTest {
        dao.replace(RouteSuggestionsCacheEntity(owner, byteArrayOf(1, 2, 3)))
        assertNull(repository.observe(owner).first())

        login()
        respond(RouteSuggestionsFixtures.body())
        repository.refresh(owner)
        cipher.broken = true
        assertNull(repository.observe(owner).first())
    }

    @Test
    fun `la cache conserva la antiguedad y se marca desactualizada a las 24 horas`() = runTest {
        login()
        respond(RouteSuggestionsFixtures.body())
        repository.refresh(owner)
        val cached = checkNotNull(repository.observe(owner).first())

        assertFalse(cached.isStale(clock.instant.plusSeconds(24 * 3600)))
        assertTrue(cached.isStale(clock.instant.plusSeconds(24 * 3600 + 1)))
    }

    @Test
    fun `cada deportista solo ve su particion y la limpieza respeta las demas`() = runTest {
        login()
        respond(RouteSuggestionsFixtures.body())
        repository.refresh(owner)
        val other = RouteSuggestionsOwner.keyOf(TrainingFixtures.OTHER_USER_ID)

        assertNull(repository.observe(other).first())
        repository.clearAllExcept(other)
        assertNull(repository.observe(owner).first())
    }

    // ----------------------------------------------------------------- limpieza al cerrar sesión

    @Test
    fun `el limpiador borra la cache al cerrar sesion y la de otros deportistas al entrar`() = runTest {
        val user = MutableStateFlow<SessionUser?>(null)
        val other = RouteSuggestionsOwner.keyOf(TrainingFixtures.OTHER_USER_ID)
        dao.replace(RouteSuggestionsCacheEntity(other, byteArrayOf(9)))
        dao.replace(RouteSuggestionsCacheEntity(owner, byteArrayOf(7)))
        val job = RouteSuggestionsCacheJanitor(user, repository).start(TestScope(UnconfinedTestDispatcher(testScheduler)))
        assertEquals(2, dao.rows.value.size)

        user.value = SessionUser(TrainingFixtures.USER_ID, "Ana Deportista", "ana@example.com", ActorType.ATHLETE)
        assertEquals(setOf(owner), dao.rows.value.keys)

        user.value = null
        assertTrue(dao.rows.value.isEmpty())
        job.cancelAndJoin()
    }
}
