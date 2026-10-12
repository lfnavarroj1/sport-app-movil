package com.lfnj.sport_app.features.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lfnj.sport_app.core.session.AccessOutcome
import com.lfnj.sport_app.core.session.AccessSnapshot
import com.lfnj.sport_app.core.session.LogoutOutcome
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.identity.login.LoginNotice
import com.lfnj.sport_app.navigation.Destinations
import com.lfnj.sport_app.navigation.MenuEntry
import com.lfnj.sport_app.navigation.NavigationRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeExit(val route: String, val notice: LoginNotice? = null)

data class HomeUiState(
    val user: SessionUser?,
    val loadingAccess: Boolean = true,
    val access: AccessSnapshot? = null,
    /** `NoConnection` o `Unavailable`: se conservan solo las opciones base y se ofrece reintentar. */
    val accessFailure: AccessOutcome? = null,
    val menu: List<MenuEntry> = NavigationRegistry.athleteMenu(null),
    /** Cierre de sesión en curso (HU004): bloquea acciones repetidas. */
    val loggingOut: Boolean = false,
    /** Salida forzada: sesión vencida o cerrada → acceso; actor no válido → acceso no autorizado. */
    val navigateTo: HomeExit? = null,
)

/**
 * Inicio del deportista (`athlete_home`). Construye el menú con las capacidades vigentes de
 * `GET /v1/me/capabilities` (HU003 CA7), sale de forma segura si el BFF rechaza la sesión o el
 * actor (CA10) y coordina el cierre de sesión (HU004).
 */
class HomeViewModel(
    user: SessionUser?,
    private val loadAccess: suspend () -> AccessOutcome,
    private val logoutAvailable: Boolean = false,
    private val performLogout: suspend () -> LogoutOutcome = { LogoutOutcome.LoggedOutRemoteUnconfirmed },
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        HomeUiState(user = user, menu = NavigationRegistry.athleteMenu(null, logoutAvailable))
    )
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private var accessJob: Job? = null
    private var logoutJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (accessJob?.isActive == true || _uiState.value.loggingOut) return
        _uiState.update { it.copy(loadingAccess = true, accessFailure = null) }
        accessJob = viewModelScope.launch {
            val outcome = loadAccess()
            _uiState.update { state ->
                if (state.loggingOut || state.navigateTo != null) return@update state
                when (outcome) {
                    is AccessOutcome.Loaded -> state.copy(
                        loadingAccess = false,
                        access = outcome.access,
                        menu = NavigationRegistry.athleteMenu(outcome.access, logoutAvailable),
                    )
                    AccessOutcome.SessionExpired -> state.copy(
                        loadingAccess = false,
                        navigateTo = HomeExit(Destinations.LOGIN, LoginNotice.SESSION_EXPIRED),
                    )
                    AccessOutcome.Unauthorized -> state.copy(
                        loadingAccess = false,
                        access = null,
                        menu = emptyList(),
                        navigateTo = HomeExit(Destinations.UNAUTHORIZED),
                    )
                    AccessOutcome.NoConnection, is AccessOutcome.Unavailable ->
                        state.copy(loadingAccess = false, accessFailure = outcome)
                }
            }
        }
    }

    /**
     * Cierra la sesión (HU004). Una sola solicitud activa: los toques repetidos y la recreación
     * de la pantalla no la duplican porque el ViewModel sobrevive y conserva el trabajo en curso.
     */
    fun logout() {
        if (!logoutAvailable || logoutJob?.isActive == true || _uiState.value.loggingOut) return
        accessJob?.cancel()
        _uiState.update { it.copy(loggingOut = true) }
        logoutJob = viewModelScope.launch {
            val notice = when (performLogout()) {
                LogoutOutcome.LoggedOut -> null
                LogoutOutcome.LoggedOutRemoteUnconfirmed -> LoginNotice.LOGOUT_REMOTE_UNCONFIRMED
            }
            _uiState.update {
                it.copy(user = null, access = null, menu = emptyList(), navigateTo = HomeExit(Destinations.LOGIN, notice))
            }
        }
    }

    fun onNavigated() = _uiState.update { it.copy(navigateTo = null) }
}
