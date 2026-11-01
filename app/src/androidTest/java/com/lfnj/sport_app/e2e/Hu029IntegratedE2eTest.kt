package com.lfnj.sport_app.e2e

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
 * E2E de HU029: aplicación real (MainActivity, Room, Keystore y URL de la variante) → `bff-mobile`
 * ≥ 0.13.0 → `complementary_services`, `events_management` y Usuarios en la composición local.
 * Precondición: un organizador publicó un evento dentro de los próximos 31 días y el deportista
 * `FREE` se inscribió (HU041); un profesional publicó por `bff-web` el servicio `TRANSPORT`
 * «E2E HU029 Transporte <sufijo>» con un intervalo disponible el día de ese evento (en su zona) y el
 * servicio `COACHING` «E2E HU029 Entrenamiento <sufijo>» con un intervalo disponible en 31 días. El
 * segundo deportista `FREE` no tiene inscripciones vigentes. Datos sintéticos; se omite si no se
 * reciben los argumentos:
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.lfnj.sport_app.e2e.Hu029IntegratedE2eTest \
 *   -Pandroid.testInstrumentationRunnerArguments.e2eAthleteEmail=<correo con inscripción> \
 *   -Pandroid.testInstrumentationRunnerArguments.e2eUnregisteredEmail=<correo sin inscripciones> \
 *   -Pandroid.testInstrumentationRunnerArguments.e2ePassword=<contraseña sintética> \
 *   -Pandroid.testInstrumentationRunnerArguments.e2eServiceSuffix=<sufijo>
 * ```
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class Hu029IntegratedE2eTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val args = InstrumentationRegistry.getArguments()

    @Before
    fun setUp() {
        assumeTrue(
            "E2E omitido: faltan las cuentas sintéticas o el sufijo de los servicios publicados",
            listOf("e2ePassword", "e2eAthleteEmail", "e2eUnregisteredEmail", SUFFIX).all { args.getString(it) != null },
        )
        // Las pruebas comparten el proceso: se elimina también la sesión en memoria del contenedor.
        runBlocking { (context as SportApp).container.sessionManager.clearLocalSession() }
        EncryptedTokenStore(context).clear()
    }

    private fun arg(key: String): String = requireNotNull(args.getString(key)) { "Falta el argumento $key" }

    private fun openSupportSuggestions(email: String) {
        composeRule.waitUntilExactlyOneExists(hasTestTag("login_email"), TIMEOUT)
        composeRule.onNodeWithTag("login_email").performTextInput(email)
        composeRule.onNodeWithTag("login_password").performTextInput(arg("e2ePassword"))
        composeRule.onNodeWithTag("login_submit").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), TIMEOUT)
        // Las opciones dependen de las capacidades vigentes del plan: se espera a que el inicio las resuelva.
        composeRule.waitUntil(TIMEOUT) {
            composeRule.onAllNodes(hasText("Plan ", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("athlete_menu").performScrollToNode(hasTestTag("menu_services.support.suggest"))
        composeRule.onNodeWithTag("menu_services.support.suggest").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("support_suggestions_screen"), TIMEOUT)
        composeRule.waitUntil(TIMEOUT) {
            composeRule.onAllNodes(hasTestTag("support_suggestions_synced_at")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun ca1Ca3ElDeportistaInscritoVePrimeroElTransporteDelDiaDeSuEventoConSuMotivoYDetalle() {
        ActivityScenario.launch(MainActivity::class.java).use {
            openSupportSuggestions(arg("e2eAthleteEmail"))
            val transport = hasText("E2E HU029 Transporte ${arg(SUFFIX)}") and hasClickAction()
            val coaching = hasText("E2E HU029 Entrenamiento ${arg(SUFFIX)}") and hasClickAction()
            composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodes(transport).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithTag("support_suggestions_personalized").assertIsDisplayed()

            // CA1: el motivo logístico precede a la disponibilidad próxima.
            val transportTop = composeRule.onNode(transport).fetchSemanticsNode().boundsInRoot.top
            val coachingTop = composeRule.onNode(coaching).fetchSemanticsNode().boundsInRoot.top
            assertTrue("el transporte del día del evento va antes que el entrenamiento", transportTop < coachingTop)
            // Cada tarjeta fusiona sus textos: el motivo pertenece a la tarjeta de ese servicio.
            composeRule.onNode(transport and hasText("Disponible el día de tu evento", substring = true)).performScrollTo().assertIsDisplayed()
            composeRule.onNode(coaching and hasText("Disponibilidad en los próximos 31 días", substring = true)).performScrollTo().assertIsDisplayed()

            // CA3: el detalle informa proveedor, categoría, modalidad, tarifa, intervalo y motivo, sin contratar.
            composeRule.onNode(transport).performScrollTo().performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag("support_suggestion_detail"), TIMEOUT)
            composeRule.onNodeWithText("Categoría: Transporte").assertIsDisplayed()
            composeRule.onNodeWithTag("support_suggestion_fee").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("support_suggestion_detail_interval").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("support_suggestion_hire_info").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun ca2SinInscripcionesLaListaExplicaElContextoYNoSugiereTransporte() {
        ActivityScenario.launch(MainActivity::class.java).use {
            openSupportSuggestions(arg("e2eUnregisteredEmail"))
            composeRule.waitUntilExactlyOneExists(hasTestTag("support_suggestions_not_personalized"), TIMEOUT)
            composeRule.onNodeWithTag("support_suggestions_missing_event_registrations", useUnmergedTree = true).assertIsDisplayed()
            composeRule.onNode(hasText("E2E HU029 Transporte ${arg(SUFFIX)}")).assertDoesNotExist()
        }
    }

    private companion object {
        const val TIMEOUT = 20_000L
        const val SUFFIX = "e2eServiceSuffix"
    }
}
