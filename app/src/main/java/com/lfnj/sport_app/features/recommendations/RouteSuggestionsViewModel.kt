package com.lfnj.sport_app.features.recommendations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lfnj.sport_app.features.recommendations.data.RouteSuggestionsGateway
import com.lfnj.sport_app.features.recommendations.data.RouteSuggestionsRefresh
import com.lfnj.sport_app.features.recommendations.model.RouteSuggestion
import com.lfnj.sport_app.features.recommendations.model.RouteSuggestionsSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.ZoneId

/** Motivo por el que la lista no pudo confirmarse; nunca describe datos ni detalles técnicos. */
enum class RouteSuggestionsProblem { NO_CONNECTION, UNAVAILABLE, CAPABILITY_REQUIRED, PERMISSION_REQUIRED, INVALID_REQUEST, INVALID_RESPONSE, STORAGE }

data class RouteSuggestionsUiState(
    /** Zona del dispositivo con la que se presentan las fechas. */
    val zone: ZoneId,
    /** Consulta al BFF en curso. */
    val refreshing: Boolean = false,
    /** Última lista confirmada (desde la caché), o `null`. */
    val snapshot: RouteSuggestionsSnapshot? = null,
    /** [snapshot] tiene más de 24 horas (ADR-041 §5). */
    val stale: Boolean = false,
    val problem: RouteSuggestionsProblem? = null,
    /** Ruta cuyo detalle está abierto, si sigue en la lista confirmada. */
    val selected: RouteSuggestion? = null,
    /** La sesión de la aplicación ya no es válida: volver al acceso. */
    val sessionExpired: Boolean = false,
) {
    /** Sin datos que mostrar y esperando la caché o el BFF. */
    val loading: Boolean get() = snapshot == null && problem == null

    /** Sin red y sin ninguna lista confirmada en el dispositivo. */
    val neverSynced: Boolean get() = snapshot == null && problem == RouteSuggestionsProblem.NO_CONNECTION

    /** Lista confirmada sin rutas: estado vacío, no error. */
    val empty: Boolean get() = snapshot?.items?.isEmpty() == true
}

/**
 * Pantalla «Rutas sugeridas» de HU026. Observa la caché cifrada del deportista ([owner]) y consulta
 * la lista al BFF; con red, la respuesta reemplaza la caché y la pantalla se actualiza desde ella.
 * Sin red conserva la última lista confirmada con su antigüedad. Solo lectura: no publica rutas ni
 * inscribe al deportista.
 */
class RouteSuggestionsViewModel(
    private val owner: String,
    private val gateway: RouteSuggestionsGateway,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(RouteSuggestionsUiState(zone = clock.zone))
    val uiState: StateFlow<RouteSuggestionsUiState> = _uiState.asStateFlow()
    private var cached: RouteSuggestionsSnapshot? = null
    private var selectedId: String? = null
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            gateway.observe(owner)
                .catch { emit(null) }
                .collect { snapshot ->
                    cached = snapshot
                    _uiState.update { it.withCache() }
                }
        }
        refresh()
    }

    fun retry() = refresh()

    fun openDetail(routeId: String) {
        selectedId = routeId
        _uiState.update { it.withCache() }
    }

    fun closeDetail() {
        selectedId = null
        _uiState.update { it.withCache() }
    }

    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    private fun refresh() {
        if (refreshJob?.isActive == true) return
        _uiState.update { it.copy(refreshing = true, problem = null) }
        refreshJob = viewModelScope.launch {
            val result = try {
                gateway.refresh(owner)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                RouteSuggestionsRefresh.StorageFailed
            }
            _uiState.update {
                it.copy(
                    refreshing = false,
                    problem = result.toProblem(),
                    sessionExpired = result == RouteSuggestionsRefresh.SignedOut,
                ).withCache()
            }
        }
    }

    /** Recalcula lo visible a partir de la caché y del detalle abierto. */
    private fun RouteSuggestionsUiState.withCache(): RouteSuggestionsUiState {
        // Sin la capacidad o sin permiso, el BFF ya rechazó el acceso: no se muestran datos guardados.
        val forbidden = problem == RouteSuggestionsProblem.CAPABILITY_REQUIRED || problem == RouteSuggestionsProblem.PERMISSION_REQUIRED
        val visible = cached?.takeIf { !forbidden }
        // Una ruta que dejó de sugerirse (p. ej. retirada) cierra su detalle (CA4).
        val detail = selectedId?.let { id -> visible?.find(id) }
        if (detail == null) selectedId = null
        return copy(
            snapshot = visible,
            stale = visible?.isStale(clock.instant()) == true,
            selected = detail,
        )
    }

    private fun RouteSuggestionsRefresh.toProblem(): RouteSuggestionsProblem? = when (this) {
        RouteSuggestionsRefresh.Updated, RouteSuggestionsRefresh.SignedOut -> null
        RouteSuggestionsRefresh.NoConnection -> RouteSuggestionsProblem.NO_CONNECTION
        is RouteSuggestionsRefresh.Unavailable -> RouteSuggestionsProblem.UNAVAILABLE
        RouteSuggestionsRefresh.CapabilityRequired -> RouteSuggestionsProblem.CAPABILITY_REQUIRED
        RouteSuggestionsRefresh.PermissionRequired -> RouteSuggestionsProblem.PERMISSION_REQUIRED
        RouteSuggestionsRefresh.InvalidRequest -> RouteSuggestionsProblem.INVALID_REQUEST
        RouteSuggestionsRefresh.InvalidResponse -> RouteSuggestionsProblem.INVALID_RESPONSE
        RouteSuggestionsRefresh.StorageFailed -> RouteSuggestionsProblem.STORAGE
    }
}
