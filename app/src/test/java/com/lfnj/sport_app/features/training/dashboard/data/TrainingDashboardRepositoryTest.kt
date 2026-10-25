package com.lfnj.sport_app.features.training.dashboard.data

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.training.dashboard.data.local.TrainingDashboardCacheEntity
import com.lfnj.sport_app.features.training.dashboard.data.local.TrainingDashboardCodec
import com.lfnj.sport_app.features.training.dashboard.model.DashboardPeriod
import com.lfnj.sport_app.features.training.dashboard.model.DashboardRange
import com.lfnj.sport_app.features.training.dashboard.model.DashboardSourceStatus
import com.lfnj.sport_app.features.training.session.model.TrainingOwner
import com.lfnj.sport_app.testing.DashboardFixtures
import com.lfnj.sport_app.testing.FakeCipher
import com.lfnj.sport_app.testing.FakeTokenStore
import com.lfnj.sport_app.testing.InMemoryDashboardDao
import com.lfnj.sport_app.testing.MutableClock
import com.lfnj.sport_app.testing.SessionFixtures
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
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * HU033 contra un `bff-mobile` simulado con MockWebServer y la caché en memoria (cifrado de
 * prueba). Contrato simulado según ADR-043: **no** es un E2E.
 */
class TrainingDashboardRepositoryTest {
    private val server = MockWebServer().apply { start() }
    private val retrofit = BffMobileClient.retrofit(
        server.url("/").toString(),
        BffMobileClient.okHttp().newBuilder().retryOnConnectionFailure(false).build(),
    )
    private val sessionManager = SessionManager(retrofit.create(SessionApi::class.java), FakeTokenStore(), BffMobileClient.json, clock = { 1_000_000L })
    private val dao = InMemoryDashboardDao()
    private val cipher = FakeCipher()
    private val zone = ZoneId.of("America/Bogota")
    private val clock = MutableClock(Instant.parse("2026-10-25T15:00:00Z"), zone)
    private val repository = TrainingDashboardRepository(
        dao, TrainingDashboardCodec(cipher, BffMobileClient.json), retrofit.create(TrainingDashboardApi::class.java),
        sessionManager, BffMobileClient.json, clock,
    )
    private val owner = TrainingOwner.keyOf(TrainingFixtures.USER_ID)
    private val today = LocalDate.of(2026, 10, 25)
    private val last30 = DashboardPeriod.preset(DashboardRange.LAST_30, today, zone)

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
    fun `consulta el periodo con el token y los extremos con desfase y guarda el tablero confirmado (CA1)`() = runTest {
        login()
        respond(DashboardFixtures.body())

        assertEquals(DashboardRefresh.Updated, repository.refresh(owner, last30))

        val request = takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/me/training-dashboard", request.requestUrl?.encodedPath)
        assertEquals("2026-09-26T00:00:00-05:00", request.requestUrl?.queryParameter("from"))
        assertEquals("2026-10-26T00:00:00-05:00", request.requestUrl?.queryParameter("to"))
        assertEquals("Bearer ${SessionFixtures.ACCESS_TOKEN}", request.getHeader("Authorization"))

        val cached = checkNotNull(repository.observe(owner, DashboardRange.LAST_30).first())
        assertEquals(DashboardRange.LAST_30, cached.range)
        assertEquals(clock.instant, cached.syncedAt)
        assertTrue(cached.hasData)
        assertEquals(2L, cached.totals.sessionsCount)
        assertEquals(95L, cached.totals.totalDurationMinutes)
        assertEquals(48L, cached.totals.avgDurationMinutes)
        assertNull(cached.totals.distanceMeters)
        assertEquals(listOf("running", "cycling"), cached.bySport.map { it.sport })
        assertEquals(listOf(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 19)), cached.byWeek.map { it.weekStart })
        assertEquals(2L, cached.bySource.sportApp.sessionsCount)
        assertEquals(0L, cached.bySource.strava.sessionsCount)
        val ftp = cached.latestMetrics.single()
        assertEquals("FTP_ESTIMATED", ftp.indicator)
        assertEquals(BigDecimal("238"), ftp.value)
        assertEquals("W", ftp.unit)
        assertEquals("FTP_20_MIN_95_V1", ftp.methodCode)
        assertEquals(1, ftp.methodVersion)
        assertEquals(Instant.parse("2026-10-20T12:30:00Z"), ftp.measuredAt)
        assertEquals("estimated_not_diagnostic", ftp.disclaimerKey)
        assertTrue(cached.complete)
    }

    @Test
    fun `un desfase positivo viaja codificado y llega intacto al BFF (CA3)`() = runTest {
        login()
        val tokyo = DashboardPeriod.preset(DashboardRange.LAST_7, today, ZoneId.of("Asia/Tokyo"))
        respond(DashboardFixtures.body(from = "2026-10-19T00:00:00+09:00", to = "2026-10-26T00:00:00+09:00"))

        assertEquals(DashboardRefresh.Updated, repository.refresh(owner, tokyo))

        val url = checkNotNull(takeRequest().requestUrl)
        val query = checkNotNull(url.encodedQuery)
        // Un «+» sin codificar se interpretaría como espacio en la consulta.
        assertTrue(query, query.startsWith("from=2026-10-19T00%3A00%3A00%2B09%3A00&") && "+" !in query)
        assertEquals("2026-10-26T00:00:00+09:00", url.queryParameter("to"))
    }

    @Test
    fun `los decimales de un indicador se conservan exactos`() = runTest {
        login()
        respond(DashboardFixtures.body(metrics = listOf(DashboardFixtures.metric("VO2MAX_ESTIMATED", "47.25", "ml/kg/min", "COOPER_12_MIN_V1"))))

        repository.refresh(owner, last30)

        val metric = checkNotNull(repository.observe(owner, DashboardRange.LAST_30).first()).latestMetrics.single()
        assertEquals(BigDecimal("47.25"), metric.value)
        assertEquals("ml/kg/min", metric.unit)
    }

    @Test
    fun `cada opcion del selector tiene su propia fila y una nueva consulta la reemplaza`() = runTest {
        login()
        respond(DashboardFixtures.body())
        repository.refresh(owner, last30)
        respond(DashboardFixtures.body(from = "2026-10-19T00:00:00-05:00", to = "2026-10-26T00:00:00-05:00"))
        repository.refresh(owner, DashboardPeriod.preset(DashboardRange.LAST_7, today, zone))
        clock.instant = clock.instant.plusSeconds(60)
        respond(DashboardFixtures.emptyBody())

        repository.refresh(owner, last30)

        assertEquals(2, dao.rows.value.size)
        val month = checkNotNull(repository.observe(owner, DashboardRange.LAST_30).first())
        assertFalse(month.hasData)
        assertEquals(clock.instant, month.syncedAt)
        assertTrue(checkNotNull(repository.observe(owner, DashboardRange.LAST_7).first()).hasData)
        assertNull(repository.observe(owner, DashboardRange.LAST_90).first())
    }

    @Test
    fun `sin datos es un tablero confirmado vacio con ceros y listas vacias, no un error (CA2)`() = runTest {
        login()
        respond(DashboardFixtures.emptyBody())

        assertEquals(DashboardRefresh.Updated, repository.refresh(owner, last30))

        val cached = checkNotNull(repository.observe(owner, DashboardRange.LAST_30).first())
        assertTrue(cached.confirmedEmpty)
        assertEquals(0L, cached.totals.sessionsCount)
        assertTrue(cached.bySport.isEmpty() && cached.byWeek.isEmpty() && cached.latestMetrics.isEmpty())
    }

    @Test
    fun `una consulta interna caida llega como 200 y se conserva como incompleta`() = runTest {
        login()
        respond(DashboardFixtures.body(sources = listOf(DashboardFixtures.source("training_sessions"), DashboardFixtures.source("training_metrics", "UNAVAILABLE"))))

        assertEquals(DashboardRefresh.Updated, repository.refresh(owner, last30))

        val cached = checkNotNull(repository.observe(owner, DashboardRange.LAST_30).first())
        assertEquals(DashboardSourceStatus.UNAVAILABLE, cached.sources.single { it.name == "training_metrics" }.status)
        assertEquals(listOf("training_metrics"), cached.incompleteSources)
    }

    @Test
    fun `la distancia disponible se conserva por deporte y en el total`() = runTest {
        login()
        respond(
            DashboardFixtures.body(
                totals = """{"sessions_count":2,"total_duration_minutes":95,"avg_duration_minutes":48,"distance_meters":20500}""",
                bySport = listOf("""{"sport":"cycling","sessions_count":2,"total_duration_minutes":95,"distance_meters":20500}"""),
            )
        )

        repository.refresh(owner, last30)

        val cached = checkNotNull(repository.observe(owner, DashboardRange.LAST_30).first())
        assertEquals(20_500L, cached.totals.distanceMeters)
        assertEquals(20_500L, cached.bySport.single().distanceMeters)
    }

    // ----------------------------------------------------------------- errores (CA4)

    @Test
    fun `sin la capacidad del plan responde 403 capability_required`() = runTest {
        login()
        server.enqueue(SessionFixtures.error(403, "capability_required"))

        assertEquals(DashboardRefresh.CapabilityRequired, repository.refresh(owner, last30))
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun `otro actor recibe 403 permission_required (CA4)`() = runTest {
        login()
        server.enqueue(SessionFixtures.error(403, "permission_required"))

        assertEquals(DashboardRefresh.PermissionRequired, repository.refresh(owner, last30))
    }

    @Test
    fun `un periodo invalido o de mas de 366 dias recibe 422 con su causa (CA4)`() = runTest {
        login()
        respond(DashboardFixtures.validationError("to", "out_of_range"), 422)

        assertEquals(DashboardRefresh.InvalidPeriod(listOf("out_of_range")), repository.refresh(owner, last30))
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun `503 y 504 son temporales y conservan la cache anterior`() = runTest {
        login()
        respond(DashboardFixtures.body())
        repository.refresh(owner, last30)
        server.enqueue(SessionFixtures.error(503, "upstream_unavailable"))
        server.enqueue(SessionFixtures.error(504, "upstream_timeout"))

        assertTrue(repository.refresh(owner, last30) is DashboardRefresh.Unavailable)
        assertTrue(repository.refresh(owner, last30) is DashboardRefresh.Unavailable)
        assertTrue(checkNotNull(repository.observe(owner, DashboardRange.LAST_30).first()).hasData)
    }

    @Test
    fun `sin red conserva el tablero guardado`() = runTest {
        login()
        respond(DashboardFixtures.body())
        repository.refresh(owner, last30)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        assertEquals(DashboardRefresh.NoConnection, repository.refresh(owner, last30))
        assertTrue(checkNotNull(repository.observe(owner, DashboardRange.LAST_30).first()).hasData)
    }

    @Test
    fun `una respuesta mal formada o de otro periodo no se guarda`() = runTest {
        login()
        respond(DashboardFixtures.body(metrics = listOf(DashboardFixtures.metric(measuredAt = "ayer"))))
        assertEquals(DashboardRefresh.InvalidResponse, repository.refresh(owner, last30))

        respond(DashboardFixtures.body(metrics = listOf(DashboardFixtures.metric(value = "\"238\""))))
        assertEquals(DashboardRefresh.InvalidResponse, repository.refresh(owner, last30))

        respond(DashboardFixtures.body(byWeek = listOf("""{"week_start":"semana","sessions_count":1,"total_duration_minutes":45}""")))
        assertEquals(DashboardRefresh.InvalidResponse, repository.refresh(owner, last30))

        respond(DashboardFixtures.body(from = "2026-09-25T00:00:00-05:00"))
        assertEquals(DashboardRefresh.InvalidResponse, repository.refresh(owner, last30))

        respond("""{"period":{"from":"2026-09-26T00:00:00-05:00"}}""")
        assertEquals(DashboardRefresh.InvalidResponse, repository.refresh(owner, last30))

        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun `si el tablero no puede guardarse se informa sin perder el anterior`() = runTest {
        login()
        respond(DashboardFixtures.body())
        repository.refresh(owner, last30)
        dao.failWrites = true
        respond(DashboardFixtures.emptyBody())

        assertEquals(DashboardRefresh.StorageFailed, repository.refresh(owner, last30))
        assertTrue(checkNotNull(repository.observe(owner, DashboardRange.LAST_30).first()).hasData)
    }

    @Test
    fun `sin sesion no se consulta y se informa SignedOut`() = runTest {
        assertEquals(DashboardRefresh.SignedOut, repository.refresh(owner, last30))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `sin URL del BFF el tablero no esta disponible`() = runTest {
        val offline = TrainingDashboardRepository(dao, TrainingDashboardCodec(cipher, BffMobileClient.json), null, sessionManager, BffMobileClient.json, clock)

        assertEquals(DashboardRefresh.Unavailable(null), offline.refresh(owner, last30))
    }

    // ----------------------------------------------------------------- caché cifrada

    @Test
    fun `la cache solo guarda la particion y el periodo en claro`() = runTest {
        login()
        respond(DashboardFixtures.body())
        repository.refresh(owner, last30)

        val entity: TrainingDashboardCacheEntity = dao.rows.value.values.single()
        assertEquals(owner, entity.ownerKey)
        assertEquals("LAST_30", entity.periodKey)
        val stored = String(entity.payload, Charsets.UTF_8)
        listOf("FTP_ESTIMATED", "running", "training_sessions", "last_synced_at").forEach { assertFalse("«$it» en claro", stored.contains(it)) }
    }

    @Test
    fun `una cache ilegible se trata como inexistente`() = runTest {
        login()
        respond(DashboardFixtures.body())
        repository.refresh(owner, last30)
        cipher.broken = true

        assertNull(repository.observe(owner, DashboardRange.LAST_30).first())
    }

    @Test
    fun `cada deportista solo ve su particion`() = runTest {
        login()
        respond(DashboardFixtures.body())
        repository.refresh(owner, last30)

        assertNull(repository.observe(TrainingOwner.keyOf("0f0e0d0c-0b0a-4908-8706-050403020100"), DashboardRange.LAST_30).first())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `el cierre de sesion borra la cache y otro deportista no hereda la anterior`() = runTest {
        val other = TrainingOwner.keyOf("0f0e0d0c-0b0a-4908-8706-050403020100")
        val codec = TrainingDashboardCodec(cipher, BffMobileClient.json)
        dao.replace(TrainingDashboardCacheEntity(owner, "LAST_30", codec.encode(DashboardFixtures.snapshot())))
        dao.replace(TrainingDashboardCacheEntity(other, "LAST_7", codec.encode(DashboardFixtures.snapshot(range = DashboardRange.LAST_7))))
        val ana = SessionUser(TrainingFixtures.USER_ID, "Ana Deportista", "ana@example.com", ActorType.ATHLETE)
        val user = MutableStateFlow<SessionUser?>(ana)
        val job = TrainingDashboardCacheJanitor(user, repository).start(TestScope(UnconfinedTestDispatcher(testScheduler)))

        advanceUntilIdle()
        assertEquals(setOf(owner to "LAST_30"), dao.rows.value.keys)

        user.value = null
        advanceUntilIdle()
        assertTrue(dao.rows.value.isEmpty())
        job.cancelAndJoin()
    }
}
