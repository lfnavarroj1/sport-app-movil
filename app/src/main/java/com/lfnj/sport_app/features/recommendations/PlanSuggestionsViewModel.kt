package com.lfnj.sport_app.features.recommendations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lfnj.sport_app.features.recommendations.data.PlanSuggestionsGateway
import com.lfnj.sport_app.features.recommendations.data.PlanSuggestionsRefresh
import com.lfnj.sport_app.features.recommendations.model.PlanSuggestionsSnapshot
import com.lfnj.sport_app.features.recommendations.model.PlanTemplateSuggestion
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
enum class PlanSuggestionsProblem { NO_CONNECTION, UNAVAILABLE, CAPABILITY_REQUIRED, PERMISSION_REQUIRED, INVALID_REQUEST, INVALID_RESPONSE, STORAGE }

data class PlanSuggestionsUiState(
    /** Zona del dispositivo con la que se presentan las fechas. */
    val zone: ZoneId,
    /** Consulta al BFF en curso. */
    val refreshing: Boolean = false,
    /** Última lista confirmada (desde la caché), o `null`. */
    val snapshot: PlanSuggestionsSnapshot? = null,
    /** [snapshot] tiene más de 24 horas (ADR-041 §5). */
    val stale: Boolean = false,
    val problem: PlanSuggestionsProblem? = null,
    /** Plantilla cuyo detalle está abierto, si sigue en la lista confirmada. */
    val selected: PlanTemplateSuggestion? = null,
    /** La sesión de la aplicación ya no es válida: volver al acceso. */
    val sessionExpired: Boolean = false,
) {
    /** Sin datos que mostrar y esperando la caché o el BFF. */
    val loading: Boolean get() = snapshot == null && problem == null

    /** Sin red y sin ninguna lista confirmada en el dispositivo. */
    val neverSynced: Boolean get() = snapshot == null && problem == PlanSuggestionsProblem.NO_CONNECTION

    /** Lista confirmada sin plantillas: estado vacío, no error. */
    val empty: Boolean get() = snapshot?.items?.isEmpty() == true
}

/**
 * Pantalla «Entrenamientos sugeridos» de HU028. Observa la caché cifrada del deportista ([owner]) y
 * consulta la lista al BFF; con red, la respuesta reemplaza la caché y la pantalla se actualiza
 * desde ella. Sin red conserva la última lista confirmada con su antigüedad. Solo lectura: no
 * asigna ni inicia planes.
 */
class PlanSuggestionsViewModel(
    private val owner: String,
    private val gateway: PlanSuggestionsGateway,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(PlanSuggestionsUiState(zone = clock.zone))
    val uiState: StateFlow<PlanSuggestionsUiState> = _uiState.asStateFlow()
    private var cached: PlanSuggestionsSnapshot? = null
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

    fun openDetail(templateId: String) {
        selectedId = templateId
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
                PlanSuggestionsRefresh.StorageFailed
            }
            _uiState.update {
                it.copy(
                    refreshing = false,
                    problem = result.toProblem(),
                    sessionExpired = result == PlanSuggestionsRefresh.SignedOut,
                ).withCache()
            }
        }
    }

    /** Recalcula lo visible a partir de la caché y del detalle abierto. */
    private fun PlanSuggestionsUiState.withCache(): PlanSuggestionsUiState {
        // Sin la capacidad o sin permiso, el BFF ya rechazó el acceso: no se muestran datos guardados.
        val forbidden = problem == PlanSuggestionsProblem.CAPABILITY_REQUIRED || problem == PlanSuggestionsProblem.PERMISSION_REQUIRED
        val visible = cached?.takeIf { !forbidden }
        // Una plantilla que dejó de sugerirse (p. ej. retirada) cierra su detalle (CA3).
        val detail = selectedId?.let { id -> visible?.find(id) }
        if (detail == null) selectedId = null
        return copy(
            snapshot = visible,
            stale = visible?.isStale(clock.instant()) == true,
            selected = detail,
        )
    }

    private fun PlanSuggestionsRefresh.toProblem(): PlanSuggestionsProblem? = when (this) {
        PlanSuggestionsRefresh.Updated, PlanSuggestionsRefresh.SignedOut -> null
        PlanSuggestionsRefresh.NoConnection -> PlanSuggestionsProblem.NO_CONNECTION
        is PlanSuggestionsRefresh.Unavailable -> PlanSuggestionsProblem.UNAVAILABLE
        PlanSuggestionsRefresh.CapabilityRequired -> PlanSuggestionsProblem.CAPABILITY_REQUIRED
        PlanSuggestionsRefresh.PermissionRequired -> PlanSuggestionsProblem.PERMISSION_REQUIRED
        PlanSuggestionsRefresh.InvalidRequest -> PlanSuggestionsProblem.INVALID_REQUEST
        PlanSuggestionsRefresh.InvalidResponse -> PlanSuggestionsProblem.INVALID_RESPONSE
        PlanSuggestionsRefresh.StorageFailed -> PlanSuggestionsProblem.STORAGE
    }
}
