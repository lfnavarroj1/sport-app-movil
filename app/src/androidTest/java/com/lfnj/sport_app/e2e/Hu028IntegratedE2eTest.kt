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
 * E2E de HU028: aplicación real (MainActivity, Room, Keystore y URL de la variante) → `bff-mobile` →
 * `training_management` y `users_management` en la composición local. Precondición: un profesional
 * publicó por `bff-web` dos plantillas de un deporte del deportista, «E2E HU028 Objetivo <sufijo>»
 * con su `primary_goal` y «E2E HU028 Deporte <sufijo>» con otro objetivo, y el deportista registró
 * ese deporte y objetivo (HU006). Datos sintéticos; se omite si no se reciben los argumentos:
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.lfnj.sport_app.e2e.Hu028IntegratedE2eTest \
 *   -Pandroid.testInstrumentationRunnerArguments.e2eAthleteEmail=<correo> \
 *   -Pandroid.testInstrumentationRunnerArguments.e2ePassword=<contraseña sintética> \
 *   -Pandroid.testInstrumentationRunnerArguments.e2eTemplateSuffix=<sufijo>
 * ```
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class Hu028IntegratedE2eTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val args = InstrumentationRegistry.getArguments()

    @Before
    fun setUp() {
        assumeTrue(
            "E2E omitido: faltan la cuenta sintética o el sufijo de las plantillas publicadas",
            listOf("e2ePassword", "e2eAthleteEmail", SUFFIX).all { args.getString(it) != null },
        )
        // Las pruebas comparten el proceso: se elimina también la sesión en memoria del contenedor.
        runBlocking { (context as SportApp).container.sessionManager.clearLocalSession() }
        EncryptedTokenStore(context).clear()
    }

    private fun arg(key: String): String = requireNotNull(args.getString(key)) { "Falta el argumento $key" }

    private fun loginAndOpenSuggestions() {
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
        composeRule.onNodeWithTag("athlete_menu").performScrollToNode(hasTestTag("menu_training.plans.suggest"))
        composeRule.onNodeWithTag("menu_training.plans.suggest").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("plan_suggestions_screen"), TIMEOUT)
    }

    @Test
    fun ca1Ca4ElDeportistaVeLasPlantillasPublicadasDeSuDeporteConSuObjetivoPrimeroYAbreElDetalle() {
        ActivityScenario.launch(MainActivity::class.java).use {
            loginAndOpenSuggestions()

            val goal = hasText("E2E HU028 Objetivo ${arg(SUFFIX)}") and hasClickAction()
            val sport = hasText("E2E HU028 Deporte ${arg(SUFFIX)}") and hasClickAction()
            composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodes(goal).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithTag("plan_suggestions_personalized").assertIsDisplayed()

            // CA1: primero las que coinciden con el objetivo, en el orden que confirmó el BFF.
            composeRule.onNode(sport).performScrollTo()
            val goalTop = composeRule.onNode(goal).fetchSemanticsNode().positionInRoot.y
            val sportTop = composeRule.onNode(sport).fetchSemanticsNode().positionInRoot.y
            assertTrue("La plantilla de su objetivo debe aparecer antes ($goalTop < $sportTop)", goalTop < sportTop)

            // CA4: autor, motivo y, en el detalle, sesiones semanales.
            composeRule.onNode(goal).performScrollTo()
            composeRule.onNode(goal and hasText("Coincide con tu objetivo principal")).assertExists()
            composeRule.onNode(goal and hasText("Por ", substring = true)).assertExists()
            composeRule.onNode(goal).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag("plan_suggestion_detail"), TIMEOUT)
            composeRule.onNodeWithTag("plan_suggestion_duration").assertIsDisplayed()
            composeRule.onNodeWithTag("plan_suggestion_session_0").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("plan_suggestion_authorize_info").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("plan_suggestions_back").performClick()
            composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodes(goal).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    private companion object {
        const val TIMEOUT = 20_000L
        const val SUFFIX = "e2eTemplateSuffix"
    }
}
