package com.lfnj.sport_app.features.identity.login

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lfnj.sport_app.ui.theme.SportappTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var submits = 0

    private fun show(state: LoginUiState) = composeRule.setContent {
        SportappTheme {
            LoginScreen(
                state = state,
                onEmailChange = {}, onPasswordChange = {}, onTogglePasswordVisibility = {},
                onSubmit = { submits++ }, onOpenStatus = {},
            )
        }
    }

    @Test
    fun estadoNormalPermiteEnviar() {
        show(LoginUiState(email = "ana@example.com", password = "x"))

        composeRule.onNodeWithText("Aplicación para deportistas.", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("login_submit").performClick()
        assertEquals(1, submits)
    }

    @Test
    fun cargaDeshabilitaElEnvio() {
        show(LoginUiState(email = "ana@example.com", password = "x", submitting = true))

        composeRule.onNodeWithTag("login_loading").assertIsDisplayed()
        composeRule.onNodeWithTag("login_submit").assertIsNotEnabled()
    }

    @Test
    fun credencialesIncorrectasMuestranMensajeGenerico() {
        show(LoginUiState(error = LoginError.InvalidCredentials))

        composeRule.onNodeWithText("Correo o contraseña incorrectos.").assertIsDisplayed()
    }

    @Test
    fun actorNoDeportistaMuestraMensajeControlado() {
        show(LoginUiState(error = LoginError.ActorNotAllowed))

        composeRule.onNodeWithText("Esta aplicación es exclusiva para deportistas.", substring = true).assertIsDisplayed()
    }

    @Test
    fun demasiadosIntentosMuestraLaEspera() {
        show(LoginUiState(error = LoginError.TooManyAttempts(2)))

        composeRule.onNodeWithText("Demasiados intentos. Intenta de nuevo en 2 minutos.").assertIsDisplayed()
    }

    @Test
    fun servicioNoDisponibleMuestraReferencia() {
        show(LoginUiState(error = LoginError.Unavailable("c-123")))

        composeRule.onNodeWithText("Código de referencia: c-123", substring = true).assertIsDisplayed()
    }

    @Test
    fun sinConexion() {
        show(LoginUiState(error = LoginError.NoConnection))

        composeRule.onNodeWithText("No hay conexión.", substring = true).assertIsDisplayed()
    }

    @Test
    fun erroresDeCampo() {
        show(LoginUiState(emailError = FieldError.EMPTY, passwordError = FieldError.TOO_LONG))

        composeRule.onNodeWithText("Ingresa tu correo electrónico.").assertIsDisplayed()
        composeRule.onNodeWithText("La contraseña no puede superar 128 caracteres.").assertIsDisplayed()
    }

    @Test
    fun sesionVencida() {
        show(LoginUiState(notice = LoginNotice.SESSION_EXPIRED))

        composeRule.onNodeWithText("Tu sesión expiró. Inicia sesión de nuevo.").assertIsDisplayed()
    }

    @Test
    fun hu004Ca6CierreRemotoNoConfirmado() {
        show(LoginUiState(notice = LoginNotice.LOGOUT_REMOTE_UNCONFIRMED))

        composeRule.onNodeWithTag("login_notice").assertIsDisplayed()
        composeRule.onNodeWithText("no pudimos confirmar el cierre con el servidor", substring = true).assertIsDisplayed()
    }

    @Test
    fun hu004Ca5CierreConfirmadoInformaLaSesionCerradaEnElDispositivo() {
        show(LoginUiState(notice = LoginNotice.LOGGED_OUT))

        composeRule.onNodeWithText("Cerraste sesión en este dispositivo.").assertIsDisplayed()
        composeRule.onNodeWithTag("login_notice").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
    }

    @Test
    fun hu004Ca6RevocacionPendienteSeInformaSinBloquearElAcceso() {
        show(LoginUiState(notice = LoginNotice.LOGOUT_REMOTE_PENDING))

        composeRule.onNodeWithText("quedó pendiente y se completará automáticamente", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("login_notice").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        composeRule.onNodeWithTag("login_submit").assertIsEnabled()
    }
}
