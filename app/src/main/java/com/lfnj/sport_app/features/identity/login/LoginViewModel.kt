package com.lfnj.sport_app.features.identity.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lfnj.sport_app.core.session.LoginOutcome
import com.lfnj.sport_app.navigation.Destinations
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Puerto hacia la sesión; en producción es `SessionManager::login`. */
fun interface LoginGateway {
    suspend fun login(email: String, password: String): LoginOutcome
}

enum class FieldError { EMPTY, TOO_LONG }

sealed interface LoginError {
    /** Mismo mensaje para contraseña errada, correo inexistente y cuenta deshabilitada (CA2, CA3). */
    data object InvalidCredentials : LoginError
    /** Cuenta de organizador o profesional: la aplicación móvil es exclusiva para deportistas (CA11). */
    data object ActorNotAllowed : LoginError
    data class TooManyAttempts(val waitMinutes: Long?) : LoginError
    data object InvalidInput : LoginError
    data object NoConnection : LoginError
    data class Unavailable(val correlationId: String?) : LoginError
    data object NotConfigured : LoginError
}

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val emailError: FieldError? = null,
    val passwordError: FieldError? = null,
    val submitting: Boolean = false,
    val error: LoginError? = null,
    /** Aviso con el que se llegó al acceso; se retira en el siguiente intento. */
    val notice: LoginNotice? = null,
    /** Destino al que navegar una sola vez; la pantalla llama a [LoginViewModel.onNavigated]. */
    val navigateTo: String? = null,
) {
    override fun toString(): String =
        "LoginUiState(emailError=$emailError, passwordError=$passwordError, submitting=$submitting, " +
            "error=$error, notice=$notice, navigateTo=$navigateTo)"
}

class LoginViewModel(
    private val gateway: LoginGateway,
    notice: LoginNotice? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LoginUiState(notice = notice))
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun onEmailChange(value: String) = _uiState.update { it.copy(email = value, emailError = null, error = null) }

    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, passwordError = null, error = null) }

    fun onTogglePasswordVisibility() = _uiState.update { it.copy(passwordVisible = !it.passwordVisible) }

    fun onNavigated() = _uiState.update { it.copy(navigateTo = null) }

    /** Ignora envíos repetidos mientras hay una solicitud en curso. */
    fun submit() {
        val current = _uiState.value
        if (current.submitting) return
        val email = current.email.trim()
        val emailError = when {
            email.isEmpty() -> FieldError.EMPTY
            email.length > MAX_EMAIL_LENGTH -> FieldError.TOO_LONG
            else -> null
        }
        val passwordError = when {
            current.password.isEmpty() -> FieldError.EMPTY
            current.password.length > MAX_PASSWORD_LENGTH -> FieldError.TOO_LONG
            else -> null
        }
        if (emailError != null || passwordError != null) {
            _uiState.update { it.copy(emailError = emailError, passwordError = passwordError, error = null) }
            return
        }
        _uiState.update { it.copy(submitting = true, error = null, notice = null) }
        viewModelScope.launch {
            val outcome = gateway.login(email, current.password)
            _uiState.update { state -> state.after(outcome) }
        }
    }

    private fun LoginUiState.after(outcome: LoginOutcome): LoginUiState = when (outcome) {
        is LoginOutcome.Authenticated ->
            copy(submitting = false, password = "", navigateTo = Destinations.ATHLETE_HOME)
        LoginOutcome.UnsupportedActor ->
            copy(submitting = false, password = "", navigateTo = Destinations.UNAUTHORIZED)
        LoginOutcome.ActorNotAllowedInChannel ->
            copy(submitting = false, password = "", error = LoginError.ActorNotAllowed)
        LoginOutcome.InvalidCredentials ->
            copy(submitting = false, password = "", error = LoginError.InvalidCredentials)
        is LoginOutcome.TooManyAttempts ->
            copy(submitting = false, password = "", error = LoginError.TooManyAttempts(outcome.retryAfterSeconds?.toWaitMinutes()))
        LoginOutcome.InvalidInput -> copy(submitting = false, error = LoginError.InvalidInput)
        LoginOutcome.NoConnection -> copy(submitting = false, error = LoginError.NoConnection)
        is LoginOutcome.Unavailable -> copy(submitting = false, error = LoginError.Unavailable(outcome.correlationId))
        LoginOutcome.NotConfigured -> copy(submitting = false, error = LoginError.NotConfigured)
    }

    private fun Long.toWaitMinutes(): Long = maxOf(1, (this + 59) / 60)

    companion object {
        /** Límites de `LoginRequest` en el contrato `bff-mobile` 0.3.0. */
        const val MAX_EMAIL_LENGTH = 254
        const val MAX_PASSWORD_LENGTH = 128
    }
}
