package com.lfnj.sport_app.features.calendar.data

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.features.calendar.data.local.CalendarCacheCodec
import com.lfnj.sport_app.features.calendar.data.local.CalendarCacheEntity
import com.lfnj.sport_app.features.calendar.model.CalendarItem
import com.lfnj.sport_app.features.calendar.model.CalendarOwner
import com.lfnj.sport_app.features.calendar.model.CalendarPeriod
import com.lfnj.sport_app.features.calendar.model.SourceStatus
import com.lfnj.sport_app.testing.CalendarFixtures
import com.lfnj.sport_app.testing.FakeCipher
import com.lfnj.sport_app.testing.FakeTokenStore
import com.lfnj.sport_app.testing.InMemoryCalendarDao
import com.lfnj.sport_app.testing.MutableClock
import com.lfnj.sport_app.testing.SessionFixtures
import com.lfnj.sport_app.testing.TrainingFixtures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
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
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * HU035 contra un `bff-mobile` simulado con MockWebServer y la caché en memoria (cifrado de
 * prueba). Contrato simulado según ADR-031: **no** es un E2E.
 */
class CalendarRepositoryTest {
    private val server = MockWebServer().apply { start() }
    private val retrofit = BffMobileClient.retrofit(
        server.url("/").toString(),
        BffMobileClient.okHttp().newBuilder().retryOnConnectionFailure(false).build(),
    )
    private val sessionManager = SessionManager(retrofit.create(SessionApi::class.java), FakeTokenStore(), BffMobileClient.json, clock = { 1_000_000L })
    private val dao = InMemoryCalendarDao()
    private val cipher = FakeCipher()
    private val clock = MutableClock(Instant.parse("2026-11-10T15:00:00Z"), ZoneId.of("America/Bogota"))
    private val repository = CalendarRepository(
        dao, CalendarCacheCodec(cipher, BffMobileClient.json), retrofit.create(CalendarApi::class.java),
        sessionManager, BffMobileClient.json, clock,
    )
    private val owner = CalendarOwner.keyOf(TrainingFixtures.USER_ID)
    private val november = CalendarPeriod.forMonth(YearMonth.of(2026, 11), ZoneId.of("America/Bogota"))

    @After
    fun tearDown() = server.shutdown()

    private suspend fun login() {
        server.enqueue(SessionFixtures.ok(SessionFixtures.sessionBody()))
        sessionManager.login("ana@example.com", "Secreta-123")
        server.takeRequest()
    }

    private fun takeRequest(): RecordedRequest = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS)) { "No llegó la solicitud esperada" }

    private fun respond(body: String, status: Int = 200) = server.enqueue(MockResponse().setResponseCode(status).setBody(body))

    // ----------------------------------------------------------------- consulta (CA1, CA4)

    @Test
    fun `consulta el mes con el token y los extremos con desfase y guarda el periodo confirmado`() = runTest {
        login()
        respond(CalendarFixtures.body())

        assertEquals(CalendarRefresh.Updated, repository.refresh(owner, november))

        val request = takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/me/calendar", request.requestUrl?.encodedPath)
        assertEquals("2026-11-01T00:00:00-05:00", request.requestUrl?.queryParameter("from"))
        assertEquals("2026-11-30T23:59:59-05:00", request.requestUrl?.queryParameter("to"))
        assertEquals("Bearer ${SessionFixtures.ACCESS_TOKEN}", request.getHeader("Authorization"))

        val cached = checkNotNull(repository.observe(owner).first())
        assertEquals(YearMonth.of(2026, 11), cached.month)
        assertEquals(clock.instant, cached.syncedAt)
        assertEquals(listOf(CalendarFixtures.EVENT_ITEM_ID, CalendarFixtures.SESSION_ITEM_ID), cached.items.map { it.itemId })
        val event = cached.items[0] as CalendarItem.Event
        assertEquals(Instant.parse("2026-11-15T12:00:00Z"), event.startsAt)
        assertEquals("America/Bogota", event.timeZone)
        val session = cached.items[1] as CalendarItem.PlannedSession
        assertEquals(LocalDate.of(2026, 11, 16), session.occursOn)
        assertEquals(45, session.durationMinutes)
        assertTrue(cached.complete)
    }

    @Test
    fun `un desfase positivo viaja codificado y llega intacto al BFF`() = runTest {
        login()
        respond(CalendarFixtures.body())

        repository.refresh(owner, CalendarPeriod.forMonth(YearMonth.of(2026, 11), ZoneId.of("Europe/Madrid")))

        val url: HttpUrl = checkNotNull(takeRequest().requestUrl)
        // Un «+» sin codificar se interpretaría como espacio en la consulta.
        val query = checkNotNull(url.encodedQuery)
        assertTrue(query, query.startsWith("from=2026-11-01T00%3A00%3A00%2B01%3A00&") && "+" !in query)
        assertEquals("2026-11-01T00:00:00+01:00", url.queryParameter("from"))
    }

    @Test
    fun `una nueva consulta reemplaza el periodo anterior (CA3)`() = runTest {
        login()
        respond(CalendarFixtures.body(items = listOf(CalendarFixtures.event())))
        repository.refresh(owner, november)
        respond(CalendarFixtures.body(items = listOf(CalendarFixtures.event(), CalendarFixtures.session())))
        clock.instant = clock.instant.plusSeconds(60)

        repository.refresh(owner, november)

        val cached = checkNotNull(repository.observe(owner).first())
        assertEquals(2, cached.items.size)
        assertEquals(clock.instant, cached.syncedAt)
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun `sin elementos es un periodo confirmado vacio, no un error (CA2)`() = runTest {
        login()
        respond(CalendarFixtures.body(items = emptyList()))

        assertEquals(CalendarRefresh.Updated, repository.refresh(owner, november))
        assertTrue(checkNotNull(repository.observe(owner).first()).confirmedEmpty)
    }

    @Test
    fun `una fuente caida llega como 200 parcial y se conserva como incompleta (CA7)`() = runTest {
        login()
        respond(
            CalendarFixtures.body(
                items = listOf(CalendarFixtures.event()),
                sources = listOf(CalendarFixtures.source("events"), CalendarFixtures.source("training", "UNAVAILABLE")),
            )
        )

        assertEquals(CalendarRefresh.Updated, repository.refresh(owner, november))

        val cached = checkNotNull(repository.observe(owner).first())
        assertEquals(listOf("training"), cached.incompleteSources)
        assertEquals(SourceStatus.UNAVAILABLE, cached.sources.single { it.name == "training" }.status)
        assertFalse(cached.confirmedEmpty)
    }

    @Test
    fun `una fuente truncada se conserva para sugerir acotar el periodo`() = runTest {
        login()
        respond(CalendarFixtures.body(sources = listOf(CalendarFixtures.source("events", truncated = true), CalendarFixtures.source("training"))))

        repository.refresh(owner, november)

        assertEquals(listOf("events"), checkNotNull(repository.observe(owner).first()).truncatedSources)
    }

    @Test
    fun `una fuente futura se muestra sin inventar su significado`() = runTest {
        login()
        val registration = """{"item_id":"EVENT_REGISTRATION:1","source":"EVENT_REGISTRATION","occurs_on":"2026-11-20","title":"Inscripción"}"""
        respond(CalendarFixtures.body(items = listOf(registration)))

        repository.refresh(owner, november)

        val item = checkNotNull(repository.observe(owner).first()).items.single() as CalendarItem.Other
        assertEquals("EVENT_REGISTRATION", item.source)
    }

    // ----------------------------------------------------------------- errores del contrato

    @Test
    fun `sin la capacidad el BFF responde 403 capability_required (CA5)`() = runTest {
        login()
        server.enqueue(SessionFixtures.error(403, "capability_required"))

        assertEquals(CalendarRefresh.CapabilityRequired, repository.refresh(owner, november))
        assertNull(repository.observe(owner).first())
    }

    @Test
    fun `un actor distinto de deportista recibe 403 permission_required`() = runTest {
        login()
        server.enqueue(SessionFixtures.error(403, "permission_required"))

        assertEquals(CalendarRefresh.PermissionRequired, repository.refresh(owner, november))
    }

    @Test
    fun `un periodo invalido se rechaza con 422 y su causa estable (CA6)`() = runTest {
        login()
        respond(CalendarFixtures.validationError("out_of_range"), status = 422)

        assertEquals(CalendarRefresh.InvalidPeriod(listOf("out_of_range")), repository.refresh(owner, november))
    }

    @Test
    fun `con ambas fuentes caidas 503 o 504 no reemplazan la cache (CA7)`() = runTest {
        login()
        respond(CalendarFixtures.body())
        repository.refresh(owner, november)
        val before = repository.observe(owner).first()

        server.enqueue(SessionFixtures.error(503, "upstream_unavailable"))
        assertEquals(CalendarRefresh.Unavailable("8c1f2d3e-0000-4000-8000-000000000001"), repository.refresh(owner, november))
        server.enqueue(SessionFixtures.error(504, "upstream_timeout"))
        assertTrue(repository.refresh(owner, november) is CalendarRefresh.Unavailable)

        assertEquals(before, repository.observe(owner).first())
    }

    @Test
    fun `un 401 renueva la sesion una vez y repite la consulta`() = runTest {
        login()
        server.enqueue(SessionFixtures.error(401, "invalid_access_token"))
        server.enqueue(SessionFixtures.ok(SessionFixtures.sessionBody(accessToken = "access-token-sintetico-2", refreshToken = SessionFixtures.ROTATED_REFRESH_TOKEN)))
        respond(CalendarFixtures.body())

        assertEquals(CalendarRefresh.Updated, repository.refresh(owner, november))
        takeRequest()
        assertEquals("/v1/sessions/refresh", takeRequest().path)
        assertEquals("Bearer access-token-sintetico-2", takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `sin sesion no consulta y pide volver al acceso`() = runTest {
        assertEquals(CalendarRefresh.SignedOut, repository.refresh(owner, november))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `sin red conserva la cache y lo informa (CA8)`() = runTest {
        login()
        respond(CalendarFixtures.body())
        repository.refresh(owner, november)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        assertEquals(CalendarRefresh.NoConnection, repository.refresh(owner, november))
        assertEquals(2, checkNotNull(repository.observe(owner).first()).items.size)
    }

    @Test
    fun `una respuesta fuera de contrato no se guarda ni se muestra parcialmente`() = runTest {
        login()
        val eventWithoutStart = CalendarFixtures.event().replace(""""starts_at":"2026-11-15T12:00:00Z",""", "")
        respond(CalendarFixtures.body(items = listOf(CalendarFixtures.session(), eventWithoutStart)))

        assertEquals(CalendarRefresh.InvalidResponse, repository.refresh(owner, november))
        respond(CalendarFixtures.body(from = "ayer"))
        assertEquals(CalendarRefresh.InvalidResponse, repository.refresh(owner, november))
        assertNull(repository.observe(owner).first())
    }

    @Test
    fun `si no se puede guardar se informa sin perder la cache anterior`() = runTest {
        login()
        dao.failWrites = true
        respond(CalendarFixtures.body())

        assertEquals(CalendarRefresh.StorageFailed, repository.refresh(owner, november))
    }

    // ----------------------------------------------------------------- caché local

    @Test
    fun `la cache solo guarda texto cifrado y la particion`() = runTest {
        login()
        respond(CalendarFixtures.body())
        repository.refresh(owner, november)

        val row = dao.rows.value.getValue(owner)
        val text = String(row.payload, Charsets.UTF_8)
        listOf("Carrera 10K", "Base aeróbica", "2026-11", "Bogota", "Parque").forEach { assertFalse("«$it» en claro", text.contains(it)) }
    }

    @Test
    fun `una cache ilegible se trata como inexistente y no inventa elementos`() = runTest {
        dao.replace(CalendarCacheEntity(owner, byteArrayOf(1, 2, 3)))
        assertNull(repository.observe(owner).first())

        login()
        respond(CalendarFixtures.body())
        repository.refresh(owner, november)
        cipher.broken = true
        assertNull(repository.observe(owner).first())
    }

    @Test
    fun `cada deportista solo ve su particion y la limpieza respeta las demas`() = runTest {
        login()
        respond(CalendarFixtures.body())
        repository.refresh(owner, november)
        val other = CalendarOwner.keyOf(TrainingFixtures.OTHER_USER_ID)
        dao.replace(CalendarCacheEntity(other, dao.rows.value.getValue(owner).payload))

        assertNull(repository.observe(CalendarOwner.keyOf("ffffffff-0000-4000-8000-000000000000")).first())
        repository.clearAllExcept(owner)
        assertEquals(setOf(owner), dao.rows.value.keys)
        repository.clear(owner)
        assertNull(repository.observe(owner).first())
    }
}
