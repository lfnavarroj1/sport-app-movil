package com.lfnj.sport_app.features.nutrition.intake

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lfnj.sport_app.R
import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.security.KeystoreTokenCipher
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.features.nutrition.intake.data.FoodIntakeApi
import com.lfnj.sport_app.features.nutrition.intake.data.FoodIntakeDraftItem
import com.lfnj.sport_app.features.nutrition.intake.data.FoodIntakeRepository
import com.lfnj.sport_app.features.nutrition.intake.data.FoodSubmitResult
import com.lfnj.sport_app.features.nutrition.intake.data.local.FoodIntakeCodec
import com.lfnj.sport_app.features.nutrition.intake.data.local.FoodIntakeDatabase
import com.lfnj.sport_app.features.nutrition.intake.model.FoodOwner
import com.lfnj.sport_app.features.nutrition.intake.model.FoodSyncFailure
import com.lfnj.sport_app.features.nutrition.intake.model.FoodSyncState
import com.lfnj.sport_app.features.nutrition.intake.model.FoodUnit
import com.lfnj.sport_app.features.nutrition.intake.model.MealType
import com.lfnj.sport_app.features.nutrition.intake.sync.FoodIntakeSyncEngine
import com.lfnj.sport_app.testing.InMemoryTokenStore
import com.lfnj.sport_app.testing.RecordingScheduler
import com.lfnj.sport_app.testing.TestAppContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/**
 * HU043 en el dispositivo: Room real, cifrado AES-GCM con Android Keystore, partición por deportista,
 * reinicio del proceso, vencimiento, limpieza al cerrar sesión, esquema exportado y exclusión del
 * respaldo (ADR-037 §5 con la política de ADR-029).
 */
@RunWith(AndroidJUnit4::class)
class FoodIntakeLocalStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val owner = FoodOwner.keyOf(USER_ID)
    private val server = MockWebServer()
    private val scheduler = RecordingScheduler()
    private var database = openDatabase()
    private val sessionManager by lazy {
        SessionManager(BffMobileClient.retrofit(server.url("/").toString()).create(SessionApi::class.java), InMemoryTokenStore(), BffMobileClient.json)
    }
    private val codec get() = FoodIntakeCodec(KeystoreTokenCipher(TestAppContainer.FOOD_INTAKES_TEST_KEY_ALIAS), BffMobileClient.json)
    private val api get() = BffMobileClient.retrofit(server.url("/").toString()).create(FoodIntakeApi::class.java)

    @get:Rule
    val migrationHelper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), FoodIntakeDatabase::class.java)

    private fun openDatabase() = Room.databaseBuilder(context, FoodIntakeDatabase::class.java, DB_NAME).build()

    /** Un repositorio nuevo sobre la misma base equivale a un proceso nuevo. */
    private fun repository(db: FoodIntakeDatabase = database, clock: Clock = Clock.systemUTC()) = FoodIntakeRepository(
        dao = db.foodIntakeDao(),
        transactions = db.transactions,
        codec = codec,
        api = api,
        sessionManager = sessionManager,
        scheduler = scheduler,
        json = BffMobileClient.json,
        clock = clock,
    )

    @Before
    fun setUp() {
        server.start()
        server.enqueue(MockResponse().setBody(SESSION_BODY))
        runBlocking { sessionManager.login("ana@example.com", "Secreta-123") }
        server.takeRequest(5, TimeUnit.SECONDS)
    }

    @After
    fun tearDown() {
        server.shutdown()
        database.close()
        context.deleteDatabase(DB_NAME)
        context.deleteDatabase(MIGRATION_DB)
        TestAppContainer.deleteTestKey(TestAppContainer.FOOD_INTAKES_TEST_KEY_ALIAS)
    }

    private suspend fun cacheCatalogAndCapture(repository: FoodIntakeRepository = repository()): String {
        server.enqueue(MockResponse().setBody(CATALOG).setHeader("ETag", "\"food-catalog-2026-10-13.v1\""))
        repository.refreshCatalog(owner)
        server.takeRequest(5, TimeUnit.SECONDS)
        val queued = repository.submit(
            owner, MealType.BREAKFAST, Instant.now().minusSeconds(60),
            listOf(FoodIntakeDraftItem("OATS", "50.0", FoodUnit.G), FoodIntakeDraftItem("MILK", "250.0", FoodUnit.ML)),
        )
        return (queued as FoodSubmitResult.Queued).intakeId
    }

    private fun rawBlobs(): List<String> {
        val blobs = mutableListOf<String>()
        val db = database.openHelper.readableDatabase
        for (table in listOf("food_catalog_cache", "food_intake")) {
            db.query("SELECT payload FROM $table").use { cursor ->
                while (cursor.moveToNext()) blobs += String(cursor.getBlob(0), Charsets.ISO_8859_1)
            }
        }
        return blobs
    }

    @Test
    fun laBaseSoloGuardaTextoCifradoYMetadatosTecnicos() = runBlocking {
        cacheCatalogAndCapture()

        val blobs = rawBlobs()
        assertEquals(2, blobs.size) // catálogo + consumo pendiente
        blobs.forEach { blob ->
            listOf("OATS", "MILK", "food.oats", "BREAKFAST", "250.0", "consumed_at", "food-catalog-2026").forEach {
                assertFalse("«$it» en claro", blob.contains(it))
            }
        }
        val columns = database.openHelper.readableDatabase.query("SELECT * FROM food_intake").use { it.columnNames.toSet() }
        assertEquals(
            setOf(
                "intake_id", "owner_key", "idempotency_key", "sync_state", "failure_reason", "failure_field",
                "failure_issue", "created_at_ms", "attempts", "last_attempt_at_ms", "payload",
            ),
            columns,
        )
    }

    @Test
    fun ca5ElConsumoPendienteSobreviveAlReinicioDelProceso() = runBlocking {
        val id = cacheCatalogAndCapture()
        database.close()

        database = openDatabase()
        val restarted = repository()
        val local = restarted.observe(owner).first()

        assertEquals(id, local.intakes.single().intakeId)
        assertEquals(FoodSyncState.PENDING_SYNC, local.intakes.single().state)
        assertEquals(listOf("50.0", "250.0"), local.intakes.single().details?.items?.map { it.quantity })
        assertEquals(4, local.catalog?.items?.size)
        assertTrue(restarted.hasPending(owner))
    }

    @Test
    fun cadaDeportistaSoloVeSuParticion() = runBlocking {
        cacheCatalogAndCapture()

        val other = repository().observe(FoodOwner.keyOf("0f0e0d0c-0b0a-4908-8706-050403020100")).first()
        assertNull(other.catalog)
        assertTrue(other.intakes.isEmpty())
    }

    @Test
    fun conLaClaveInvalidadaNoSeInventanDatos() = runBlocking {
        cacheCatalogAndCapture()
        TestAppContainer.deleteTestKey(TestAppContainer.FOOD_INTAKES_TEST_KEY_ALIAS)

        val local = repository().observe(owner).first()

        assertNull("Un catálogo ilegible no habilita alimentos", local.catalog)
        assertEquals(FoodSyncFailure.UNREADABLE, local.intakes.single().failure)
        assertNull(local.intakes.single().details)
    }

    @Test
    fun unPendienteConMasDe30DiasVenceSinEnviarse() = runBlocking {
        cacheCatalogAndCapture()
        val later = Clock.fixed(Instant.now().plus(Duration.ofDays(31)), ZoneOffset.UTC)
        val engine = FoodIntakeSyncEngine(database.foodIntakeDao(), database.transactions, codec, api, sessionManager, BffMobileClient.json, later)
        val requests = server.requestCount

        engine.run(owner)

        val local = repository().observe(owner).first()
        assertEquals(FoodSyncFailure.EXPIRED, local.intakes.single().failure)
        assertFalse(repository().hasPending(owner))
        assertEquals(requests, server.requestCount)
    }

    @Test
    fun alCerrarSesionSeBorranCatalogoYResueltosYSeConservanLosPendientes() = runBlocking {
        val repository = repository()
        val pending = cacheCatalogAndCapture(repository)
        val failed = (repository.submit(owner, MealType.SNACK, Instant.now().minusSeconds(30), listOf(FoodIntakeDraftItem("WATER", "500.0", FoodUnit.ML)))
            as FoodSubmitResult.Queued).intakeId
        val dao = database.foodIntakeDao()
        dao.replaceIntake(checkNotNull(dao.intake(owner, failed)).failed(FoodSyncFailure.CONFLICT.name))

        repository.clearOnSessionEnd(owner)

        val local = repository.observe(owner).first()
        assertNull(local.catalog)
        assertEquals(listOf(pending), local.intakes.map { it.intakeId })
        assertEquals(FoodSyncState.PENDING_SYNC, local.intakes.single().state)
    }

    @Test
    fun elEsquemaCreadoCoincideConElExportadoDeLaVersion1() {
        migrationHelper.createDatabase(MIGRATION_DB, 1).close()

        migrationHelper.runMigrationsAndValidate(MIGRATION_DB, 1, true).close()
    }

    @Test
    fun laBaseDeConsumosSeExcluyeDelRespaldoYLaTransferencia() {
        val backup = excludedPaths(R.xml.backup_rules)
        val extraction = excludedPaths(R.xml.data_extraction_rules)

        listOf(FoodIntakeDatabase.NAME, "${FoodIntakeDatabase.NAME}-wal", "${FoodIntakeDatabase.NAME}-shm", "${FoodIntakeDatabase.NAME}-journal").forEach { file ->
            assertTrue("$file en backup_rules", "database:$file" in backup)
            assertEquals("$file en nube y transferencia", 2, extraction.count { it == "database:$file" })
        }
    }

    private fun excludedPaths(xml: Int): List<String> {
        val paths = mutableListOf<String>()
        context.resources.getXml(xml).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "exclude") {
                    paths += "${parser.getAttributeValue(null, "domain")}:${parser.getAttributeValue(null, "path")}"
                }
            }
        }
        return paths
    }

    companion object {
        const val USER_ID = "7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b"
        const val DB_NAME = "sportapp_food_intakes_test.db"
        const val MIGRATION_DB = "sportapp_food_intakes_migration_test.db"
        const val SESSION_BODY =
            """{"token_type":"Bearer","access_token":"access-sintetico","expires_in":900,""" +
                """"refresh_token":"refresh-token-sintetico-000000000043","refresh_expires_in":604800,""" +
                """"user":{"user_id":"$USER_ID","full_name":"Ana Deportista",""" +
                """"email":"ana@example.com","actor_type":"athlete"}}"""

        /** Fragmento del catálogo precargado `food-catalog-2026-10-13.v1` con la forma del contrato 0.21.0. */
        const val CATALOG =
            """{"catalog_version":"food-catalog-2026-10-13.v1","items":[""" +
                """{"food_code":"OATS","label_key":"food.oats","category":"GRAIN","default_unit":"G","allowed_units":["G","CUP","TBSP"],"allergens":[],"incompatible_with":[]},""" +
                """{"food_code":"EGG","label_key":"food.egg","category":"PROTEIN","default_unit":"G","allowed_units":["G","UNIT"],"allergens":["EGGS"],"incompatible_with":["VEGAN"]},""" +
                """{"food_code":"MILK","label_key":"food.milk","category":"DAIRY","default_unit":"ML","allowed_units":["ML","CUP"],"allergens":["MILK"],"incompatible_with":["VEGAN","LACTOSE_FREE"]},""" +
                """{"food_code":"WATER","label_key":"food.water","category":"BEVERAGE","default_unit":"ML","allowed_units":["ML","CUP"],"allergens":[],"incompatible_with":[]}""" +
                """]}"""

        fun response(intakeId: String, items: String = """[{"food_code":"OATS","quantity":"50.0","unit":"G"},{"food_code":"MILK","quantity":"250.0","unit":"ML"}]""",
                     consumedAt: String, mealType: String = "BREAKFAST") =
            """{"intake_id":"$intakeId","athlete_id":"$USER_ID","consumed_at":"$consumedAt","meal_type":"$mealType",""" +
                """"items":$items,"catalog_version":"food-catalog-2026-10-13.v1","version":1,"registered_at":"2026-10-24T12:35:02Z"}"""
    }
}
