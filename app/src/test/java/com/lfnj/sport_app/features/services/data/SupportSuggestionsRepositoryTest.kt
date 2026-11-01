package com.lfnj.sport_app.features.services.data

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.services.data.local.SupportSuggestionsCacheEntity
import com.lfnj.sport_app.features.services.data.local.SupportSuggestionsCodec
import com.lfnj.sport_app.features.services.model.ServiceFee
import com.lfnj.sport_app.features.services.model.SupportReason
import com.lfnj.sport_app.features.services.model.SupportSourceStatus
import com.lfnj.sport_app.features.services.model.SupportSuggestionsOwner
import com.lfnj.sport_app.testing.FakeCipher
import com.lfnj.sport_app.testing.FakeTokenStore
import com.lfnj.sport_app.testing.InMemorySupportSuggestionsDao
import com.lfnj.sport_app.testing.MutableClock
import com.lfnj.sport_app.testing.SessionFixtures
import com.lfnj.sport_app.testing.SupportSuggestionsFixtures
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
 * HU029 contra un `bff-mobile` simulado con MockWebServer y la caché en memoria (cifrado de
 * prueba). Contrato simulado según `bff-mobile` 0.18.0: **no** es un E2E.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SupportSuggestionsRepositoryTest {
    private val server = MockWebServer().apply { start() }
    private val retrofit = BffMobileClient.retrofit(
        server.url("/").toString(),
        BffMobileClient.okHttp().newBuilder().retryOnConnectionFailure(false).build(),
    )
    private val sessionManager = SessionManager(retrofit.create(SessionApi::class.java), FakeTokenStore(), BffMobileClient.json, clock = { 1_000_000L })
    private val dao = InMemorySupportSuggestionsDao()
    private val cipher = FakeCipher()
    private val clock = MutableClock(Instant.parse("2026-10-25T15:00:00Z"), ZoneId.of("America/Bogota"))
    private val repository = SupportSuggestionsRepository(
        dao, SupportSuggestionsCodec(cipher, BffMobileClient.json), retrofit.create(SupportSuggestionsApi::class.java),
        sessionManager, BffMobileClient.json, clock,
    )
    private val owner = SupportSuggestionsOwner.keyOf(TrainingFixtures.USER_ID)

    @After
    fun tearDown() = server.shutdown()

    private suspend fun login() {
        server.enqueue(SessionFixtures.ok(SessionFixtures.sessionBody()))
        sessionManager.login("ana@example.com", "Secreta-123")
        server.takeRequest()
    }

    private fun takeRequest(): RecordedRequest = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS)) { "No llegó la solicitud esperada" }

    private fun respond(body: String, status: Int = 200) = server.enqueue(MockResponse().setResponseCode(status).setBody(body))

    // ----------------------------------------------------------------- consulta (CA1, CA3)

    @Test
    fun `consulta con el token y el limite y guarda la lista en el orden del BFF, logistica primero (CA1)`() = runTest {
        login()
        respond(SupportSuggestionsFixtures.body())

        assertEquals(SupportSuggestionsRefresh.Updated, repository.refresh(owner))

        val request = takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/me/suggestions/services", request.requestUrl?.encodedPath)
        assertEquals("20", request.requestUrl?.queryParameter("limit"))
        assertEquals("Bearer ${SessionFixtures.ACCESS_TOKEN}", request.getHeader("Authorization"))

        val cached = checkNotNull(repository.observe(owner).first())
        assertTrue(cached.personalized)
        assertEquals(emptyList<String>(), cached.missingContext)
        assertEquals(SupportSourceStatus.OK, cached.eventsSource)
        assertEquals(SupportSourceStatus.OK, cached.usersSource)
        assertEquals(Instant.parse("2026-10-25T13:00:00Z"), cached.generatedAt)
        assertEquals(clock.instant, cached.syncedAt)
        // El cliente no reordena: primero el transporte del día del evento, luego el entrenamiento.
        assertEquals(listOf(SupportSuggestionsFixtures.TRANSPORT_SERVICE_ID, SupportSuggestionsFixtures.COACHING_SERVICE_ID), cached.items.map { it.serviceId })
        assertEquals(listOf(SupportReason.EVENT_LOGISTICS), cached.items[0].knownReasons)
        assertEquals(listOf(SupportReason.UPCOMING_AVAILABILITY), cached.items[1].knownReasons)
    }

    @Test
    fun `cada servicio conserva proveedor, categoria, modalidad, tarifa, proximo intervalo y motivo (CA3)`() = runTest {
        login()
        respond(SupportSuggestionsFixtures.body())
        repository.refresh(owner)

        val item = checkNotNull(repository.observe(owner).first()).items[0]
        assertEquals("Transporte al Medio Maratón de Bogotá", item.title)
        assertEquals("Carlos Rodríguez", item.authorName)
        assertEquals("TRANSPORT", item.category)
        assertEquals("IN_PERSON", item.modality)
        assertEquals(ServiceFee("80000.00", "COP", "SERVICE"), item.fee)
        assertEquals(Instant.parse("2026-11-08T10:00:00Z"), item.nextInterval.startsAt)
        assertEquals(Instant.parse("2026-11-08T12:00:00Z"), item.nextInterval.endsAt)
        assertEquals(ZoneId.of("America/Bogota"), item.nextInterval.timeZone)
        assertEquals(listOf("EVENT_LOGISTICS"), item.reasons)
    }

    @Test
    fun `sin respuesta de Usuarios el proveedor llega nulo y se declara la fuente`() = runTest {
        login()
        respond(SupportSuggestionsFixtures.body(items = listOf(SupportSuggestionsFixtures.item(authorName = null)), usersStatus = "UNAVAILABLE"))

        assertEquals(SupportSuggestionsRefresh.Updated, repository.refresh(owner))
        val cached = checkNotNull(repository.observe(owner).first())
        assertNull(cached.items.single().authorName)
        assertEquals(SupportSourceStatus.UNAVAILABLE, cached.usersSource)
    }

    @Test
    fun `sin inscripciones vigentes la lista declara missing_context y solo trae entrenamiento (CA2)`() = runTest {
        login()
        respond(SupportSuggestionsFixtures.withoutRegistrations())

        repository.refresh(owner)
        val cached = checkNotNull(repository.observe(owner).first())
        assertFalse(cached.personalized)
        assertTrue(cached.missingRegistrations)
        assertFalse(cached.eventsUnavailable)
        assertEquals(listOf("COACHING"), cached.items.map { it.category })
    }

    @Test
    fun `sin coincidencias es una lista vacia explicada, no un error ni servicios arbitrarios (CA2)`() = runTest {
        login()
        respond(SupportSuggestionsFixtures.withoutRegistrations(items = emptyList()))

        assertEquals(SupportSuggestionsRefresh.Updated, repository.refresh(owner))
        val cached = checkNotNull(repository.observe(owner).first())
        assertTrue(cached.items.isEmpty())
        assertTrue(cached.missingRegistrations)

        respond(SupportSuggestionsFixtures.body(items = emptyList()))
        repository.refresh(owner)
        val noMatch = checkNotNull(repository.observe(owner).first())
        assertTrue(noMatch.items.isEmpty())
        assertFalse(noMatch.missingRegistrations)
    }

    @Test
    fun `si solo falla Eventos la lista llega sin el motivo logistico y lo declara en sources (CA5)`() = runTest {
        login()
        respond(SupportSuggestionsFixtures.eventsDown())

        assertEquals(SupportSuggestionsRefresh.Updated, repository.refresh(owner))
        val cached = checkNotNull(repository.observe(owner).first())
        assertTrue(cached.eventsUnavailable)
        assertTrue(cached.items.none { SupportReason.EVENT_LOGISTICS in it.knownReasons })
    }

    @Test
    fun `una nueva consulta reemplaza la lista y un servicio despublicado deja de mostrarse (CA4)`() = runTest {
        login()
        respond(SupportSuggestionsFixtures.body())
        repository.refresh(owner)
        respond(SupportSuggestionsFixtures.body(items = listOf(SupportSuggestionsFixtures.coaching())))
        clock.instant = clock.instant.plusSeconds(60)

        repository.refresh(owner)

        val cached = checkNotNull(repository.observe(owner).first())
        assertEquals(listOf(SupportSuggestionsFixtures.COACHING_SERVICE_ID), cached.items.map { it.serviceId })
        assertNull(cached.find(SupportSuggestionsFixtures.TRANSPORT_SERVICE_ID))
        assertEquals(clock.instant, cached.syncedAt)
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun `codigos futuros de motivo, categoria o contexto se conservan sin interpretarse`() = runTest {
        login()
        respond(
            SupportSuggestionsFixtures.body(
                items = listOf(SupportSuggestionsFixtures.item(reasons = listOf("EVENT_LOGISTICS", "CITY_MATCH"), modality = "OUTDOOR")),
                missingContext = listOf("event_registrations", "city"),
            )
        )

        repository.refresh(owner)
        val cached = checkNotNull(repository.observe(owner).first())
        assertEquals(listOf("EVENT_LOGISTICS", "CITY_MATCH"), cached.items.single().reasons)
        assertEquals(listOf(SupportReason.EVENT_LOGISTICS), cached.items.single().knownReasons)
        assertEquals("OUTDOOR", cached.items.single().modality)
        assertEquals(listOf("event_registrations", "city"), cached.missingContext)
    }

    // ----------------------------------------------------------------- errores

    @Test
    fun `sin la capacidad el BFF responde 403 capability_required`() = runTest {
        login()
        server.enqueue(SessionFixtures.error(403, "capability_required"))

        assertEquals(SupportSuggestionsRefresh.CapabilityRequired, repository.refresh(owner))
    }

    @Test
    fun `un actor distinto de deportista recibe 403 permission_required`() = runTest {
        login()
        server.enqueue(SessionFixtures.error(403, "permission_required"))

        assertEquals(SupportSuggestionsRefresh.PermissionRequired, repository.refresh(owner))
    }

    @Test
    fun `un limite rechazado con 422 se informa sin datos`() = runTest {
        login()
        respond(SupportSuggestionsFixtures.validationError(), 422)

        assertEquals(SupportSuggestionsRefresh.InvalidRequest, repository.refresh(owner))
        assertNull(repository.observe(owner).first())
    }

    @Test
    fun `Servicios caido responde 503 o 504 y no reemplaza la cache (CA5)`() = runTest {
        login()
        respond(SupportSuggestionsFixtures.body())
        repository.refresh(owner)

        server.enqueue(SessionFixtures.error(503, "upstream_unavailable"))
        assertEquals(SupportSuggestionsRefresh.Unavailable("8c1f2d3e-0000-4000-8000-000000000001"), repository.refresh(owner))
        server.enqueue(SessionFixtures.error(504, "upstream_timeout"))
        assertTrue(repository.refresh(owner) is SupportSuggestionsRefresh.Unavailable)

        assertEquals(2, checkNotNull(repository.observe(owner).first()).items.size)
    }

    @Test
    fun `un 401 renueva la sesion una vez y repite la consulta`() = runTest {
        login()
        server.enqueue(SessionFixtures.error(401, "invalid_access_token"))
        server.enqueue(SessionFixtures.ok(SessionFixtures.sessionBody(accessToken = "access-token-sintetico-2", refreshToken = SessionFixtures.ROTATED_REFRESH_TOKEN)))
        respond(SupportSuggestionsFixtures.body())

        assertEquals(SupportSuggestionsRefresh.Updated, repository.refresh(owner))
        takeRequest()
        assertEquals("/v1/sessions/refresh", takeRequest().path)
        assertEquals("Bearer access-token-sintetico-2", takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `sin sesion no consulta y pide volver al acceso`() = runTest {
        assertEquals(SupportSuggestionsRefresh.SignedOut, repository.refresh(owner))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `sin red conserva la cache y lo informa`() = runTest {
        login()
        respond(SupportSuggestionsFixtures.body())
        repository.refresh(owner)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        assertEquals(SupportSuggestionsRefresh.NoConnection, repository.refresh(owner))
        assertEquals(2, checkNotNull(repository.observe(owner).first()).items.size)
    }

    @Test
    fun `sin URL del BFF configurada no consulta`() = runTest {
        val unconfigured = SupportSuggestionsRepository(dao, SupportSuggestionsCodec(cipher, BffMobileClient.json), null, sessionManager, BffMobileClient.json, clock)

        assertEquals(SupportSuggestionsRefresh.Unavailable(null), unconfigured.refresh(owner))
    }

    @Test
    fun `una respuesta fuera de contrato no se guarda ni se muestra parcialmente`() = runTest {
        login()
        respond(SupportSuggestionsFixtures.body(items = listOf(SupportSuggestionsFixtures.item(startsAt = "mañana"))))
        assertEquals(SupportSuggestionsRefresh.InvalidResponse, repository.refresh(owner))

        respond(SupportSuggestionsFixtures.body(items = listOf(SupportSuggestionsFixtures.item(timeZone = "Bogota/Centro"))))
        assertEquals(SupportSuggestionsRefresh.InvalidResponse, repository.refresh(owner))

        respond(SupportSuggestionsFixtures.body(items = listOf(SupportSuggestionsFixtures.item(endsAt = "2026-11-08T10:00:00Z"))))
        assertEquals(SupportSuggestionsRefresh.InvalidResponse, repository.refresh(owner))

        respond(SupportSuggestionsFixtures.body(items = listOf(SupportSuggestionsFixtures.item(referenceType = "event"))))
        assertEquals(SupportSuggestionsRefresh.InvalidResponse, repository.refresh(owner))

        respond(SupportSuggestionsFixtures.body(items = listOf(SupportSuggestionsFixtures.item(referenceId = SupportSuggestionsFixtures.COACHING_SERVICE_ID))))
        assertEquals(SupportSuggestionsRefresh.InvalidResponse, repository.refresh(owner))

        respond(SupportSuggestionsFixtures.body(generatedAt = "hoy"))
        assertEquals(SupportSuggestionsRefresh.InvalidResponse, repository.refresh(owner))

        respond("""{"items":[],"personalized":true}""")
        assertEquals(SupportSuggestionsRefresh.InvalidResponse, repository.refresh(owner))

        assertNull(repository.observe(owner).first())
    }

    @Test
    fun `si no se puede guardar se informa sin perder la cache anterior`() = runTest {
        login()
        respond(SupportSuggestionsFixtures.body())
        repository.refresh(owner)
        dao.failWrites = true
        respond(SupportSuggestionsFixtures.body(items = emptyList()))

        assertEquals(SupportSuggestionsRefresh.StorageFailed, repository.refresh(owner))
        assertEquals(2, checkNotNull(repository.observe(owner).first()).items.size)
    }

    // ----------------------------------------------------------------- caché cifrada

    @Test
    fun `la cache solo guarda texto cifrado y la particion`() = runTest {
        login()
        respond(SupportSuggestionsFixtures.body())
        repository.refresh(owner)

        val row = dao.rows.value.getValue(owner)
        val stored = row.payload.toString(Charsets.ISO_8859_1)
        listOf("Transporte", "Carlos", "80000", SupportSuggestionsFixtures.TRANSPORT_SERVICE_ID).forEach { assertFalse(it, it in stored) }
        assertFalse(TrainingFixtures.USER_ID in row.ownerKey)
    }

    @Test
    fun `la cache descifrada reproduce la lista confirmada con sus fuentes`() = runTest {
        login()
        respond(SupportSuggestionsFixtures.eventsDown())
        repository.refresh(owner)

        val restored = checkNotNull(SupportSuggestionsCodec(cipher, BffMobileClient.json).decode(dao.rows.value.getValue(owner)))
        assertEquals(checkNotNull(repository.observe(owner).first()), restored)
        assertTrue(restored.eventsUnavailable)
        assertEquals(SupportSourceStatus.OK, restored.usersSource)
    }

    @Test
    fun `una cache ilegible se trata como inexistente y no inventa servicios`() = runTest {
        dao.replace(SupportSuggestionsCacheEntity(owner, byteArrayOf(1, 2, 3)))
        assertNull(repository.observe(owner).first())

        login()
        respond(SupportSuggestionsFixtures.body())
        repository.refresh(owner)
        cipher.broken = true
        assertNull(repository.observe(owner).first())
    }

    @Test
    fun `la cache conserva la antiguedad y se marca desactualizada a las 24 horas`() = runTest {
        login()
        respond(SupportSuggestionsFixtures.body())
        repository.refresh(owner)
        val cached = checkNotNull(repository.observe(owner).first())

        assertFalse(cached.isStale(clock.instant.plusSeconds(24 * 3600)))
        assertTrue(cached.isStale(clock.instant.plusSeconds(24 * 3600 + 1)))
    }

    @Test
    fun `cada deportista solo ve su particion y la limpieza respeta las demas`() = runTest {
        login()
        respond(SupportSuggestionsFixtures.body())
        repository.refresh(owner)
        val other = SupportSuggestionsOwner.keyOf(TrainingFixtures.OTHER_USER_ID)

        assertNull(repository.observe(other).first())
        repository.clearAllExcept(other)
        assertNull(repository.observe(owner).first())
    }

    // ----------------------------------------------------------------- limpieza al cerrar sesión

    @Test
    fun `el limpiador borra la cache al cerrar sesion y la de otros deportistas al entrar`() = runTest {
        val user = MutableStateFlow<SessionUser?>(null)
        val other = SupportSuggestionsOwner.keyOf(TrainingFixtures.OTHER_USER_ID)
        dao.replace(SupportSuggestionsCacheEntity(other, byteArrayOf(9)))
        dao.replace(SupportSuggestionsCacheEntity(owner, byteArrayOf(7)))
        val job = SupportSuggestionsCacheJanitor(user, repository).start(TestScope(UnconfinedTestDispatcher(testScheduler)))
        assertEquals(2, dao.rows.value.size)

        user.value = SessionUser(TrainingFixtures.USER_ID, "Ana Deportista", "ana@example.com", ActorType.ATHLETE)
        assertEquals(setOf(owner), dao.rows.value.keys)

        user.value = null
        assertTrue(dao.rows.value.isEmpty())
        job.cancelAndJoin()
    }
}
