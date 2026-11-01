package com.lfnj.sport_app.navigation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lfnj.sport_app.core.config.AppConfig
import com.lfnj.sport_app.testing.InMemoryTokenStore
import com.lfnj.sport_app.testing.TestAppContainer
import com.lfnj.sport_app.ui.theme.SportappTheme
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * HU030 en la aplicación (menú, guarda, ViewModel, Room y cifrado reales) contra un `bff-mobile`
 * simulado con MockWebServer según la copia 0.21.0. Prueba de componente con contrato simulado,
 * **no** un E2E.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NutritionRoutinesFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val server = MockWebServer()
    private lateinit var container: TestAppContainer
    private lateinit var navController: NavHostController

    @Before
    fun setUp() {
        // Sin respuesta preparada, el servidor corta la conexión: así se simula la falta de red.
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

    private fun login(capabilities: String = CAPABILITIES) {
        server.enqueue(json(SESSION))
        server.enqueue(json(capabilities))
        composeRule.waitUntilExactlyOneExists(hasTestTag("login_email"), 5_000)
        composeRule.onNodeWithTag("login_email").performTextInput("ana@example.com")
        composeRule.onNodeWithTag("login_password").performTextInput("Secreta-123")
        composeRule.onNodeWithTag("login_submit").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), 5_000)
        composeRule.waitUntilExactlyOneExists(hasText("Plan FREE activo."), 5_000)
        server.takeRequest(1, TimeUnit.SECONDS)
        server.takeRequest(1, TimeUnit.SECONDS)
    }

    /** [response]: respuesta de `GET /v1/me/suggestions/nutrition-routines`; sin ella, falta de red. */
    private fun openRoutinesFromMenu(response: MockResponse? = null) {
        response?.let(server::enqueue)
        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("athlete_menu").performScrollToNode(hasTestTag("menu_nutrition.routines.suggest"))
        composeRule.onNodeWithTag("menu_nutrition.routines.suggest").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("nutrition_routines_screen"), 5_000)
    }

    @Test
    fun muestraLasRutinasAbreElDetalleYLasConservaSinRed() {
        login()
        openRoutinesFromMenu(json(ROUTINES))

        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/v1/me/suggestions/nutrition-routines", request.requestUrl?.encodedPath)
        assertEquals("20", request.requestUrl?.queryParameter("limit"))
        assertEquals(setOf("limit"), request.requestUrl?.queryParameterNames)
        assertEquals("Bearer access-sintetico", request.getHeader("Authorization"))

        composeRule.waitUntilExactlyOneExists(hasTestTag("nutrition_routine_$TEMPLATE_ID"), 5_000)
        composeRule.onNodeWithTag("nutrition_routines_personalized").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("nutrition_routines_not_medical_advice").performScrollTo().assertIsDisplayed()
        composeRule.onNode(hasTestTag("nutrition_routine_$TEMPLATE_ID") and hasClickAction()).performScrollTo().performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("nutrition_routine_detail"), 5_000)
        composeRule.onNodeWithTag("nutrition_routine_allergens").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("nutrition_routine_meal_0").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("nutrition_routine_no_purchase").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("nutrition_routines_back").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("nutrition_routine_$TEMPLATE_ID"), 5_000)

        // Sin red: la misma lista sigue visible desde la caché cifrada, con su antigüedad.
        composeRule.onNodeWithTag("nutrition_routines_back").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), 5_000)
        openRoutinesFromMenu()
        composeRule.waitUntilExactlyOneExists(hasTestTag("nutrition_routines_problem"), 5_000)
        composeRule.onNodeWithTag("nutrition_routines_synced_at").assertIsDisplayed()
        composeRule.onNodeWithTag("nutrition_routine_$TEMPLATE_ID").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun sinHabitosAlimenticiosLaListaAvisaQueNoSeVerificaronRestricciones() {
        login()
        val body = ROUTINES
            .replace("\"personalized\":true", "\"personalized\":false")
            .replace("\"missing_context\":[]", "\"missing_context\":[\"nutrition_habits\"]")
            .replace("\"notices\":[\"NOT_MEDICAL_ADVICE\"]", "\"notices\":[\"RESTRICTIONS_NOT_VERIFIED\",\"NOT_MEDICAL_ADVICE\"]")
            .replace("\"reasons\":[\"RESTRICTION_COMPATIBLE\",\"GOAL_MATCH\"]", "\"reasons\":[]")
        openRoutinesFromMenu(json(body))

        composeRule.waitUntilExactlyOneExists(hasTestTag("nutrition_routines_restrictions_not_verified"), 5_000)
        composeRule.onNodeWithTag("nutrition_routines_not_personalized").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("nutrition_routines_personalized").assertDoesNotExist()
        composeRule.onNodeWithTag("nutrition_routines_missing_nutrition_habits", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("nutrition_routines_not_medical_advice").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun sinRedYSinCacheInformaQueAunNoSeSincronizo() {
        login()
        openRoutinesFromMenu()

        composeRule.waitUntilExactlyOneExists(hasTestTag("nutrition_routines_never_synced"), 5_000)
        composeRule.onNodeWithTag("nutrition_routines_empty").assertDoesNotExist()
    }

    @Test
    fun sinLaCapacidadLaOpcionNoApareceYLaRutaDirectaSeRechaza() {
        login(CAPABILITIES.replace(""","nutrition.routines.suggest"""", ""))

        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("menu_nutrition.routines.suggest").assertDoesNotExist()

        composeRule.runOnIdle { navController.navigate(Destinations.NUTRITION_ROUTINES) }
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), 5_000)
        composeRule.onNodeWithTag("nutrition_routines_screen").assertDoesNotExist()
        assertEquals("solo inicio de sesión y capacidades", 2, server.requestCount)
    }

    @Test
    fun unaRespuesta403CapabilityRequiredSeInforma() {
        login()
        openRoutinesFromMenu(json("""{"code":"capability_required","message":"m","correlation_id":"c-1"}""", status = 403))

        composeRule.waitUntilExactlyOneExists(hasText("Tu plan actual no incluye las rutinas de alimentación sugeridas."), 5_000)
    }

    private companion object {
        const val USER_ID = "7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b"
        const val TEMPLATE_ID = "7d1e2f30-4a5b-4c6d-8e7f-90a1b2c3d4e5"
        const val SESSION =
            """{"token_type":"Bearer","access_token":"access-sintetico","expires_in":900,""" +
                """"refresh_token":"refresh-token-sintetico-000000000001","refresh_expires_in":604800,""" +
                """"user":{"user_id":"$USER_ID","full_name":"Ana Deportista",""" +
                """"email":"ana@example.com","actor_type":"athlete"}}"""
        const val CAPABILITIES =
            """{"actor_type":"athlete","subscription_status":"ACTIVE","plan_code":"FREE","plan_version":1,""" +
                """"catalog_version":"plans-2026-10-11.v1","authorization_policy_version":"actor-permissions.v1",""" +
                """"permissions":[],"capabilities":["training.session.record","nutrition.routines.suggest"],""" +
                """"evaluated_at":"2026-10-12T12:00:00Z"}"""
        const val ROUTINES =
            """{"items":[{"reference":{"type":"nutrition_routine_template","id":"$TEMPLATE_ID"},"title":"Desayunos para fondo",""" +
                """"summary":"Comidas del día tipo para semanas de carga en carrera.",""" +
                """"reasons":["RESTRICTION_COMPATIBLE","GOAL_MATCH"],"template_id":"$TEMPLATE_ID",""" +
                """"professional_id":"2e3f4a5b-6c7d-4e8f-9a0b-1c2d3e4f5a6b","goal":"PERFORMANCE","sport":"running",""" +
                """"dietary_pattern":"VEGETARIAN","contains_allergens":["MILK"],"duration_days":14,"daily_meals":[""" +
                """{"meal":"BREAKFAST","items":[{"food_code":"OATS","portions":1}]},""" +
                """{"meal":"HYDRATION","items":[{"food_code":"WATER","portions":4}]}],""" +
                """"published_at":"2026-10-20T14:00:00Z","author_name":"Marta Ruiz"}],""" +
                """"personalized":true,"missing_context":[],"notices":["NOT_MEDICAL_ADVICE"],"generated_at":"2026-10-27T13:00:00Z",""" +
                """"sources":[{"source":"users","status":"OK"}]}"""
    }
}
