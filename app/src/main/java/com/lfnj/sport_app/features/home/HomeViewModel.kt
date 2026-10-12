package com.lfnj.sport_app.features.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lfnj.sport_app.core.session.AccessOutcome
import com.lfnj.sport_app.core.session.AccessSnapshot
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.navigation.Destinations
import com.lfnj.sport_app.navigation.MenuEntry
import com.lfnj.sport_app.navigation.NavigationRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeExit(val route: String, val sessionExpired: Boolean = false)

data class HomeUiState(
    val user: SessionUser?,
    val loadingAccess: Boolean = true,
    val access: AccessSnapshot? = null,
    /** `NoConnection` o `Unavailable`: se conservan solo las opciones base y se ofrece reintentar. */
    val accessFailure: AccessOutcome? = null,
    val menu: List<MenuEntry> = NavigationRegistry.athleteMenu(null),
    /** Salida forzada: sesión vencida → acceso; actor no válido → acceso no autorizado. */
    val navigateTo: HomeExit? = null,
)

/**
 * Inicio del deportista (`athlete_home`). Construye el menú con las capacidades vigentes de
 * `GET /v1/me/capabilities` (HU003 CA7) y sale de forma segura si el BFF rechaza la sesión o el
 * actor (CA10).
 */
class HomeViewModel(
    user: SessionUser?,
    private val loadAccess: suspend () -> AccessOutcome,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState(user = user))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private var job: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (job?.isActive == true) return
        _uiState.update { it.copy(loadingAccess = true, accessFailure = null) }
        job = viewModelScope.launch {
            val outcome = loadAccess()
            _uiState.update { state ->
                when (outcome) {
                    is AccessOutcome.Loaded -> state.copy(
                        loadingAccess = false,
                        access = outcome.access,
                        menu = NavigationRegistry.athleteMenu(outcome.access),
                    )
                    AccessOutcome.SessionExpired ->
                        state.copy(loadingAccess = false, navigateTo = HomeExit(Destinations.LOGIN, sessionExpired = true))
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

    fun onNavigated() = _uiState.update { it.copy(navigateTo = null) }
}
