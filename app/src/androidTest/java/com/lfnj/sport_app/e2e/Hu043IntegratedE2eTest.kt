package com.lfnj.sport_app.e2e

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lfnj.sport_app.MainActivity
import com.lfnj.sport_app.SportApp
import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.security.EncryptedTokenStore
import com.lfnj.sport_app.core.security.KeystoreTokenCipher
import com.lfnj.sport_app.features.nutrition.intake.data.FoodIntakeApi
import com.lfnj.sport_app.features.nutrition.intake.data.FoodIntakeDraftItem
import com.lfnj.sport_app.features.nutrition.intake.data.FoodIntakeRepository
import com.lfnj.sport_app.features.nutrition.intake.data.FoodSubmitResult
import com.lfnj.sport_app.features.nutrition.intake.data.local.FoodIntakeCodec
import com.lfnj.sport_app.features.nutrition.intake.data.local.FoodIntakeDatabase
import com.lfnj.sport_app.features.nutrition.intake.model.FoodOwner
import com.lfnj.sport_app.features.nutrition.intake.model.FoodSyncState
import com.lfnj.sport_app.features.nutrition.intake.model.FoodUnit
import com.lfnj.sport_app.features.nutrition.intake.model.LocalFoodIntake
import com.lfnj.sport_app.features.nutrition.intake.model.MealType
import com.lfnj.sport_app.features.nutrition.intake.sync.FoodIntakeSyncEngine
import com.lfnj.sport_app.features.training.session.data.TrainingSyncScheduler
import com.lfnj.sport_app.features.training.session.sync.SyncRun
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * E2E de HU043 (ADR-037, «Evidencia requerida»): aplicación real (MainActivity, Room, Keystore y URL
 * de la variante) → `bff-mobile` 0.21.0 → `training_management` en la composición local. El
 * deportista abre «Mi alimentación», que descarga el catálogo real `food-catalog-2026-10-13.v1` a la
 * caché cifrada; sin conexión con el BFF registra un consumo con alimentos de esa caché, que queda
 * `PENDING_SYNC`; tras «reiniciar» (base y repositorio nuevos sobre el mismo archivo) y recuperar la
 * red, la cola lo envía una sola vez, recibe el `201` versión 1 y la pantalla lo muestra confirmado.
 * La consulta del historial (HU013) es web y no forma parte de este recorrido.
 *
 * Requiere una cuenta sintética de deportista. Se omite sin argumentos:
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.lfnj.sport_app.e2e.Hu043IntegratedE2eTest \
 *   -Pandroid.testInstrumentationRunnerArguments.e2eAthleteEmail=<correo> \
 *   -Pandroid.testInstrumentationRunnerArguments.e2ePassword=<contraseña sintética>
 * ```
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class Hu043IntegratedE2eTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val args = InstrumentationRegistry.getArguments()
    private val password: String? = args.getString("e2ePassword")
    private val email: String? = args.getString("e2eAthleteEmail")
    private val container get() = (context as SportApp).container

    /** El envío lo controla la prueba: así se observa el estado pendiente antes de recuperar la red. */
    private val heldScheduler = object : TrainingSyncScheduler {
        override fun schedule(owner: String) = Unit
        override fun cancelAll() = Unit
    }

    @Before
    fun setUp() {
        assumeTrue("E2E omitido: falta la cuenta sintética", password != null && email != null)
        // Las pruebas comparten el proceso: se elimina también la sesión en memoria del contenedor.
        runBlocking { container.sessionManager.clearLocalSession() }
        EncryptedTokenStore(context).clear()
    }

    private fun login() {
        composeRule.waitUntilExactlyOneExists(hasTestTag("login_email"), TIMEOUT)
        composeRule.onNodeWithTag("login_email").performTextInput(requireNotNull(email))
        composeRule.onNodeWithTag("login_password").performTextInput(requireNotNull(password))
        composeRule.onNodeWithTag("login_submit").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), TIMEOUT)
    }

    private fun openNutrition() {
        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("menu_nutrition").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("food_intake"), TIMEOUT)
    }

    private fun codec() = FoodIntakeCodec(KeystoreTokenCipher(FoodIntakeCodec.KEY_ALIAS), BffMobileClient.json)

    private fun api(baseUrl: String) = BffMobileClient.retrofit(baseUrl).create(FoodIntakeApi::class.java)

    private fun repository(database: FoodIntakeDatabase, baseUrl: String) = FoodIntakeRepository(
        database.foodIntakeDao(), database.transactions, codec(), api(baseUrl), container.sessionManager, heldScheduler, BffMobileClient.json,
    )

    private fun engine(database: FoodIntakeDatabase, baseUrl: String) = FoodIntakeSyncEngine(
        dao = database.foodIntakeDao(),
        transactions = database.transactions,
        codec = codec(),
        api = api(baseUrl),
        sessionManager = container.sessionManager,
        json = BffMobileClient.json,
    )

    @Test
    fun conCatalogoEnCacheSinRedRegistraReiniciaRecuperaLaRedYSincronizaUnaSolaVez() {
        ActivityScenario.launch(MainActivity::class.java).use {
            login()
            val owner = FoodOwner.keyOf(checkNotNull(container.sessionManager.currentUser.value).userId)

            // 1. Catálogo real descargado por la pantalla a la caché cifrada (ETag del BFF).
            openNutrition()
            composeRule.waitUntilExactlyOneExists(hasTestTag("food_search"), TIMEOUT)
            composeRule.onNodeWithTag("food_search").performTextInput("agua")
            composeRule.waitUntilExactlyOneExists(hasTestTag("food_add_WATER"), TIMEOUT)
            composeRule.onNodeWithTag("food_back").performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), TIMEOUT)

            // 2. Sin red: se registra con alimentos de la caché y queda cifrado como pendiente.
            val consumedAt = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.SECONDS)
            val intakeId = runBlocking {
                val database = FoodIntakeDatabase.create(context)
                try {
                    val offline = repository(database, UNREACHABLE_BFF)
                    val catalog = checkNotNull(offline.observe(owner).first().catalog) { "La caché del catálogo debe existir" }
                    assertEquals("food-catalog-2026-10-13.v1", catalog.catalogVersion)
                    assertTrue(checkNotNull(catalog.item("WATER")).allows(FoodUnit.ML))
                    assertTrue(checkNotNull(catalog.item("OATS")).allows(FoodUnit.G))
                    val queued = offline.submit(
                        owner, MealType.BREAKFAST, consumedAt,
                        listOf(FoodIntakeDraftItem("OATS", "50.0", FoodUnit.G), FoodIntakeDraftItem("WATER", "250.0", FoodUnit.ML)),
                    ) as FoodSubmitResult.Queued
                    assertEquals(SyncRun.RETRY, engine(database, UNREACHABLE_BFF).run(owner))
                    assertEquals(FoodSyncState.PENDING_SYNC, offline.observe(owner).first().intakes.single { it.intakeId == queued.intakeId }.state)
                    queued.intakeId
                } finally {
                    database.close()
                }
            }

            // 3. «Reinicio»: base y repositorio nuevos sobre el mismo archivo; 4. con red, una sola sincronización.
            runBlocking {
                val database = FoodIntakeDatabase.create(context)
                try {
                    val restarted = repository(database, container.config.bffMobileBaseUrl)
                    assertTrue(restarted.hasPending(owner))
                    val online = engine(database, container.config.bffMobileBaseUrl)
                    assertEquals(SyncRun.COMPLETED, online.run(owner))
                    val confirmed = restarted.observe(owner).first().intakes.single { it.intakeId == intakeId }
                    assertEquals(FoodSyncState.SYNCED, confirmed.state)
                    assertNotNull("El backend devolvió registered_at", confirmed.registeredAt)
                    assertEquals(listOf("50.0", "250.0"), confirmed.details?.items?.map { it.quantity })
                    // Sin pendientes, una nueva ejecución no envía nada más.
                    assertEquals(SyncRun.COMPLETED, online.run(owner))
                    assertFalse(restarted.hasPending(owner))
                } finally {
                    database.close()
                }
            }

            // 5. «Mi alimentación» lo muestra confirmado entre los consumos de hoy.
            openNutrition()
            composeRule.waitUntilExactlyOneExists(hasTestTag("food_intake_$intakeId"), TIMEOUT)
            composeRule.onNodeWithTag("food_intake_$intakeId").performScrollTo().assertIsDisplayed()
            composeRule.waitUntilExactlyOneExists(hasTestTag("food_state_$intakeId") and hasText("Confirmado"), TIMEOUT)
        }
    }

    /** Ejecuta un comando de shell del dispositivo con los permisos de la instrumentación y espera su fin. */
    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
        }
    }

    private fun intake(owner: String, intakeId: String): LocalFoodIntake? =
        runBlocking { container.foodIntakeRepository.observe(owner).first().intakes.firstOrNull { it.intakeId == intakeId } }

    /**
     * Caso offline real (CA5): con el catálogo ya en caché, sin Wi-Fi ni datos la app registra el
     * consumo por la interfaz, lo deja `PENDING_SYNC` y WorkManager no lo envía; al restaurar la red,
     * el trabajo durable real lo sincroniza una sola vez (`201` versión 1, un único intento). La red
     * se restaura siempre.
     */
    @Test
    fun sinRedRealElConsumoQuedaPendienteYAlVolverLaRedWorkManagerLoConfirmaUnaVez() {
        ActivityScenario.launch(MainActivity::class.java).use {
            login()
            val owner = FoodOwner.keyOf(checkNotNull(container.sessionManager.currentUser.value).userId)
            // Catálogo real en caché antes de cortar la red.
            openNutrition()
            composeRule.waitUntilExactlyOneExists(hasTestTag("food_search"), TIMEOUT)
            composeRule.onNodeWithTag("food_back").performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), TIMEOUT)
            val before = runBlocking { container.foodIntakeRepository.observe(owner).first().intakes.map { it.intakeId }.toSet() }
            lateinit var intakeId: String
            try {
                shell("svc wifi disable")
                shell("svc data disable")
                // Cada sondeo abre una suscripción nueva, que lee el estado vigente del sistema: el flujo ya
                // abierto puede no volver a emitir si `onLost` llega antes de que cambie la red activa.
                runBlocking { withTimeout(TIMEOUT) { while (container.connectivityMonitor.online().first()) delay(500) } }

                openNutrition()
                composeRule.waitUntilExactlyOneExists(hasTestTag("food_offline"), TIMEOUT)
                composeRule.onNodeWithTag("food_meal_HYDRATION").performScrollTo().performClick()
                composeRule.onNodeWithTag("food_search").performScrollTo().performTextInput("agua")
                composeRule.waitUntilExactlyOneExists(hasTestTag("food_add_WATER"), TIMEOUT)
                composeRule.onNodeWithTag("food_add_WATER").performScrollTo().performClick()
                composeRule.onNodeWithTag("food_quantity_0").performScrollTo().performTextInput("300")
                composeRule.onNodeWithTag("food_submit").performScrollTo().performClick()

                intakeId = runBlocking {
                    withTimeout(TIMEOUT) {
                        container.foodIntakeRepository.observe(owner).first { local -> local.intakes.any { it.intakeId !in before } }
                            .intakes.single { it.intakeId !in before }.intakeId
                    }
                }
                composeRule.waitUntilExactlyOneExists(hasTestTag("food_state_$intakeId") and hasText("Pendiente de sincronizar"), TIMEOUT)
                // Sin red, el trabajo durable no se ejecuta: sigue pendiente y nunca se muestra confirmado.
                runBlocking { delay(OFFLINE_HOLD) }
                assertEquals(FoodSyncState.PENDING_SYNC, intake(owner, intakeId)?.state)
            } finally {
                shell("svc wifi enable")
                shell("svc data enable")
            }

            runBlocking {
                withTimeout(SYNC_TIMEOUT) {
                    container.foodIntakeRepository.observe(owner).first { local -> local.intakes.any { it.intakeId == intakeId && it.state == FoodSyncState.SYNCED } }
                }
            }
            val confirmed = checkNotNull(intake(owner, intakeId))
            assertNotNull("El backend devolvió registered_at", confirmed.registeredAt)
            assertEquals(listOf("300.0"), confirmed.details?.items?.map { it.quantity })
            // Una sola sincronización: un único intento registrado para ese intake_id.
            val attempts = runBlocking {
                val database = FoodIntakeDatabase.create(context)
                try { checkNotNull(database.foodIntakeDao().intake(owner, intakeId)).attempts } finally { database.close() }
            }
            assertEquals(1, attempts)
            composeRule.waitUntilExactlyOneExists(hasTestTag("food_state_$intakeId") and hasText("Confirmado"), TIMEOUT)
        }
    }

    private companion object {
        const val OFFLINE_HOLD = 10_000L
        const val SYNC_TIMEOUT = 120_000L
        const val TIMEOUT = 20_000L
        const val UNREACHABLE_BFF = "http://127.0.0.1:9/"
    }
}
