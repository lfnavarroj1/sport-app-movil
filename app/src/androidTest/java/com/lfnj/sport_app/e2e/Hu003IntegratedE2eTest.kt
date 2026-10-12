package com.lfnj.sport_app.e2e

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lfnj.sport_app.MainActivity
import com.lfnj.sport_app.SportApp
import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.security.EncryptedTokenStore
import com.lfnj.sport_app.core.session.RestoreOutcome
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E de HU003: aplicación real (MainActivity, Keystore y URL de la variante) → `bff-mobile` →
 * `users_management` en la composición local. Requiere cuentas sintéticas creadas como
 * precondición y se omite si no se reciben como argumentos de instrumentación:
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.lfnj.sport_app.e2e.Hu003IntegratedE2eTest \
 *   -Pandroid.testInstrumentationRunnerArguments.e2eAthleteEmail=<correo> \
 *   -Pandroid.testInstrumentationRunnerArguments.e2eOrganizerEmail=<correo> \
 *   -Pandroid.testInstrumentationRunnerArguments.e2eServiceProviderEmail=<correo> \
 *   -Pandroid.testInstrumentationRunnerArguments.e2ePassword=<contraseña sintética>
 * ```
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class Hu003IntegratedE2eTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val args = InstrumentationRegistry.getArguments()
    private val password: String? = args.getString("e2ePassword")
    private val store = EncryptedTokenStore(context)

    @Before
    fun setUp() {
        assumeTrue("E2E omitido: faltan las cuentas sintéticas", password != null && args.getString("e2eAthleteEmail") != null)
        // Las pruebas comparten el proceso: se elimina también la sesión en memoria del contenedor.
        runBlocking { (context as SportApp).container.sessionManager.clearLocalSession() }
        store.clear()
    }

    private fun account(key: String): String = requireNotNull(args.getString(key)) { "Falta el argumento $key" }

    private fun loginWith(email: String, secret: String = requireNotNull(password)) {
        composeRule.waitUntilExactlyOneExists(hasTestTag("login_email"), TIMEOUT)
        composeRule.onNodeWithTag("login_email").performTextInput(email)
        composeRule.onNodeWithTag("login_password").performTextInput(secret)
        composeRule.onNodeWithTag("login_submit").performClick()
    }

    /** Espera a que el inicio del deportista termine de consultar el plan (activo o en aprovisionamiento). */
    private fun awaitPlanResolved() {
        composeRule.waitUntil(TIMEOUT) {
            composeRule.onAllNodes(hasText("Plan ", substring = true).or(hasText("Estamos activando tu plan", substring = true)))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun ca6Ca7DeportistaIngresaAAthleteHomeYGuardaLaSesionCifrada() {
        ActivityScenario.launch(MainActivity::class.java).use {
            loginWith(account("e2eAthleteEmail"))

            composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), TIMEOUT)
            awaitPlanResolved()
            composeRule.onNodeWithTag("open_menu").performClick()
            composeRule.onNodeWithTag("menu_home").assertIsDisplayed()
        }
        val raw = context.getSharedPreferences(EncryptedTokenStore.PREFERENCES_FILE, Context.MODE_PRIVATE)
            .all.values.joinToString()
        val token = store.read()
        assertNotNull("El refresh token debe quedar guardado", token)
        assertFalse("Las preferencias no deben contener el token en claro", raw.contains(token!!))
    }

    @Test
    fun recuperaLaSesionDesdeElAlmacenamientoCifradoConElBffReal() {
        ActivityScenario.launch(MainActivity::class.java).use {
            loginWith(account("e2eAthleteEmail"))
            composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), TIMEOUT)
        }
        val saved = requireNotNull(store.read())

        // Arranque en frío: un SessionManager nuevo solo conoce el refresh token cifrado.
        val config = (context as SportApp).container.config
        val api = BffMobileClient.retrofit(config.bffMobileBaseUrl).create(SessionApi::class.java)
        val outcome = runBlocking { SessionManager(api, EncryptedTokenStore(context), BffMobileClient.json).restore() }

        assertTrue("Se esperaba Restored y se obtuvo $outcome", outcome is RestoreOutcome.Restored)
        assertNotEquals("El BFF debe rotar el refresh token", saved, store.read())

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), TIMEOUT)
        }
    }

    @Test
    fun ca11OrganizadorEsRechazadoSinSesion() = assertRejected(account("e2eOrganizerEmail"))

    @Test
    fun ca11ProfesionalEsRechazadoSinSesion() = assertRejected(account("e2eServiceProviderEmail"))

    @Test
    fun ca2ContrasenaIncorrectaMuestraMensajeGenerico() {
        ActivityScenario.launch(MainActivity::class.java).use {
            loginWith(account("e2eAthleteEmail"), secret = "Incorrecta-${System.nanoTime()}")

            composeRule.waitUntilExactlyOneExists(hasText("Correo o contraseña incorrectos."), TIMEOUT)
        }
        assertNull(store.read())
    }

    private fun assertRejected(email: String) {
        ActivityScenario.launch(MainActivity::class.java).use {
            loginWith(email)

            composeRule.waitUntilExactlyOneExists(hasTestTag("login_error"), TIMEOUT)
            composeRule.onNodeWithText("Esta aplicación es exclusiva para deportistas.", substring = true).assertIsDisplayed()
            composeRule.onNodeWithTag("athlete_home").assertDoesNotExist()
        }
        assertNull("No debe quedar ninguna credencial guardada", store.read())
    }

    private companion object {
        const val TIMEOUT = 20_000L
    }
}
