package com.lfnj.sport_app.features.nutrition.routines.data

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.nutrition.routines.data.local.NutritionRoutinesCacheEntity
import com.lfnj.sport_app.features.nutrition.routines.data.local.NutritionRoutinesCodec
import com.lfnj.sport_app.features.nutrition.routines.model.NutritionRoutinesOwner
import com.lfnj.sport_app.features.nutrition.routines.model.RoutineFood
import com.lfnj.sport_app.features.nutrition.routines.model.RoutineMissingContext
import com.lfnj.sport_app.features.nutrition.routines.model.RoutineReason
import com.lfnj.sport_app.features.nutrition.routines.model.RoutineSourceStatus
import com.lfnj.sport_app.testing.FakeCipher
import com.lfnj.sport_app.testing.FakeTokenStore
import com.lfnj.sport_app.testing.InMemoryNutritionRoutinesDao
import com.lfnj.sport_app.testing.MutableClock
import com.lfnj.sport_app.testing.NutritionRoutinesFixtures
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
 * HU030 contra un `bff-mobile` simulado con MockWebServer y la caché en memoria (cifrado de
 * prueba). Contrato simulado según `bff-mobile` 0.21.0: **no** es un E2E.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NutritionRoutinesRepositoryTest {
    private val server = MockWebServer().apply { start() }
    private val retrofit = BffMobileClient.retrofit(
        server.url("/").toString(),
        BffMobileClient.okHttp().newBuilder().retryOnConnectionFailure(false).build(),
    )
    private val sessionManager = SessionManager(retrofit.create(SessionApi::class.java), FakeTokenStore(), BffMobileClient.json, clock = { 1_000_000L })
    private val dao = InMemoryNutritionRoutinesDao()
    private val cipher = FakeCipher()
    private val clock = MutableClock(Instant.parse("2026-10-27T15:00:00Z"), ZoneId.of("America/Bogota"))
    private val repository = NutritionRoutinesRepository(
        dao, NutritionRoutinesCodec(cipher, BffMobileClient.json), retrofit.create(NutritionRoutinesApi::class.java),
        sessionManager, BffMobileClient.json, clock,
    )
    private val owner = NutritionRoutinesOwner.keyOf(TrainingFixtures.USER_ID)

    @After
    fun tearDown() = server.shutdown()

    private suspend fun login() {
        server.enqueue(SessionFixtures.ok(SessionFixtures.sessionBody()))
        sessionManager.login("ana@example.com", "Secreta-123")
        server.takeRequest()
    }

    private fun takeRequest(): RecordedRequest = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS)) { "No llegó la solicitud esperada" }

    private fun respond(body: String, status: Int = 200) = server.enqueue(MockResponse().setResponseCode(status).setBody(body))

    // ----------------------------------------------------------------- consulta (CA1, CA2, CA4)

    @Test
    fun `consulta con el token y el limite y guarda la lista confirmada en el orden del BFF (CA1)`() = runTest {
        login()
        respond(NutritionRoutinesFixtures.body())

        assertEquals(NutritionRoutinesRefresh.Updated, repository.refresh(owner))

        val request = takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/me/suggestions/nutrition-routines", request.requestUrl?.encodedPath)
        assertEquals("20", request.requestUrl?.queryParameter("limit"))
        // El cliente no envía restricciones ni alergias: el BFF las obtiene de Usuarios (ADR-041 §3).
        assertEquals(setOf("limit"), request.requestUrl?.queryParameterNames)
        assertEquals("Bearer ${SessionFixtures.ACCESS_TOKEN}", request.getHeader("Authorization"))

        val cached = checkNotNull(repository.observe(owner).first())
        assertTrue(cached.personalized)
        assertFalse(cached.restrictionsNotVerified)
        assertEquals(listOf("NOT_MEDICAL_ADVICE"), cached.notices)
        assertEquals(RoutineSourceStatus.OK, cached.usersSource)
        assertEquals(Instant.parse("2026-10-27T13:00:00Z"), cached.generatedAt)
        assertEquals(clock.instant, cached.syncedAt)
        // Primero la del objetivo, como la ordenó el BFF: el cliente no reordena ni filtra.
        assertEquals(listOf(NutritionRoutinesFixtures.GOAL_TEMPLATE_ID, NutritionRoutinesFixtures.SPORT_TEMPLATE_ID), cached.items.map { it.templateId })
        assertEquals(listOf(RoutineReason.RESTRICTION_COMPATIBLE, RoutineReason.GOAL_MATCH, RoutineReason.SPORT_MATCH), cached.items[0].knownReasons)
        assertEquals(listOf(RoutineReason.RESTRICTION_COMPATIBLE), cached.items[1].knownReasons)
    }

    @Test
    fun `cada rutina conserva autor, vigencia, patron, alergenos, comidas y motivo (CA4)`() = runTest {
        login()
        respond(NutritionRoutinesFixtures.body())
        repository.refresh(owner)

        val items = checkNotNull(repository.observe(owner).first()).items
        val item = items[0]
        assertEquals("Marta Ruiz", item.authorName)
        assertEquals(NutritionRoutinesFixtures.PROFESSIONAL_ID, item.professionalId)
        assertEquals(14, item.durationDays)
        assertEquals("PERFORMANCE", item.goal)
        assertEquals("running", item.sport)
        assertEquals("VEGETARIAN", item.dietaryPattern)
        assertEquals(listOf("MILK"), item.containsAllergens)
        assertEquals(Instant.parse("2026-10-20T14:00:00Z"), item.publishedAt)
        assertEquals(listOf("BREAKFAST", "LUNCH", "HYDRATION"), item.dailyMeals.map { it.meal })
        assertEquals(listOf(RoutineFood("OATS", 1), RoutineFood("BANANA", 2)), item.dailyMeals[0].items)
        // Una rutina sin deporte específico llega con `sport: null` y no se inventa uno.
        assertNull(items[1].sport)
        assertTrue(items[1].containsAllergens.isEmpty())
    }

    @Test
    fun `sin respuesta de Usuarios el autor llega nulo y no se inventa`() = runTest {
        login()
        respond(NutritionRoutinesFixtures.body(items = listOf(NutritionRoutinesFixtures.item(authorName = null))))

        assertEquals(NutritionRoutinesRefresh.Updated, repository.refresh(owner))
        assertNull(checkNotNull(repository.observe(owner).first()).items.single().authorName)
    }

    @Test
    fun `sin habitos alimenticios la lista no personalizada declara missing_context y el aviso (CA3)`() = runTest {
        login()
        respond(NutritionRoutinesFixtures.notPersonalized())

        assertEquals(NutritionRoutinesRefresh.Updated, repository.refresh(owner))
        val cached = checkNotNull(repository.observe(owner).first())
        assertFalse(cached.personalized)
        assertTrue(cached.restrictionsNotVerified)
        assertEquals(listOf(RoutineMissingContext.NUTRITION_HABITS), cached.knownMissingContext)
        assertEquals(listOf("RESTRICTIONS_NOT_VERIFIED", "NOT_MEDICAL_ADVICE"), cached.notices)
        assertTrue(cached.items.single().reasons.isEmpty())
        assertFalse(cached.contextUnavailable)
    }

    @Test
    fun `con Usuarios caido la lista no es personalizada y se marca la fuente no disponible (CA3)`() = runTest {
        login()
        respond(NutritionRoutinesFixtures.notPersonalized(listOf("nutrition_habits", "sports", "primary_goal"), usersStatus = "UNAVAILABLE"))

        repository.refresh(owner)
        val cached = checkNotNull(repository.observe(owner).first())
        assertFalse(cached.personalized)
        assertTrue(cached.contextUnavailable)
        assertTrue(cached.restrictionsNotVerified)
        assertEquals(
            listOf(RoutineMissingContext.NUTRITION_HABITS, RoutineMissingContext.SPORTS, RoutineMissingContext.PRIMARY_GOAL),
            cached.knownMissingContext,
        )
    }

    @Test
    fun `una lista no personalizada sin el aviso igualmente se trata como no verificada`() = runTest {
        login()
        respond(NutritionRoutinesFixtures.body(personalized = false, missingContext = listOf("nutrition_habits"), notices = listOf("NOT_MEDICAL_ADVICE")))

        repository.refresh(owner)
        assertTrue(checkNotNull(repository.observe(owner).first()).restrictionsNotVerified)
    }

    @Test
    fun `una nueva consulta reemplaza la lista y una rutina excluida deja de mostrarse (CA2)`() = runTest {
        login()
        respond(NutritionRoutinesFixtures.body())
        repository.refresh(owner)
        // El deportista registró una alergia: el BFF ya no sugiere la rutina con leche.
        respond(NutritionRoutinesFixtures.body(items = listOf(NutritionRoutinesFixtures.item(templateId = NutritionRoutinesFixtures.SPORT_TEMPLATE_ID, allergens = emptyList()))))
        clock.instant = clock.instant.plusSeconds(60)

        repository.refresh(owner)

        val cached = checkNotNull(repository.observe(owner).first())
        assertEquals(listOf(NutritionRoutinesFixtures.SPORT_TEMPLATE_ID), cached.items.map { it.templateId })
        assertNull(cached.find(NutritionRoutinesFixtures.GOAL_TEMPLATE_ID))
        assertEquals(clock.instant, cached.syncedAt)
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun `sin rutinas es una lista confirmada vacia, no un error`() = runTest {
        login()
        respond(NutritionRoutinesFixtures.body(items = emptyList()))

        assertEquals(NutritionRoutinesRefresh.Updated, repository.refresh(owner))
        assertTrue(checkNotNull(repository.observe(owner).first()).items.isEmpty())
    }

    @Test
    fun `codigos futuros de motivo, contexto o aviso se conservan sin interpretarse`() = runTest {
        login()
        respond(
            NutritionRoutinesFixtures.body(
                items = listOf(NutritionRoutinesFixtures.item(reasons = listOf("GOAL_MATCH", "SEASON_MATCH"), allergens = listOf("MUSTARD"))),
                missingContext = listOf("primary_goal", "city"),
                notices = listOf("NOT_MEDICAL_ADVICE", "NEW_NOTICE"),
            )
        )

        repository.refresh(owner)
        val cached = checkNotNull(repository.observe(owner).first())
        assertEquals(listOf("GOAL_MATCH", "SEASON_MATCH"), cached.items.single().reasons)
        assertEquals(listOf(RoutineReason.GOAL_MATCH), cached.items.single().knownReasons)
        assertEquals(listOf("MUSTARD"), cached.items.single().containsAllergens)
        assertEquals(listOf("primary_goal", "city"), cached.missingContext)
        assertEquals(listOf("NOT_MEDICAL_ADVICE", "NEW_NOTICE"), cached.notices)
    }

    // ----------------------------------------------------------------- errores

    @Test
    fun `sin la capacidad el BFF responde 403 capability_required`() = runTest {
        login()
        server.enqueue(SessionFixtures.error(403, "capability_required"))

        assertEquals(NutritionRoutinesRefresh.CapabilityRequired, repository.refresh(owner))
    }

    @Test
    fun `un actor distinto de deportista recibe 403 permission_required`() = runTest {
        login()
        server.enqueue(SessionFixtures.error(403, "permission_required"))

        assertEquals(NutritionRoutinesRefresh.PermissionRequired, repository.refresh(owner))
    }

    @Test
    fun `un limite rechazado con 422 se informa sin datos`() = runTest {
        login()
        respond(NutritionRoutinesFixtures.validationError(), 422)

        assertEquals(NutritionRoutinesRefresh.InvalidRequest, repository.refresh(owner))
        assertNull(repository.observe(owner).first())
    }

    @Test
    fun `503 y 504 no reemplazan la cache`() = runTest {
        login()
        respond(NutritionRoutinesFixtures.body())
        repository.refresh(owner)

        server.enqueue(SessionFixtures.error(503, "upstream_unavailable"))
        assertEquals(NutritionRoutinesRefresh.Unavailable("8c1f2d3e-0000-4000-8000-000000000001"), repository.refresh(owner))
        server.enqueue(SessionFixtures.error(504, "upstream_timeout"))
        assertTrue(repository.refresh(owner) is NutritionRoutinesRefresh.Unavailable)

        assertEquals(2, checkNotNull(repository.observe(owner).first()).items.size)
    }

    @Test
    fun `un 401 renueva la sesion una vez y repite la consulta`() = runTest {
        login()
        server.enqueue(SessionFixtures.error(401, "invalid_access_token"))
        server.enqueue(SessionFixtures.ok(SessionFixtures.sessionBody(accessToken = "access-token-sintetico-2", refreshToken = SessionFixtures.ROTATED_REFRESH_TOKEN)))
        respond(NutritionRoutinesFixtures.body())

        assertEquals(NutritionRoutinesRefresh.Updated, repository.refresh(owner))
        takeRequest()
        assertEquals("/v1/sessions/refresh", takeRequest().path)
        assertEquals("Bearer access-token-sintetico-2", takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `sin sesion no consulta y pide volver al acceso`() = runTest {
        assertEquals(NutritionRoutinesRefresh.SignedOut, repository.refresh(owner))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `sin red conserva la cache y lo informa`() = runTest {
        login()
        respond(NutritionRoutinesFixtures.body())
        repository.refresh(owner)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        assertEquals(NutritionRoutinesRefresh.NoConnection, repository.refresh(owner))
        assertEquals(2, checkNotNull(repository.observe(owner).first()).items.size)
    }

    @Test
    fun `sin URL del BFF configurada no consulta`() = runTest {
        val unconfigured = NutritionRoutinesRepository(dao, NutritionRoutinesCodec(cipher, BffMobileClient.json), null, sessionManager, BffMobileClient.json, clock)

        assertEquals(NutritionRoutinesRefresh.Unavailable(null), unconfigured.refresh(owner))
    }

    @Test
    fun `una respuesta fuera de contrato no se guarda ni se muestra parcialmente`() = runTest {
        login()
        respond(NutritionRoutinesFixtures.body(items = listOf(NutritionRoutinesFixtures.item(publishedAt = "ayer"))))
        assertEquals(NutritionRoutinesRefresh.InvalidResponse, repository.refresh(owner))

        respond(NutritionRoutinesFixtures.body(items = listOf(NutritionRoutinesFixtures.item(referenceType = "training_plan_template"))))
        assertEquals(NutritionRoutinesRefresh.InvalidResponse, repository.refresh(owner))

        // Sin `notices`, obligatorio en 0.21.0.
        respond("""{"items":[],"personalized":true,"missing_context":[],"generated_at":"2026-10-27T13:00:00Z","sources":[]}""")
        assertEquals(NutritionRoutinesRefresh.InvalidResponse, repository.refresh(owner))

        assertNull(repository.observe(owner).first())
    }

    @Test
    fun `si no se puede guardar se informa sin perder la cache anterior`() = runTest {
        login()
        respond(NutritionRoutinesFixtures.body())
        repository.refresh(owner)
        dao.failWrites = true
        respond(NutritionRoutinesFixtures.body(items = emptyList()))

        assertEquals(NutritionRoutinesRefresh.StorageFailed, repository.refresh(owner))
        assertEquals(2, checkNotNull(repository.observe(owner).first()).items.size)
    }

    // ----------------------------------------------------------------- caché cifrada

    @Test
    fun `la cache solo guarda texto cifrado y la particion`() = runTest {
        login()
        respond(NutritionRoutinesFixtures.body())
        repository.refresh(owner)

        val row = dao.rows.value.getValue(owner)
        val stored = row.payload.toString(Charsets.ISO_8859_1)
        listOf("Desayunos", "Marta", "MILK", "OATS", NutritionRoutinesFixtures.GOAL_TEMPLATE_ID).forEach { assertFalse(it, it in stored) }
        assertFalse(TrainingFixtures.USER_ID in row.ownerKey)
    }

    @Test
    fun `una cache ilegible se trata como inexistente y no inventa rutinas`() = runTest {
        dao.replace(NutritionRoutinesCacheEntity(owner, byteArrayOf(1, 2, 3)))
        assertNull(repository.observe(owner).first())

        login()
        respond(NutritionRoutinesFixtures.body())
        repository.refresh(owner)
        cipher.broken = true
        assertNull(repository.observe(owner).first())
    }

    @Test
    fun `la cache conserva todos los datos y la antiguedad y se marca STALE a las 24 horas`() = runTest {
        login()
        respond(NutritionRoutinesFixtures.notPersonalized(usersStatus = "UNAVAILABLE"))
        repository.refresh(owner)
        val cached = checkNotNull(repository.observe(owner).first())

        assertFalse(cached.personalized)
        assertEquals(RoutineSourceStatus.UNAVAILABLE, cached.usersSource)
        assertEquals(listOf("RESTRICTIONS_NOT_VERIFIED", "NOT_MEDICAL_ADVICE"), cached.notices)
        assertEquals(listOf("PEANUTS", "MILK"), cached.items.single().containsAllergens)
        assertFalse(cached.isStale(clock.instant.plusSeconds(24 * 3600)))
        assertTrue(cached.isStale(clock.instant.plusSeconds(24 * 3600 + 1)))
    }

    @Test
    fun `cada deportista solo ve su particion y la limpieza respeta las demas`() = runTest {
        login()
        respond(NutritionRoutinesFixtures.body())
        repository.refresh(owner)
        val other = NutritionRoutinesOwner.keyOf(TrainingFixtures.OTHER_USER_ID)

        assertNull(repository.observe(other).first())
        repository.clearAllExcept(other)
        assertNull(repository.observe(owner).first())
    }

    // ----------------------------------------------------------------- limpieza al cerrar sesión

    @Test
    fun `el limpiador borra la cache al cerrar sesion y la de otros deportistas al entrar`() = runTest {
        val user = MutableStateFlow<SessionUser?>(null)
        val other = NutritionRoutinesOwner.keyOf(TrainingFixtures.OTHER_USER_ID)
        dao.replace(NutritionRoutinesCacheEntity(other, byteArrayOf(9)))
        dao.replace(NutritionRoutinesCacheEntity(owner, byteArrayOf(7)))
        val job = NutritionRoutinesCacheJanitor(user, repository).start(TestScope(UnconfinedTestDispatcher(testScheduler)))
        assertEquals(2, dao.rows.value.size)

        user.value = SessionUser(TrainingFixtures.USER_ID, "Ana Deportista", "ana@example.com", ActorType.ATHLETE)
        assertEquals(setOf(owner), dao.rows.value.keys)

        user.value = null
        assertTrue(dao.rows.value.isEmpty())
        job.cancelAndJoin()
    }
}
