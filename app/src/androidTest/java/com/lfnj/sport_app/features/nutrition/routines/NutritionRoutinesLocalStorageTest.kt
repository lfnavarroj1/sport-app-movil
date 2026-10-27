package com.lfnj.sport_app.features.nutrition.routines

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lfnj.sport_app.R
import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.security.KeystoreTokenCipher
import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.nutrition.routines.data.NutritionRoutinesCacheJanitor
import com.lfnj.sport_app.features.nutrition.routines.data.NutritionRoutinesRepository
import com.lfnj.sport_app.features.nutrition.routines.data.local.NutritionRoutinesCacheEntity
import com.lfnj.sport_app.features.nutrition.routines.data.local.NutritionRoutinesCodec
import com.lfnj.sport_app.features.nutrition.routines.data.local.NutritionRoutinesDatabase
import com.lfnj.sport_app.features.nutrition.routines.model.NutritionRoutineSuggestion
import com.lfnj.sport_app.features.nutrition.routines.model.NutritionRoutinesOwner
import com.lfnj.sport_app.features.nutrition.routines.model.NutritionRoutinesSnapshot
import com.lfnj.sport_app.features.nutrition.routines.model.RoutineFood
import com.lfnj.sport_app.features.nutrition.routines.model.RoutineMeal
import com.lfnj.sport_app.features.nutrition.routines.model.RoutineSourceStatus
import com.lfnj.sport_app.testing.InMemoryTokenStore
import com.lfnj.sport_app.testing.TestAppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.time.Instant

/**
 * HU030 en el dispositivo: Room real, cifrado AES-GCM con Android Keystore, partición por
 * deportista, reinicio del proceso, limpieza al cerrar sesión, esquema exportado y exclusión del
 * respaldo (ADR-041 §5).
 */
@RunWith(AndroidJUnit4::class)
class NutritionRoutinesLocalStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val ana = SessionUser("7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b", "Ana Deportista", "ana@example.com", ActorType.ATHLETE)
    private val owner = NutritionRoutinesOwner.keyOf(ana.userId)
    private val otherOwner = NutritionRoutinesOwner.keyOf("0f0e0d0c-0b0a-4908-8706-050403020100")
    private var database = openDatabase()

    @get:Rule
    val migrationHelper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), NutritionRoutinesDatabase::class.java)

    private fun openDatabase() = Room.databaseBuilder(context, NutritionRoutinesDatabase::class.java, DB_NAME).build()

    private fun codec() = NutritionRoutinesCodec(KeystoreTokenCipher(TestAppContainer.NUTRITION_ROUTINES_TEST_KEY_ALIAS), BffMobileClient.json)

    /** Un repositorio nuevo equivale a un proceso nuevo: no conserva nada en memoria. */
    private fun repository(db: NutritionRoutinesDatabase = database) = NutritionRoutinesRepository(
        dao = db.nutritionRoutinesCacheDao(),
        codec = codec(),
        api = null,
        sessionManager = SessionManager(null, InMemoryTokenStore(), BffMobileClient.json),
        json = BffMobileClient.json,
    )

    private val snapshot = NutritionRoutinesSnapshot(
        items = listOf(
            NutritionRoutineSuggestion(
                templateId = "7d1e2f30-4a5b-4c6d-8e7f-90a1b2c3d4e5", professionalId = "2e3f4a5b-6c7d-4e8f-9a0b-1c2d3e4f5a6b",
                title = "Desayunos para fondo", summary = "Comidas del día tipo para semanas de carga en carrera.",
                goal = "PERFORMANCE", sport = null, dietaryPattern = "VEGETARIAN", containsAllergens = listOf("MILK", "SESAME"), durationDays = 14,
                dailyMeals = listOf(RoutineMeal("BREAKFAST", listOf(RoutineFood("OATS", 1), RoutineFood("BANANA", 2)))),
                publishedAt = Instant.parse("2026-10-20T14:00:00Z"), authorName = "Marta Ruiz", reasons = listOf("RESTRICTION_COMPATIBLE", "GOAL_MATCH"),
            ),
        ),
        personalized = false,
        missingContext = listOf("nutrition_habits"),
        notices = listOf("RESTRICTIONS_NOT_VERIFIED", "NOT_MEDICAL_ADVICE"),
        generatedAt = Instant.parse("2026-10-27T13:00:00Z"),
        usersSource = RoutineSourceStatus.OK,
        syncedAt = Instant.parse("2026-10-27T13:00:05Z"),
    )

    private suspend fun save(target: String = owner, db: NutritionRoutinesDatabase = database) =
        db.nutritionRoutinesCacheDao().replace(NutritionRoutinesCacheEntity(target, codec().encode(snapshot)))

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DB_NAME)
        context.deleteDatabase(MIGRATION_DB)
        TestAppContainer.deleteTestKey(TestAppContainer.NUTRITION_ROUTINES_TEST_KEY_ALIAS)
    }

    @Test
    fun laCacheSoloGuardaTextoCifradoYLaParticion() = runBlocking {
        save()

        val db = database.openHelper.readableDatabase
        val columns = db.query("SELECT * FROM nutrition_routines_cache").use { it.columnNames.toSet() }
        assertEquals(setOf("owner_key", "payload"), columns)
        val blob = db.query("SELECT payload FROM nutrition_routines_cache").use { it.moveToFirst(); it.getBlob(0) }
        val text = String(blob, Charsets.ISO_8859_1)
        listOf("Desayunos", "Marta", "MILK", "SESAME", "VEGETARIAN", "OATS", "nutrition_habits", "RESTRICTIONS_NOT_VERIFIED", "last_synced_at").forEach {
            assertFalse("«$it» en claro", text.contains(it))
        }
        assertEquals(snapshot, repository().observe(owner).first())
    }

    @Test
    fun laListaConfirmadaSobreviveAlReinicioConSuAntiguedadYAvisos() = runBlocking {
        save()
        database.close()

        database = openDatabase()
        val restored = checkNotNull(repository().observe(owner).first())

        assertEquals(snapshot.syncedAt, restored.syncedAt)
        assertEquals(snapshot.items, restored.items)
        assertEquals(listOf("nutrition_habits"), restored.missingContext)
        assertTrue(restored.restrictionsNotVerified)
        assertTrue(restored.isStale(snapshot.syncedAt.plusSeconds(24 * 3600 + 1)))
    }

    @Test
    fun cadaDeportistaSoloVeSuParticion() = runBlocking {
        save()

        assertNull(repository().observe(otherOwner).first())
    }

    @Test
    fun conLaClaveInvalidadaLaCacheSeIgnoraSinInventarRutinas() = runBlocking {
        save()
        TestAppContainer.deleteTestKey(TestAppContainer.NUTRITION_ROUTINES_TEST_KEY_ALIAS)

        assertNull(repository().observe(owner).first())
    }

    @Test
    fun cerrarSesionEliminaLaCacheYOtroDeportistaNoReutilizaLaAnterior() = runBlocking {
        save(owner)
        save(otherOwner)
        val user = MutableStateFlow<SessionUser?>(ana)
        val scope = CoroutineScope(Dispatchers.Default)
        NutritionRoutinesCacheJanitor(user, repository()).start(scope)
        val dao = database.nutritionRoutinesCacheDao()

        // Al estar autenticada Ana, se elimina la partición que otro deportista dejó en el dispositivo.
        withTimeout(5_000) { while (dao.observe(otherOwner).first() != null) delay(50) }
        assertTrue(dao.observe(owner).first() != null)

        user.value = null
        withTimeout(5_000) { while (dao.observe(owner).first() != null) delay(50) }
        scope.cancel()
    }

    @Test
    fun elEsquemaCreadoCoincideConElExportadoDeLaVersion1() {
        migrationHelper.createDatabase(MIGRATION_DB, 1).close()

        migrationHelper.runMigrationsAndValidate(MIGRATION_DB, 1, true).close()
    }

    @Test
    fun laCacheSeExcluyeDelRespaldoYLaTransferencia() {
        val backup = excludedPaths(R.xml.backup_rules)
        val extraction = excludedPaths(R.xml.data_extraction_rules)
        val name = NutritionRoutinesDatabase.NAME

        listOf(name, "$name-wal", "$name-shm", "$name-journal").forEach { file ->
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

    private companion object {
        const val DB_NAME = "sportapp_nutrition_routines_test.db"
        const val MIGRATION_DB = "sportapp_nutrition_routines_migration_test.db"
    }
}
