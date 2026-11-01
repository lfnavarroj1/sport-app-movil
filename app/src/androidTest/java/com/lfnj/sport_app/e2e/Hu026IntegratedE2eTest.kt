package com.lfnj.sport_app.e2e

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lfnj.sport_app.MainActivity
import com.lfnj.sport_app.SportApp
import com.lfnj.sport_app.core.security.EncryptedTokenStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E de HU026: aplicación real (MainActivity, Room, Keystore y URL de la variante) → `bff-mobile` →
 * `training_management` y `users_management` en la composición local. Precondición: un organizador
 * publicó por `bff-web`, para un deporte del deportista, «E2E HU026 Ciudad <sufijo>» en la ciudad
 * del deportista y más larga, «E2E HU026 Corta <sufijo>» en otra ciudad y más corta, y
 * «E2E HU026 Retirada <sufijo>», que luego retiró; el deportista registró ese deporte (HU006) y su
 * ciudad (HU005). Datos sintéticos; se omite si no se reciben los argumentos:
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.lfnj.sport_app.e2e.Hu026IntegratedE2eTest \
 *   -Pandroid.testInstrumentationRunnerArguments.e2eAthleteEmail=<correo> \
 *   -Pandroid.testInstrumentationRunnerArguments.e2ePassword=<contraseña sintética> \
 *   -Pandroid.testInstrumentationRunnerArguments.e2eRouteSuffix=<sufijo>
 * ```
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class Hu026IntegratedE2eTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val args = InstrumentationRegistry.getArguments()

    @Before
    fun setUp() {
        assumeTrue(
            "E2E omitido: faltan la cuenta sintética o el sufijo de las rutas publicadas",
            listOf("e2ePassword", "e2eAthleteEmail", SUFFIX).all { args.getString(it) != null },
        )
        // Las pruebas comparten el proceso: se elimina también la sesión en memoria del contenedor.
        runBlocking { (context as SportApp).container.sessionManager.clearLocalSession() }
        EncryptedTokenStore(context).clear()
    }

    private fun arg(key: String): String = requireNotNull(args.getString(key)) { "Falta el argumento $key" }

    private fun loginAndOpenRoutes() {
        composeRule.waitUntilExactlyOneExists(hasTestTag("login_email"), TIMEOUT)
        composeRule.onNodeWithTag("login_email").performTextInput(arg("e2eAthleteEmail"))
        composeRule.onNodeWithTag("login_password").performTextInput(arg("e2ePassword"))
        composeRule.onNodeWithTag("login_submit").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), TIMEOUT)
        // La opción depende de las capacidades vigentes del plan FREE: se espera a que el inicio las resuelva.
        composeRule.waitUntil(TIMEOUT) {
            composeRule.onAllNodes(hasText("Plan ", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("athlete_menu").performScrollToNode(hasTestTag("menu_training.routes.suggest"))
        composeRule.onNodeWithTag("menu_training.routes.suggest").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("route_suggestions_screen"), TIMEOUT)
    }

    @Test
    fun ca3Ca4ElDeportistaVeLasRutasDeSuDeporteConSuCiudadPrimeroYNoVeLaRetirada() {
        ActivityScenario.launch(MainActivity::class.java).use {
            loginAndOpenRoutes()

            val city = hasText("E2E HU026 Ciudad ${arg(SUFFIX)}") and hasClickAction()
            val short = hasText("E2E HU026 Corta ${arg(SUFFIX)}") and hasClickAction()
            composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodes(city).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithTag("route_suggestions_personalized").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("route_suggestions_safety").performScrollTo().assertIsDisplayed()

            // CA3: primero la de su ciudad aunque sea más larga, en el orden que confirmó el BFF.
            composeRule.onNode(short).performScrollTo()
            val cityTop = composeRule.onNode(city).fetchSemanticsNode().positionInRoot.y
            val shortTop = composeRule.onNode(short).fetchSemanticsNode().positionInRoot.y
            assertTrue("La ruta de su ciudad debe aparecer antes ($cityTop < $shortTop)", cityTop < shortTop)

            // CA4: la ruta retirada no se sugiere.
            composeRule.onNode(hasText("E2E HU026 Retirada ${arg(SUFFIX)}")).assertDoesNotExist()

            // Distancia, dificultad, autor y motivo; en el detalle, el punto de inicio y la apertura en mapas.
            composeRule.onNode(city).performScrollTo()
            composeRule.onNode(city and hasText("Está en tu ciudad")).assertExists()
            composeRule.onNode(city and hasText("Coincide con tu deporte")).assertExists()
            composeRule.onNode(city and hasText("Por ", substring = true)).assertExists()
            composeRule.onNode(city and hasText(" km · Dificultad ", substring = true)).assertExists()
            composeRule.onNode(city).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag("route_suggestion_detail"), TIMEOUT)
            composeRule.onNodeWithTag("route_suggestion_start_point").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("route_suggestion_open_map").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("route_suggestions_back").performClick()
            composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodes(city).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    private companion object {
        const val TIMEOUT = 20_000L
        const val SUFFIX = "e2eRouteSuffix"
    }
}
