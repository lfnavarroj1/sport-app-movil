package com.lfnj.sport_app.features.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lfnj.sport_app.features.events.data.EventSuggestionsGateway
import com.lfnj.sport_app.features.events.data.EventSuggestionsRefresh
import com.lfnj.sport_app.features.events.model.EventSuggestionsSnapshot
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
enum class EventSuggestionsProblem { NO_CONNECTION, UNAVAILABLE, CAPABILITY_REQUIRED, PERMISSION_REQUIRED, INVALID_REQUEST, INVALID_RESPONSE, STORAGE }

data class EventSuggestionsUiState(
    /** Zona del dispositivo con la que se presentan las fechas. */
    val zone: ZoneId,
    /** Consulta al BFF en curso. */
    val refreshing: Boolean = false,
    /** Última lista confirmada (desde la caché), o `null`. */
    val snapshot: EventSuggestionsSnapshot? = null,
    /** [snapshot] tiene más de 24 horas (ADR-041 §5). */
    val stale: Boolean = false,
    val problem: EventSuggestionsProblem? = null,
    /** La sesión de la aplicación ya no es válida: volver al acceso. */
    val sessionExpired: Boolean = false,
) {
    /** Sin datos que mostrar y esperando la caché o el BFF. */
    val loading: Boolean get() = snapshot == null && problem == null

    /** Sin red y sin ninguna lista confirmada en el dispositivo. */
    val neverSynced: Boolean get() = snapshot == null && problem == EventSuggestionsProblem.NO_CONNECTION

    /** Lista confirmada sin eventos: estado vacío, no error. */
    val empty: Boolean get() = snapshot?.items?.isEmpty() == true
}

/**
 * Pantalla «Eventos sugeridos» de HU027. Observa la caché cifrada del deportista ([owner]) y
 * consulta la lista al BFF; con red, la respuesta reemplaza la caché y la pantalla se actualiza
 * desde ella. Sin red conserva la última lista confirmada con su antigüedad. Solo lectura: el
 * detalle y la inscripción son de HU041.
 */
class EventSuggestionsViewModel(
    private val owner: String,
    private val gateway: EventSuggestionsGateway,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(EventSuggestionsUiState(zone = clock.zone))
    val uiState: StateFlow<EventSuggestionsUiState> = _uiState.asStateFlow()
    private var cached: EventSuggestionsSnapshot? = null
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
                EventSuggestionsRefresh.StorageFailed
            }
            _uiState.update {
                it.copy(
                    refreshing = false,
                    problem = result.toProblem(),
                    sessionExpired = result == EventSuggestionsRefresh.SignedOut,
                ).withCache()
            }
        }
    }

    /** Recalcula lo visible a partir de la caché. */
    private fun EventSuggestionsUiState.withCache(): EventSuggestionsUiState {
        // Sin la capacidad o sin permiso, el BFF ya rechazó el acceso: no se muestran datos guardados.
        val forbidden = problem == EventSuggestionsProblem.CAPABILITY_REQUIRED || problem == EventSuggestionsProblem.PERMISSION_REQUIRED
        val visible = cached?.takeIf { !forbidden }
        return copy(snapshot = visible, stale = visible?.isStale(clock.instant()) == true)
    }

    private fun EventSuggestionsRefresh.toProblem(): EventSuggestionsProblem? = when (this) {
        EventSuggestionsRefresh.Updated, EventSuggestionsRefresh.SignedOut -> null
        EventSuggestionsRefresh.NoConnection -> EventSuggestionsProblem.NO_CONNECTION
        is EventSuggestionsRefresh.Unavailable -> EventSuggestionsProblem.UNAVAILABLE
        EventSuggestionsRefresh.CapabilityRequired -> EventSuggestionsProblem.CAPABILITY_REQUIRED
        EventSuggestionsRefresh.PermissionRequired -> EventSuggestionsProblem.PERMISSION_REQUIRED
        EventSuggestionsRefresh.InvalidRequest -> EventSuggestionsProblem.INVALID_REQUEST
        EventSuggestionsRefresh.InvalidResponse -> EventSuggestionsProblem.INVALID_RESPONSE
        EventSuggestionsRefresh.StorageFailed -> EventSuggestionsProblem.STORAGE
    }
}
