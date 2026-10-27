package com.lfnj.sport_app.navigation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lfnj.sport_app.core.config.AppConfig
import com.lfnj.sport_app.features.nutrition.intake.FoodIntakeLocalStorageTest
import com.lfnj.sport_app.features.nutrition.intake.model.FoodOwner
import com.lfnj.sport_app.features.training.session.sync.SyncRun
import com.lfnj.sport_app.testing.InMemoryTokenStore
import com.lfnj.sport_app.testing.TestAppContainer
import com.lfnj.sport_app.ui.theme.SportappTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * HU043 en la aplicación (opción base 60, guarda, ViewModel, Room y cifrado reales) contra un
 * `bff-mobile` simulado con MockWebServer según la copia 0.21.0. Prueba de componente con contrato
 * simulado, **no** un E2E.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class FoodIntakeFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val server = MockWebServer()
    private lateinit var container: TestAppContainer
    private lateinit var navController: NavHostController
    private val owner = FoodOwner.keyOf(FoodIntakeLocalStorageTest.USER_ID)

    @Before
    fun setUp() {
        server.dispatcher = QueueDispatcher().apply { setFailFast(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) }
        server.start()
        val config = AppConfig(server.url("/").toString(), "test", "0.21.0", "0.1.0")
        container = TestAppContainer(config, InMemoryTokenStore(), ApplicationProvider.getApplicationContext())
        composeRule.setContent {
            navController = rememberNavController()
            SportappTheme { AppNavHost(container, navController = navController) }
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
        container.close()
    }

    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setBody(body)

    private fun login() {
        server.enqueue(json(FoodIntakeLocalStorageTest.SESSION_BODY))
        server.enqueue(json(FREE))
        composeRule.waitUntilExactlyOneExists(hasTestTag("login_email"), 5_000)
        composeRule.onNodeWithTag("login_email").performTextInput("ana@example.com")
        composeRule.onNodeWithTag("login_password").performTextInput("Secreta-123")
        composeRule.onNodeWithTag("login_submit").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), 5_000)
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Plan ", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        server.takeRequest(1, TimeUnit.SECONDS)
        server.takeRequest(1, TimeUnit.SECONDS)
    }

    private fun openNutrition() {
        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("menu_nutrition").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("food_intake"), 5_000)
    }

    private fun takeRequest() = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "No llegó la solicitud esperada" }

    @Test
    fun descargaElCatalogoRegistraSinRedYMuestraElConsumoConfirmadoTrasSincronizar() {
        login()
        server.enqueue(json(FoodIntakeLocalStorageTest.CATALOG).setHeader("ETag", "\"food-catalog-2026-10-13.v1\""))
        openNutrition()
        assertEquals("/v1/food-catalog", takeRequest().requestUrl?.encodedPath)
        composeRule.waitUntilExactlyOneExists(hasTestTag("food_search"), 5_000)

        // Sin red: se registra igual con el catálogo en caché y queda pendiente (CA5).
        container.online.value = false
        composeRule.waitUntilExactlyOneExists(hasTestTag("food_offline"), 5_000)
        composeRule.onNodeWithTag("food_meal_HYDRATION").performClick()
        composeRule.onNodeWithTag("food_search").performTextInput("agua")
        composeRule.waitUntilExactlyOneExists(hasTestTag("food_add_WATER"), 5_000)
        composeRule.onNodeWithTag("food_add_WATER").performClick()
        composeRule.onNodeWithTag("food_quantity_0").performTextInput("500")
        composeRule.onNodeWithTag("food_unit_0_ML").assertIsDisplayed()
        composeRule.onNodeWithTag("food_submit").performScrollTo().performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("food_submit_notice"), 5_000)
        assertEquals(listOf(owner), container.foodScheduler.scheduled)
        val intake = runBlocking { container.foodIntakeRepository.observe(owner).first().intakes.single() }
        composeRule.waitUntilExactlyOneExists(hasTestTag("food_state_${intake.intakeId}") and hasText("Pendiente de sincronizar"), 5_000)

        // Al recuperar la red, la cola lo envía una vez y la pantalla lo muestra confirmado.
        container.online.value = true
        val consumedAt = checkNotNull(intake.details).consumedAt.toString()
        server.enqueue(
            json(FoodIntakeLocalStorageTest.response(intake.intakeId, """[{"food_code":"WATER","quantity":"500.0","unit":"ML"}]""", consumedAt, "HYDRATION"), 201),
        )
        assertEquals(SyncRun.COMPLETED, runBlocking { container.foodIntakeSyncEngine.run(owner) })
        val post = takeRequest()
        assertEquals("/v1/me/food-intakes", post.requestUrl?.encodedPath)
        assertTrue(post.getHeader("Idempotency-Key") != null)
        assertTrue(post.body.readUtf8().contains("\"quantity\":\"500.0\""))
        composeRule.waitUntilExactlyOneExists(hasTestTag("food_state_${intake.intakeId}") and hasText("Confirmado"), 5_000)
    }

    @Test
    fun sinRedYSinCatalogoEnCacheSolicitaConexion() {
        login()
        openNutrition()
        composeRule.waitUntilExactlyOneExists(hasTestTag("food_catalog_required"), 5_000)
        composeRule.onNodeWithTag("food_search").assertDoesNotExist()
    }

    @Test
    fun laOpcionBaseApareceConCualquierPlan() {
        login()
        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("menu_nutrition").assertIsDisplayed()
    }

    private companion object {
        const val FREE =
            """{"actor_type":"athlete","subscription_status":"ACTIVE","plan_code":"FREE","plan_version":1,""" +
                """"catalog_version":"plans-2026-10-11.v1","authorization_policy_version":"actor-permissions.v1",""" +
                """"permissions":[],"capabilities":["training.session.record"],"evaluated_at":"2026-10-12T12:00:00Z"}"""
    }
}
