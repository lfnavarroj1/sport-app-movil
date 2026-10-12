package com.lfnj.sport_app.features.identity.start

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lfnj.sport_app.core.session.RestoreOutcome
import com.lfnj.sport_app.navigation.Destinations
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Destino resuelto al abrir la aplicación. */
data class StartNavigation(val route: String, val sessionExpired: Boolean = false)

data class SessionStartUiState(
    val restoring: Boolean = true,
    /** Sin conexión o BFF no disponible: la sesión guardada se conserva y se puede reintentar. */
    val failure: RestoreOutcome? = null,
    val navigateTo: StartNavigation? = null,
)

/** Recupera la sesión guardada de forma segura antes de mostrar el acceso o el inicio del actor. */
class SessionStartViewModel(
    private val restore: suspend () -> RestoreOutcome,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SessionStartUiState())
    val uiState: StateFlow<SessionStartUiState> = _uiState.asStateFlow()
    private var job: Job? = null

    init {
        retry()
    }

    fun retry() {
        if (job?.isActive == true) return
        _uiState.update { it.copy(restoring = true, failure = null) }
        job = viewModelScope.launch {
            val outcome = restore()
            _uiState.update {
                when (outcome) {
                    is RestoreOutcome.Restored ->
                        it.copy(restoring = false, navigateTo = StartNavigation(Destinations.ATHLETE_HOME))
                    RestoreOutcome.NoSession, RestoreOutcome.NotConfigured ->
                        it.copy(restoring = false, navigateTo = StartNavigation(Destinations.LOGIN))
                    RestoreOutcome.Expired ->
                        it.copy(restoring = false, navigateTo = StartNavigation(Destinations.LOGIN, sessionExpired = true))
                    RestoreOutcome.UnsupportedActor ->
                        it.copy(restoring = false, navigateTo = StartNavigation(Destinations.UNAUTHORIZED))
                    RestoreOutcome.NoConnection, is RestoreOutcome.Unavailable ->
                        it.copy(restoring = false, failure = outcome)
                }
            }
        }
    }

    /** El usuario prefiere ingresar de nuevo en lugar de esperar la conexión. */
    fun continueToLogin() = _uiState.update { it.copy(navigateTo = StartNavigation(Destinations.LOGIN)) }

    fun onNavigated() = _uiState.update { it.copy(navigateTo = null) }
}
