package com.lfnj.sport_app.e2e

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
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
 * E2E de HU040: aplicación real (MainActivity, Room, Keystore y URL de la variante) → `bff-mobile`
 * ≥ 0.18.0 → `complementary_services` 0.6.0 y Afiliación en la composición local. Precondición: un
 * profesional publicó por `bff-web` el servicio `COACHING` `VIRTUAL` «E2E HU040 <sufijo>» con un
 * intervalo que empieza en 3 días o más y otro que empieza en unas 2 h; el deportista `PREMIUM`
 * ya reservó el de 2 h (para comprobar que no puede cancelarlo) y el deportista `INTERMEDIATE` no
 * tiene la capacidad. Datos sintéticos; se omite si no se reciben los argumentos:
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.lfnj.sport_app.e2e.Hu040IntegratedE2eTest \
 *   -Pandroid.testInstrumentationRunnerArguments.e2eAthleteEmail=<correo PREMIUM> \
 *   -Pandroid.testInstrumentationRunnerArguments.e2eIntermediateEmail=<correo INTERMEDIATE> \
 *   -Pandroid.testInstrumentationRunnerArguments.e2ePassword=<contraseña sintética> \
 *   -Pandroid.testInstrumentationRunnerArguments.e2eServiceSuffix=<sufijo>
 * ```
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class Hu040IntegratedE2eTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val args = InstrumentationRegistry.getArguments()

    @Before
    fun setUp() {
        assumeTrue(
            "E2E omitido: faltan las cuentas sintéticas o el sufijo del servicio publicado",
            listOf("e2ePassword", "e2eAthleteEmail", "e2eIntermediateEmail", SUFFIX).all { args.getString(it) != null },
        )
        // Las pruebas comparten el proceso: se elimina también la sesión en memoria del contenedor.
        runBlocking { (context as SportApp).container.sessionManager.clearLocalSession() }
        EncryptedTokenStore(context).clear()
    }

    private fun tagPrefix(prefix: String) = SemanticsMatcher("testTag empieza por $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    private fun arg(key: String): String = requireNotNull(args.getString(key)) { "Falta el argumento $key" }

    private fun login(email: String) {
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
    }

    @Test
    fun ca1Ca4Ca6ElDeportistaPremiumReservaVeLaCitaLaCancelaATiempoYNoPuedeCancelarLaDe2Horas() {
        ActivityScenario.launch(MainActivity::class.java).use {
            login(arg("e2eAthleteEmail"))
            composeRule.onNodeWithTag("athlete_menu").performScrollToNode(hasTestTag("menu_services.virtual_sessions.schedule"))
            composeRule.onNodeWithTag("menu_services.virtual_sessions.schedule").performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag("virtual_sessions_screen"), TIMEOUT)

            val service = hasText("E2E HU040 ${arg(SUFFIX)}") and hasClickAction()
            composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodes(service).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNode(service).performScrollTo().performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag("virtual_service_detail"), TIMEOUT)

            // CA1: el primer intervalo reservable (≥ 3 días) se confirma con conectividad.
            val book = composeRule.onAllNodes(tagPrefix("virtual_slot_book_"))
            composeRule.waitUntil(TIMEOUT) { book.fetchSemanticsNodes().isNotEmpty() }
            book[0].performScrollTo().performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag("virtual_booking_dialog"), TIMEOUT)
            composeRule.onNodeWithTag("virtual_booking_confirm").performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag("virtual_sessions_booked_notice"), TIMEOUT)

            // CA6: la cita es identificable y el enlace se explica; CA4: se cancela con 24 h o más.
            val cancel = composeRule.onAllNodes(tagPrefix("virtual_appointment_cancel_"))
            composeRule.waitUntil(TIMEOUT) { cancel.fetchSemanticsNodes().isNotEmpty() }
            composeRule.onAllNodes(tagPrefix("virtual_appointment_meeting_"))[0].performScrollTo().assertIsDisplayed()
            cancel[0].performScrollTo().performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag("virtual_cancel_dialog"), TIMEOUT)
            composeRule.onNodeWithTag("virtual_cancel_confirm").performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag("virtual_sessions_cancelled_notice"), TIMEOUT)

            // CA4: la cita sembrada a 2 h no ofrece cancelar y explica la ventana.
            val closed = composeRule.onAllNodes(tagPrefix("virtual_appointment_window_closed_"))
            composeRule.waitUntil(TIMEOUT) { closed.fetchSemanticsNodes().isNotEmpty() }
            closed[0].performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun ca3UnDeportistaIntermedioNoVeLaOpcion() {
        ActivityScenario.launch(MainActivity::class.java).use {
            login(arg("e2eIntermediateEmail"))
            composeRule.onNodeWithTag("athlete_menu").assertIsDisplayed()
            composeRule.onNodeWithTag("menu_services.virtual_sessions.schedule").assertDoesNotExist()
        }
    }

    private companion object {
        const val TIMEOUT = 20_000L
        const val SUFFIX = "e2eServiceSuffix"
    }
}
