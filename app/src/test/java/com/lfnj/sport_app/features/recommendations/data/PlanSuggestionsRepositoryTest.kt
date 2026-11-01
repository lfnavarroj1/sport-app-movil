package com.lfnj.sport_app.features.recommendations.data

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.features.recommendations.data.local.PlanSuggestionsCacheEntity
import com.lfnj.sport_app.features.recommendations.data.local.PlanSuggestionsCodec
import com.lfnj.sport_app.features.recommendations.model.ContextSourceStatus
import com.lfnj.sport_app.features.recommendations.model.MissingContext
import com.lfnj.sport_app.features.recommendations.model.PlanSuggestionsOwner
import com.lfnj.sport_app.features.recommendations.model.SuggestionReason
import com.lfnj.sport_app.testing.FakeCipher
import com.lfnj.sport_app.testing.FakeTokenStore
import com.lfnj.sport_app.testing.InMemoryPlanSuggestionsDao
import com.lfnj.sport_app.testing.MutableClock
import com.lfnj.sport_app.testing.PlanSuggestionsFixtures
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
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * HU028 contra un `bff-mobile` simulado con MockWebServer y la caché en memoria (cifrado de
 * prueba). Contrato simulado según `bff-mobile` 0.15.0: **no** es un E2E.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlanSuggestionsRepositoryTest {
    private val server = MockWebServer().apply { start() }
    private val retrofit = BffMobileClient.retrofit(
        server.url("/").toString(),
        BffMobileClient.okHttp().newBuilder().retryOnConnectionFailure(false).build(),
    )
    private val sessionManager = SessionManager(retrofit.create(SessionApi::class.java), FakeTokenStore(), BffMobileClient.json, clock = { 1_000_000L })
    private val dao = InMemoryPlanSuggestionsDao()
    private val cipher = FakeCipher()
    private val clock = MutableClock(Instant.parse("2026-10-25T15:00:00Z"), ZoneId.of("America/Bogota"))
    private val repository = PlanSuggestionsRepository(
        dao, PlanSuggestionsCodec(cipher, BffMobileClient.json), retrofit.create(PlanSuggestionsApi::class.java),
        sessionManager, BffMobileClient.json, clock,
    )
    private val owner = PlanSuggestionsOwner.keyOf(TrainingFixtures.USER_ID)

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
    fun `consulta con el token y el limite y guarda la lista confirmada en el orden del BFF (CA1)`() = runTest {
        login()
        respond(PlanSuggestionsFixtures.body())

        assertEquals(PlanSuggestionsRefresh.Updated, repository.refresh(owner))

        val request = takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/me/suggestions/training-plan-templates", request.requestUrl?.encodedPath)
        assertEquals("20", request.requestUrl?.queryParameter("limit"))
        assertEquals("Bearer ${SessionFixtures.ACCESS_TOKEN}", request.getHeader("Authorization"))

        val cached = checkNotNull(repository.observe(owner).first())
        assertTrue(cached.personalized)
        assertEquals(emptyList<String>(), cached.missingContext)
        assertEquals(ContextSourceStatus.OK, cached.usersSource)
        assertEquals(Instant.parse("2026-10-25T13:00:00Z"), cached.generatedAt)
        assertEquals(clock.instant, cached.syncedAt)
        // Primero la del objetivo, como la ordenó el BFF: el cliente no reordena.
        assertEquals(listOf(PlanSuggestionsFixtures.GOAL_TEMPLATE_ID, PlanSuggestionsFixtures.SPORT_TEMPLATE_ID), cached.items.map { it.templateId })
        assertEquals(listOf(SuggestionReason.SPORT_MATCH, SuggestionReason.GOAL_MATCH), cached.items[0].knownReasons)
        assertEquals(listOf(SuggestionReason.SPORT_MATCH), cached.items[1].knownReasons)
    }

    @Test
    fun `cada plantilla conserva autor, duracion, nivel, sesiones semanales y motivo (CA4)`() = runTest {
        login()
        respond(PlanSuggestionsFixtures.body())
        repository.refresh(owner)

        val item = checkNotNull(repository.observe(owner).first()).items[0]
        assertEquals("Laura Gómez", item.authorName)
        assertEquals(PlanSuggestionsFixtures.PROFESSIONAL_ID, item.professionalId)
        assertEquals(8, item.durationWeeks)
        assertEquals("BEGINNER", item.level)
        assertEquals("running", item.sport)
        assertEquals("PERFORMANCE", item.goal)
        assertEquals(Instant.parse("2026-10-20T14:00:00Z"), item.publishedAt)
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SATURDAY), item.weeklySessions.map { it.day })
        assertEquals(listOf(45, 30, 60), item.weeklySessions.map { it.durationMinutes })
        assertEquals("Fondo largo", item.weeklySessions[2].description)
    }

    @Test
    fun `sin respuesta de Usuarios el autor llega nulo y no se inventa`() = runTest {
        login()
        respond(PlanSuggestionsFixtures.body(items = listOf(PlanSuggestionsFixtures.item(authorName = null))))

        assertEquals(PlanSuggestionsRefresh.Updated, repository.refresh(owner))
        assertNull(checkNotNull(repository.observe(owner).first()).items.single().authorName)
    }

    @Test
    fun `sin deportes la lista no personalizada declara missing_context y no trae motivos (CA2)`() = runTest {
        login()
        respond(PlanSuggestionsFixtures.notPersonalized(listOf("sports")))

        assertEquals(PlanSuggestionsRefresh.Updated, repository.refresh(owner))
        val cached = checkNotNull(repository.observe(owner).first())
        assertFalse(cached.personalized)
        assertEquals(listOf(MissingContext.SPORTS), cached.knownMissingContext)
        assertTrue(cached.items.single().reasons.isEmpty())
        assertFalse(cached.contextUnavailable)
    }

    @Test
    fun `con Usuarios caido la lista no es personalizada y se marca la fuente no disponible (CA2)`() = runTest {
        login()
        respond(PlanSuggestionsFixtures.notPersonalized(usersStatus = "UNAVAILABLE"))

        repository.refresh(owner)
        val cached = checkNotNull(repository.observe(owner).first())
        assertFalse(cached.personalized)
        assertTrue(cached.contextUnavailable)
        assertEquals(listOf(MissingContext.SPORTS, MissingContext.PRIMARY_GOAL), cached.knownMissingContext)
    }

    @Test
    fun `con deportes y sin objetivo sigue personalizada por deporte con missing_context primary_goal`() = runTest {
        login()
        respond(PlanSuggestionsFixtures.body(missingContext = listOf("primary_goal")))

        repository.refresh(owner)
        val cached = checkNotNull(repository.observe(owner).first())
        assertTrue(cached.personalized)
        assertEquals(listOf(MissingContext.PRIMARY_GOAL), cached.knownMissingContext)
    }

    @Test
    fun `una nueva consulta reemplaza la lista y una plantilla retirada deja de mostrarse (CA3)`() = runTest {
        login()
        respond(PlanSuggestionsFixtures.body())
        repository.refresh(owner)
        respond(PlanSuggestionsFixtures.body(items = listOf(PlanSuggestionsFixtures.item(templateId = PlanSuggestionsFixtures.SPORT_TEMPLATE_ID))))
        clock.instant = clock.instant.plusSeconds(60)

        repository.refresh(owner)

        val cached = checkNotNull(repository.observe(owner).first())
        assertEquals(listOf(PlanSuggestionsFixtures.SPORT_TEMPLATE_ID), cached.items.map { it.templateId })
        assertNull(cached.find(PlanSuggestionsFixtures.GOAL_TEMPLATE_ID))
        assertEquals(clock.instant, cached.syncedAt)
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun `sin plantillas es una lista confirmada vacia, no un error`() = runTest {
        login()
        respond(PlanSuggestionsFixtures.body(items = emptyList()))

        assertEquals(PlanSuggestionsRefresh.Updated, repository.refresh(owner))
        assertTrue(checkNotNull(repository.observe(owner).first()).items.isEmpty())
    }

    @Test
    fun `codigos futuros de motivo o contexto se conservan sin interpretarse`() = runTest {
        login()
        respond(
            PlanSuggestionsFixtures.body(
                items = listOf(PlanSuggestionsFixtures.item(reasons = listOf("SPORT_MATCH", "LEVEL_MATCH"))),
                missingContext = listOf("primary_goal", "city"),
            )
        )

        repository.refresh(owner)
        val cached = checkNotNull(repository.observe(owner).first())
        assertEquals(listOf("SPORT_MATCH", "LEVEL_MATCH"), cached.items.single().reasons)
        assertEquals(listOf(SuggestionReason.SPORT_MATCH), cached.items.single().knownReasons)
        assertEquals(listOf("primary_goal", "city"), cached.missingContext)
    }

    // ----------------------------------------------------------------- errores

    @Test
    fun `sin la capacidad el BFF responde 403 capability_required`() = runTest {
        login()
        server.enqueue(SessionFixtures.error(403, "capability_required"))

        assertEquals(PlanSuggestionsRefresh.CapabilityRequired, repository.refresh(owner))
    }

    @Test
    fun `un actor distinto de deportista recibe 403 permission_required`() = runTest {
        login()
        server.enqueue(SessionFixtures.error(403, "permission_required"))

        assertEquals(PlanSuggestionsRefresh.PermissionRequired, repository.refresh(owner))
    }

    @Test
    fun `un limite rechazado con 422 se informa sin datos`() = runTest {
        login()
        respond(PlanSuggestionsFixtures.validationError(), 422)

        assertEquals(PlanSuggestionsRefresh.InvalidRequest, repository.refresh(owner))
        assertNull(repository.observe(owner).first())
    }

    @Test
    fun `503 y 504 no reemplazan la cache`() = runTest {
        login()
        respond(PlanSuggestionsFixtures.body())
        repository.refresh(owner)

        server.enqueue(SessionFixtures.error(503, "upstream_unavailable"))
        assertEquals(PlanSuggestionsRefresh.Unavailable("8c1f2d3e-0000-4000-8000-000000000001"), repository.refresh(owner))
        server.enqueue(SessionFixtures.error(504, "upstream_timeout"))
        assertTrue(repository.refresh(owner) is PlanSuggestionsRefresh.Unavailable)

        assertEquals(2, checkNotNull(repository.observe(owner).first()).items.size)
    }

    @Test
    fun `un 401 renueva la sesion una vez y repite la consulta`() = runTest {
        login()
        server.enqueue(SessionFixtures.error(401, "invalid_access_token"))
        server.enqueue(SessionFixtures.ok(SessionFixtures.sessionBody(accessToken = "access-token-sintetico-2", refreshToken = SessionFixtures.ROTATED_REFRESH_TOKEN)))
        respond(PlanSuggestionsFixtures.body())

        assertEquals(PlanSuggestionsRefresh.Updated, repository.refresh(owner))
        takeRequest()
        assertEquals("/v1/sessions/refresh", takeRequest().path)
        assertEquals("Bearer access-token-sintetico-2", takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `sin sesion no consulta y pide volver al acceso`() = runTest {
        assertEquals(PlanSuggestionsRefresh.SignedOut, repository.refresh(owner))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `sin red conserva la cache y lo informa`() = runTest {
        login()
        respond(PlanSuggestionsFixtures.body())
        repository.refresh(owner)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        assertEquals(PlanSuggestionsRefresh.NoConnection, repository.refresh(owner))
        assertEquals(2, checkNotNull(repository.observe(owner).first()).items.size)
    }

    @Test
    fun `sin URL del BFF configurada no consulta`() = runTest {
        val unconfigured = PlanSuggestionsRepository(dao, PlanSuggestionsCodec(cipher, BffMobileClient.json), null, sessionManager, BffMobileClient.json, clock)

        assertEquals(PlanSuggestionsRefresh.Unavailable(null), unconfigured.refresh(owner))
    }

    @Test
    fun `una respuesta fuera de contrato no se guarda ni se muestra parcialmente`() = runTest {
        login()
        respond(PlanSuggestionsFixtures.body(items = listOf(PlanSuggestionsFixtures.item(sessions = listOf(PlanSuggestionsFixtures.session(day = "LUNES"))))))
        assertEquals(PlanSuggestionsRefresh.InvalidResponse, repository.refresh(owner))

        respond(PlanSuggestionsFixtures.body(items = listOf(PlanSuggestionsFixtures.item(publishedAt = "ayer"))))
        assertEquals(PlanSuggestionsRefresh.InvalidResponse, repository.refresh(owner))

        respond(PlanSuggestionsFixtures.body(items = listOf(PlanSuggestionsFixtures.item(referenceType = "training_plan"))))
        assertEquals(PlanSuggestionsRefresh.InvalidResponse, repository.refresh(owner))

        respond("""{"items":[],"personalized":true}""")
        assertEquals(PlanSuggestionsRefresh.InvalidResponse, repository.refresh(owner))

        assertNull(repository.observe(owner).first())
    }

    @Test
    fun `si no se puede guardar se informa sin perder la cache anterior`() = runTest {
        login()
        respond(PlanSuggestionsFixtures.body())
        repository.refresh(owner)
        dao.failWrites = true
        respond(PlanSuggestionsFixtures.body(items = emptyList()))

        assertEquals(PlanSuggestionsRefresh.StorageFailed, repository.refresh(owner))
        assertEquals(2, checkNotNull(repository.observe(owner).first()).items.size)
    }

    // ----------------------------------------------------------------- caché cifrada

    @Test
    fun `la cache solo guarda texto cifrado y la particion`() = runTest {
        login()
        respond(PlanSuggestionsFixtures.body())
        repository.refresh(owner)

        val row = dao.rows.value.getValue(owner)
        val stored = row.payload.toString(Charsets.ISO_8859_1)
        listOf("Base aeróbica", "Laura", "Rodaje", PlanSuggestionsFixtures.GOAL_TEMPLATE_ID).forEach { assertFalse(it, it in stored) }
        assertFalse(TrainingFixtures.USER_ID in row.ownerKey)
    }

    @Test
    fun `una cache ilegible se trata como inexistente y no inventa plantillas`() = runTest {
        dao.replace(PlanSuggestionsCacheEntity(owner, byteArrayOf(1, 2, 3)))
        assertNull(repository.observe(owner).first())

        login()
        respond(PlanSuggestionsFixtures.body())
        repository.refresh(owner)
        cipher.broken = true
        assertNull(repository.observe(owner).first())
    }

    @Test
    fun `la cache conserva la antiguedad y se marca desactualizada a las 24 horas`() = runTest {
        login()
        respond(PlanSuggestionsFixtures.body())
        repository.refresh(owner)
        val cached = checkNotNull(repository.observe(owner).first())

        assertFalse(cached.isStale(clock.instant.plusSeconds(24 * 3600)))
        assertTrue(cached.isStale(clock.instant.plusSeconds(24 * 3600 + 1)))
    }

    @Test
    fun `cada deportista solo ve su particion y la limpieza respeta las demas`() = runTest {
        login()
        respond(PlanSuggestionsFixtures.body())
        repository.refresh(owner)
        val other = PlanSuggestionsOwner.keyOf(TrainingFixtures.OTHER_USER_ID)

        assertNull(repository.observe(other).first())
        repository.clearAllExcept(other)
        assertNull(repository.observe(owner).first())
    }

    // ----------------------------------------------------------------- limpieza al cerrar sesión

    @Test
    fun `el limpiador borra la cache al cerrar sesion y la de otros deportistas al entrar`() = runTest {
        val user = MutableStateFlow<SessionUser?>(null)
        val other = PlanSuggestionsOwner.keyOf(TrainingFixtures.OTHER_USER_ID)
        dao.replace(PlanSuggestionsCacheEntity(other, byteArrayOf(9)))
        dao.replace(PlanSuggestionsCacheEntity(owner, byteArrayOf(7)))
        val job = PlanSuggestionsCacheJanitor(user, repository).start(TestScope(UnconfinedTestDispatcher(testScheduler)))
        assertEquals(2, dao.rows.value.size)

        user.value = SessionUser(TrainingFixtures.USER_ID, "Ana Deportista", "ana@example.com", ActorType.ATHLETE)
        assertEquals(setOf(owner), dao.rows.value.keys)

        user.value = null
        assertTrue(dao.rows.value.isEmpty())
        job.cancelAndJoin()
    }
}
