package com.lfnj.sport_app.navigation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lfnj.sport_app.core.config.AppConfig
import com.lfnj.sport_app.core.session.RevocationResult
import com.lfnj.sport_app.core.session.SecureTokenStore
import com.lfnj.sport_app.core.session.SessionRevoker
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.test.performScrollToNode
import com.lfnj.sport_app.testing.TestAppContainer
import com.lfnj.sport_app.ui.theme.SportappTheme
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Recorrido de la aplicación (navegación, ViewModels y SessionManager reales) contra un BFF
 * simulado con MockWebServer. Es una prueba de componente con contrato simulado, **no** un E2E.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LoginFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val server = MockWebServer()
    private val store = object : SecureTokenStore {
        var value: String? = null
        override fun read() = value
        override fun write(value: String) { this.value = value }
        override fun clear() { value = null }
    }

    @Before
    fun setUp() = server.start()

    private var container: TestAppContainer? = null

    @After
    fun tearDown() {
        server.shutdown()
        container?.close()
    }

    private lateinit var navController: NavHostController

    private fun launch(revoker: SessionRevoker? = null) {
        val config = AppConfig(server.url("/").toString(), "test", "0.3.0", "0.1.0")
        val container = TestAppContainer(config, store, ApplicationProvider.getApplicationContext(), revoker)
        this.container = container
        composeRule.setContent {
            navController = rememberNavController()
            SportappTheme { AppNavHost(container, navController = navController) }
        }
    }

    private fun session(actor: String?) = MockResponse().setBody(
        """{"token_type":"Bearer","access_token":"access-sintetico","expires_in":900,""" +
            """"refresh_token":"refresh-token-sintetico-000000000001","refresh_expires_in":604800,""" +
            """"user":{"user_id":"7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b","full_name":"Ana Deportista",""" +
            """"email":"ana@example.com"${actor?.let { ""","actor_type":"$it"""" } ?: ""}}}"""
    )

    private val freeCapabilities = MockResponse().setBody(
        """{"actor_type":"athlete","subscription_status":"ACTIVE","plan_code":"FREE","plan_version":1,""" +
            """"catalog_version":"plans-2026-10-11.v1","authorization_policy_version":"actor-permissions.v1",""" +
            """"permissions":[],"capabilities":["training.routes.suggest","events.suggest"],""" +
            """"evaluated_at":"2026-10-12T12:00:00Z"}"""
    )

    private fun loginWith(email: String) {
        composeRule.waitUntilExactlyOneExists(hasTestTag("login_email"), 5_000)
        composeRule.onNodeWithTag("login_email").performTextInput(email)
        composeRule.onNodeWithTag("login_password").performTextInput("Secreta-123")
        composeRule.onNodeWithTag("login_submit").performClick()
    }

    @Test
    fun ca6Ca7DeportistaIngresaAAthleteHomeConElMenuDeSuPlan() {
        launch()
        server.enqueue(session("athlete"))
        server.enqueue(freeCapabilities)

        loginWith("ana@example.com")

        composeRule.waitUntilExactlyOneExists(hasText("Plan FREE activo."), 5_000)
        composeRule.onNodeWithTag("athlete_home").assertIsDisplayed()
        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("menu_events.suggest").assertIsDisplayed()
        assertEquals("refresh-token-sintetico-000000000001", store.value)
    }

    @Test
    fun ca11OrganizadorRecibeMensajeControladoYNoAbreSesion() {
        launch()
        server.enqueue(session("organizer"))

        loginWith("org@example.com")

        composeRule.waitUntilExactlyOneExists(hasTestTag("login_error"), 5_000)
        composeRule.onNodeWithTag("athlete_home").assertDoesNotExist()
        assertNull(store.value)
    }

    @Test
    fun ca11ProfesionalRecibeMensajeControladoYNoAbreSesion() {
        launch()
        server.enqueue(session("service_provider"))

        loginWith("pro@example.com")

        composeRule.waitUntilExactlyOneExists(hasTestTag("login_error"), 5_000)
        composeRule.onNodeWithTag("athlete_home").assertDoesNotExist()
        assertNull(store.value)
    }

    @Test
    fun ca10ActorDesconocidoVaAlAccesoNoAutorizadoSinMenu() {
        launch()
        server.enqueue(session("admin"))

        loginWith("x@example.com")

        composeRule.waitUntilExactlyOneExists(hasTestTag("unauthorized"), 5_000)
        composeRule.onNodeWithTag("open_menu").assertDoesNotExist()
        assertNull(store.value)
    }

    @Test
    fun ca2CredencialesIncorrectasMuestranMensajeGenerico() {
        launch()
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"code":"invalid_credentials","message":"m","correlation_id":null}""")
        )

        loginWith("ana@example.com")

        composeRule.waitUntilExactlyOneExists(hasText("Correo o contraseña incorrectos."), 5_000)
        assertNull(store.value)
    }

    @Test
    fun recuperaLaSesionGuardadaAlAbrirLaAplicacion() {
        store.value = "refresh-token-sintetico-000000000000"
        server.enqueue(session("athlete"))
        server.enqueue(freeCapabilities)

        launch()

        composeRule.waitUntilExactlyOneExists(hasText("Plan FREE activo."), 5_000)
        assertEquals("/v1/sessions/refresh", server.takeRequest().path)
        assertEquals("refresh-token-sintetico-000000000001", store.value)
    }

    // ------------------------------------------------------------------ HU004

    private fun loginAthleteAndOpenLogout(revoker: SessionRevoker) {
        launch(revoker)
        server.enqueue(session("athlete"))
        server.enqueue(freeCapabilities)
        loginWith("ana@example.com")
        composeRule.waitUntilExactlyOneExists(hasText("Plan FREE activo."), 5_000)
        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("athlete_menu").performScrollToNode(hasTestTag("menu_logout"))
        composeRule.onNodeWithTag("menu_logout").performClick()
    }

    @Test
    fun hu004Ca5CierreConfirmadoLimpiaLaSesionYLaPila() {
        val revoked = mutableListOf<String>()
        loginAthleteAndOpenLogout { token -> revoked += token; RevocationResult.CONFIRMED }

        composeRule.waitUntilExactlyOneExists(hasTestTag("login_email"), 5_000)
        composeRule.onNodeWithTag("login_notice").assertDoesNotExist()
        composeRule.onNodeWithTag("athlete_home").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(listOf("refresh-token-sintetico-000000000001"), revoked)
            assertNull(store.value)
            assertNull("Atrás no debe regresar a pantallas protegidas", navController.previousBackStackEntry)
            assertEquals(Destinations.LOGIN, navController.currentDestination?.route?.substringBefore('?'))
        }
    }

    @Test
    fun hu004Ca6CierreSinConfirmacionAvisaYNoConservaElToken() {
        loginAthleteAndOpenLogout { RevocationResult.NOT_CONFIRMED }

        composeRule.waitUntilExactlyOneExists(hasTestTag("login_notice"), 5_000)
        composeRule.onNodeWithText("no pudimos confirmar el cierre con el servidor", substring = true).assertIsDisplayed()
        composeRule.runOnIdle {
            assertNull(store.value)
            assertNull(navController.previousBackStackEntry)
        }
    }
}
