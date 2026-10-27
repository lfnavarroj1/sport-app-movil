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
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E de HU030: aplicación real (MainActivity, Room, Keystore y URL de la variante) → `bff-mobile` →
 * `users_management` (restricciones HU009) y `training_management` en la composición local.
 * Precondición: un profesional publicó por `bff-web` dos rutinas omnívoras, «E2E HU030 Compatible
 * <sufijo>» sin alérgenos y «E2E HU030 Leche <sufijo>» con `MILK`; el primer deportista registró sus
 * hábitos alimenticios (HU009) con patrón omnívoro y alergia `MILK`; el segundo no registró hábitos.
 * Datos sintéticos; se omite si no se reciben los argumentos:
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.lfnj.sport_app.e2e.Hu030IntegratedE2eTest \
 *   -Pandroid.testInstrumentationRunnerArguments.e2eAthleteEmail=<correo con hábitos y alergia MILK> \
 *   -Pandroid.testInstrumentationRunnerArguments.e2eNoHabitsEmail=<correo sin hábitos alimenticios> \
 *   -Pandroid.testInstrumentationRunnerArguments.e2ePassword=<contraseña sintética> \
 *   -Pandroid.testInstrumentationRunnerArguments.e2eRoutineSuffix=<sufijo>
 * ```
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class Hu030IntegratedE2eTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val args = InstrumentationRegistry.getArguments()

    @Before
    fun setUp() {
        assumeTrue(
            "E2E omitido: faltan las cuentas sintéticas o el sufijo de las rutinas publicadas",
            listOf("e2ePassword", "e2eAthleteEmail", "e2eNoHabitsEmail", SUFFIX).all { args.getString(it) != null },
        )
        // Las pruebas comparten el proceso: se elimina también la sesión en memoria del contenedor.
        runBlocking { (context as SportApp).container.sessionManager.clearLocalSession() }
        EncryptedTokenStore(context).clear()
    }

    private fun arg(key: String): String = requireNotNull(args.getString(key)) { "Falta el argumento $key" }

    private fun loginAndOpenRoutines(email: String) {
        composeRule.waitUntilExactlyOneExists(hasTestTag("login_email"), TIMEOUT)
        composeRule.onNodeWithTag("login_email").performTextInput(email)
        composeRule.onNodeWithTag("login_password").performTextInput(arg("e2ePassword"))
        composeRule.onNodeWithTag("login_submit").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), TIMEOUT)
        // La opción depende de las capacidades vigentes del plan FREE: se espera a que el inicio las resuelva.
        composeRule.waitUntil(TIMEOUT) {
            composeRule.onAllNodes(hasText("Plan ", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("athlete_menu").performScrollToNode(hasTestTag("menu_nutrition.routines.suggest"))
        composeRule.onNodeWithTag("menu_nutrition.routines.suggest").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("nutrition_routines_screen"), TIMEOUT)
    }

    @Test
    fun ca1Ca2Ca4ConHabitosSoloVeLaRutinaCompatibleConMotivoYDetalle() {
        ActivityScenario.launch(MainActivity::class.java).use {
            loginAndOpenRoutines(arg("e2eAthleteEmail"))

            val compatible = hasText("E2E HU030 Compatible ${arg(SUFFIX)}") and hasClickAction()
            composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodes(compatible).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithTag("nutrition_routines_personalized").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("nutrition_routines_restrictions_not_verified").assertDoesNotExist()

            // CA2: la rutina con su alérgeno no aparece en la respuesta.
            composeRule.onNode(hasText("E2E HU030 Leche ${arg(SUFFIX)}")).assertDoesNotExist()

            // CA1 y CA4: motivo, autor y, en el detalle, vigencia, alérgenos y comidas.
            composeRule.onNode(compatible).performScrollTo()
            composeRule.onNode(compatible and hasText("Compatible con tus restricciones y alergias")).assertExists()
            composeRule.onNode(compatible and hasText("Por ", substring = true)).assertExists()
            composeRule.onNode(compatible).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag("nutrition_routine_detail"), TIMEOUT)
            composeRule.onNodeWithTag("nutrition_routine_duration").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("nutrition_routine_allergens").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("nutrition_routine_meal_0").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("nutrition_routines_not_medical_advice").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("nutrition_routines_back").performClick()
            composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodes(compatible).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test
    fun ca3SinHabitosLaListaNoEsPersonalizadaYAvisaQueNoSeVerificaronRestricciones() {
        ActivityScenario.launch(MainActivity::class.java).use {
            loginAndOpenRoutines(arg("e2eNoHabitsEmail"))

            composeRule.waitUntilExactlyOneExists(hasTestTag("nutrition_routines_not_personalized"), TIMEOUT)
            composeRule.onNodeWithTag("nutrition_routines_restrictions_not_verified").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("nutrition_routines_missing_nutrition_habits", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("nutrition_routines_not_medical_advice").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("nutrition_routine_reason_RESTRICTION_COMPATIBLE", useUnmergedTree = true).assertDoesNotExist()
        }
    }

    private companion object {
        const val TIMEOUT = 20_000L
        const val SUFFIX = "e2eRoutineSuffix"
    }
}
