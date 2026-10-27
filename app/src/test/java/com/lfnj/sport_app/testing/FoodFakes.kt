package com.lfnj.sport_app.testing

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.features.nutrition.intake.data.FoodIntakeApi
import com.lfnj.sport_app.features.nutrition.intake.data.FoodIntakeDraftItem
import com.lfnj.sport_app.features.nutrition.intake.data.FoodIntakeRepository
import com.lfnj.sport_app.features.nutrition.intake.data.FoodLocalState
import com.lfnj.sport_app.features.nutrition.intake.data.FoodSubmitResult
import com.lfnj.sport_app.features.nutrition.intake.data.local.FoodCatalogCacheEntity
import com.lfnj.sport_app.features.nutrition.intake.data.local.FoodIntakeCodec
import com.lfnj.sport_app.features.nutrition.intake.data.local.FoodIntakeDao
import com.lfnj.sport_app.features.nutrition.intake.data.local.FoodIntakeEntity
import com.lfnj.sport_app.features.nutrition.intake.model.FoodOwner
import com.lfnj.sport_app.features.nutrition.intake.model.FoodUnit
import com.lfnj.sport_app.features.nutrition.intake.model.MealType
import com.lfnj.sport_app.features.nutrition.intake.sync.FoodIntakeSyncEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/** Datos sintéticos de HU043 conforme a ADR-037 y la copia de `bff-mobile` 0.21.0. */
object FoodFixtures {
    val NOW: Instant = Instant.parse("2026-10-24T12:35:00Z")
    val CONSUMED_AT: Instant = Instant.parse("2026-10-24T12:30:00Z")
    const val ETAG = "\"food-catalog-2026-10-13.v1\""
    const val CATALOG_VERSION = "food-catalog-2026-10-13.v1"

    /** Fragmento del catálogo precargado (`food-catalog-2026-10-13.v1`) con la forma exacta del contrato. */
    val CATALOG = """{"catalog_version":"$CATALOG_VERSION","items":[""" +
        """{"food_code":"OATS","label_key":"food.oats","category":"GRAIN","default_unit":"G","allowed_units":["G","CUP","TBSP"],"allergens":[],"incompatible_with":[]},""" +
        """{"food_code":"EGG","label_key":"food.egg","category":"PROTEIN","default_unit":"G","allowed_units":["G","UNIT"],"allergens":["EGGS"],"incompatible_with":["VEGAN"]},""" +
        """{"food_code":"MILK","label_key":"food.milk","category":"DAIRY","default_unit":"ML","allowed_units":["ML","CUP"],"allergens":["MILK"],"incompatible_with":["VEGAN","LACTOSE_FREE"]},""" +
        """{"food_code":"WATER","label_key":"food.water","category":"BEVERAGE","default_unit":"ML","allowed_units":["ML","CUP"],"allergens":[],"incompatible_with":[]}""" +
        """]}"""

    fun response(
        intakeId: String,
        mealType: String = "BREAKFAST",
        consumedAt: String = "2026-10-24T12:30:00Z",
        items: String = """[{"food_code":"OATS","quantity":"50.0","unit":"G"}]""",
        version: Int = 1,
    ) = """{"intake_id":"$intakeId","athlete_id":"${TrainingFixtures.USER_ID}","consumed_at":"$consumedAt","meal_type":"$mealType",""" +
        """"items":$items,"catalog_version":"$CATALOG_VERSION","version":$version,"registered_at":"2026-10-24T12:35:02Z"}"""

    fun error(code: String) =
        """{"code":"$code","message":"mensaje","correlation_id":"8c1f2d3e-0000-4000-8000-000000000043"}"""

    fun validationError(field: String, issue: String) =
        """{"code":"validation_error","message":"mensaje","correlation_id":"8c1f2d3e-0000-4000-8000-000000000044",""" +
            """"details":[{"field":"$field","issue":"$issue"}]}"""

    val OATS_50 = FoodIntakeDraftItem("OATS", "50.0", FoodUnit.G)
}

/** Reloj controlable para vigencias, vencimientos e instantes de prueba. */
class FoodClock(var instant: Instant = FoodFixtures.NOW) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = instant

    fun advanceHours(hours: Long) {
        instant = instant.plusSeconds(hours * 3600)
    }
}

/** Fuente local en memoria con la misma semántica de consultas que el DAO de Room de HU043. */
class InMemoryFoodIntakeDao : FoodIntakeDao {
    val catalogs = linkedMapOf<String, FoodCatalogCacheEntity>()
    val intakes = linkedMapOf<String, FoodIntakeEntity>()
    var failWrites = false
    private val changes = MutableStateFlow(0)

    private fun changed() {
        changes.value++
    }

    private fun checkWritable() {
        if (failWrites) throw IllegalStateException("almacenamiento no disponible")
    }

    override fun observeCatalog(owner: String): Flow<FoodCatalogCacheEntity?> = changes.map { catalogs[owner] }

    override suspend fun catalog(owner: String) = catalogs[owner]

    override suspend fun replaceCatalog(entry: FoodCatalogCacheEntity) {
        checkWritable()
        catalogs[entry.ownerKey] = entry
        changed()
    }

    override suspend fun deleteCatalog(owner: String) {
        catalogs.remove(owner)
        changed()
    }

    override fun observeIntakes(owner: String): Flow<List<FoodIntakeEntity>> =
        changes.map { intakes.values.filter { it.ownerKey == owner }.sortedByDescending { it.createdAtMs } }

    override suspend fun pendingIntakes(owner: String) =
        intakes.values.filter { it.ownerKey == owner && it.syncState == FoodIntakeEntity.PENDING_SYNC }.sortedBy { it.createdAtMs }

    override suspend fun countPending(owner: String) = pendingIntakes(owner).size

    override suspend fun intake(owner: String, intakeId: String) = intakes[intakeId]?.takeIf { it.ownerKey == owner }

    override suspend fun insertIntake(intake: FoodIntakeEntity) {
        checkWritable()
        require(intake.intakeId !in intakes && intakes.values.none { it.idempotencyKey == intake.idempotencyKey })
        intakes[intake.intakeId] = intake
        changed()
    }

    override suspend fun replaceIntake(intake: FoodIntakeEntity) {
        checkWritable()
        intakes[intake.intakeId] = intake
        changed()
    }

    override suspend fun markAttempt(intakeId: String, now: Long) {
        val i = intakes[intakeId] ?: return
        intakes[intakeId] = FoodIntakeEntity(
            i.intakeId, i.ownerKey, i.idempotencyKey, i.syncState, i.failureReason, i.failureField, i.failureIssue,
            i.createdAtMs, i.attempts + 1, now, i.payload,
        )
        changed()
    }

    override suspend fun deleteIntake(owner: String, intakeId: String) {
        if (intakes[intakeId]?.ownerKey == owner) intakes.remove(intakeId)
        changed()
    }

    override suspend fun deleteSettled(owner: String) {
        intakes.values.removeAll { it.ownerKey == owner && it.syncState != FoodIntakeEntity.PENDING_SYNC }
        changed()
    }

    override suspend fun pendingCreatedBefore(threshold: Long) =
        intakes.values.filter { it.syncState == FoodIntakeEntity.PENDING_SYNC && it.createdAtMs < threshold }
}

/**
 * Repositorio y motor reales de HU043 sobre la fuente local en memoria y un `bff-mobile` simulado
 * con MockWebServer. Contrato simulado: **no** es un E2E.
 */
class FoodHarness {
    val server = MockWebServer().apply { start() }
    private val retrofit = BffMobileClient.retrofit(
        server.url("/").toString(),
        BffMobileClient.okHttp().newBuilder().retryOnConnectionFailure(false).build(),
    )
    val tokenStore = FakeTokenStore()
    val sessionManager = SessionManager(retrofit.create(SessionApi::class.java), tokenStore, BffMobileClient.json, clock = { 1_000_000L })
    val dao = InMemoryFoodIntakeDao()
    val cipher = FakeCipher()
    val codec = FoodIntakeCodec(cipher, BffMobileClient.json)
    val clock = FoodClock()
    val scheduler = FakeScheduler()
    val api: FoodIntakeApi = retrofit.create(FoodIntakeApi::class.java)
    val owner = FoodOwner.keyOf(TrainingFixtures.USER_ID)
    private var ids = 0
    val repository = FoodIntakeRepository(
        dao, SerialTransactionRunner(), codec, api, sessionManager, scheduler, BffMobileClient.json, clock,
        newId = { "00000000-0000-4000-8000-%012d".format(++ids) },
    )
    val engine = FoodIntakeSyncEngine(dao, SerialTransactionRunner(), codec, api, sessionManager, BffMobileClient.json, clock)

    suspend fun login(manager: SessionManager = sessionManager) {
        server.enqueue(SessionFixtures.ok(SessionFixtures.sessionBody()))
        manager.login("ana@example.com", "Secreta-123")
        server.takeRequest()
    }

    /** Catálogo confirmado en caché. */
    suspend fun cacheCatalog(body: String = FoodFixtures.CATALOG) {
        server.enqueue(MockResponse().setBody(body).setHeader("ETag", FoodFixtures.ETAG))
        repository.refreshCatalog(owner)
        takeRequest()
    }

    /** Captura un desayuno de 50 g de avena y devuelve su `intake_id`. */
    suspend fun capture(
        items: List<FoodIntakeDraftItem> = listOf(FoodFixtures.OATS_50),
        mealType: MealType = MealType.BREAKFAST,
        consumedAt: Instant = FoodFixtures.CONSUMED_AT,
    ): String = (repository.submit(owner, mealType, consumedAt, items) as FoodSubmitResult.Queued).intakeId

    suspend fun local(owner: String = this.owner): FoodLocalState = repository.observe(owner).first()

    fun takeRequest(): RecordedRequest = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS)) { "No llegó la solicitud esperada" }

    fun shutdown() = server.shutdown()
}
