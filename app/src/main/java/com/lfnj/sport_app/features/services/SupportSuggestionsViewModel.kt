package com.lfnj.sport_app.features.services

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lfnj.sport_app.features.services.data.SupportSuggestionsGateway
import com.lfnj.sport_app.features.services.data.SupportSuggestionsRefresh
import com.lfnj.sport_app.features.services.model.SupportServiceSuggestion
import com.lfnj.sport_app.features.services.model.SupportSuggestionsSnapshot
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
enum class SupportSuggestionsProblem { NO_CONNECTION, UNAVAILABLE, CAPABILITY_REQUIRED, PERMISSION_REQUIRED, INVALID_REQUEST, INVALID_RESPONSE, STORAGE }

data class SupportSuggestionsUiState(
    /** Zona del dispositivo con la que se presentan los intervalos. */
    val zone: ZoneId,
    /** Consulta al BFF en curso. */
    val refreshing: Boolean = false,
    /** Última lista confirmada (desde la caché), o `null`. */
    val snapshot: SupportSuggestionsSnapshot? = null,
    /** [snapshot] tiene más de 24 horas (ADR-041 §5). */
    val stale: Boolean = false,
    val problem: SupportSuggestionsProblem? = null,
    /** Servicio cuyo detalle está abierto, si sigue en la lista confirmada. */
    val selected: SupportServiceSuggestion? = null,
    /** La sesión de la aplicación ya no es válida: volver al acceso. */
    val sessionExpired: Boolean = false,
) {
    /** Sin datos que mostrar y esperando la caché o el BFF. */
    val loading: Boolean get() = snapshot == null && problem == null

    /** Sin red y sin ninguna lista confirmada en el dispositivo. */
    val neverSynced: Boolean get() = snapshot == null && problem == SupportSuggestionsProblem.NO_CONNECTION

    /** Lista confirmada sin servicios: vacía explicada (CA2), no error. */
    val empty: Boolean get() = snapshot?.items?.isEmpty() == true
}

/**
 * Pantalla «Acompañamiento y logística» de HU029. Observa la caché cifrada del deportista ([owner])
 * y consulta la lista al BFF; con red, la respuesta reemplaza la caché y la pantalla se actualiza
 * desde ella. Sin red conserva la última lista confirmada con su antigüedad. Solo lectura: no
 * contrata ni reserva servicios.
 */
class SupportSuggestionsViewModel(
    private val owner: String,
    private val gateway: SupportSuggestionsGateway,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(SupportSuggestionsUiState(zone = clock.zone))
    val uiState: StateFlow<SupportSuggestionsUiState> = _uiState.asStateFlow()
    private var cached: SupportSuggestionsSnapshot? = null
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

    fun openDetail(serviceId: String) {
        selectedId = serviceId
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
                SupportSuggestionsRefresh.StorageFailed
            }
            _uiState.update {
                it.copy(
                    refreshing = false,
                    problem = result.toProblem(),
                    sessionExpired = result == SupportSuggestionsRefresh.SignedOut,
                ).withCache()
            }
        }
    }

    /** Recalcula lo visible a partir de la caché y del detalle abierto. */
    private fun SupportSuggestionsUiState.withCache(): SupportSuggestionsUiState {
        // Sin la capacidad o sin permiso, el BFF ya rechazó el acceso: no se muestran datos guardados.
        val forbidden = problem == SupportSuggestionsProblem.CAPABILITY_REQUIRED || problem == SupportSuggestionsProblem.PERMISSION_REQUIRED
        val visible = cached?.takeIf { !forbidden }
        // Un servicio que dejó de sugerirse (despublicado o sin intervalos) cierra su detalle (CA4).
        val detail = selectedId?.let { id -> visible?.find(id) }
        if (detail == null) selectedId = null
        return copy(
            snapshot = visible,
            stale = visible?.isStale(clock.instant()) == true,
            selected = detail,
        )
    }

    private fun SupportSuggestionsRefresh.toProblem(): SupportSuggestionsProblem? = when (this) {
        SupportSuggestionsRefresh.Updated, SupportSuggestionsRefresh.SignedOut -> null
        SupportSuggestionsRefresh.NoConnection -> SupportSuggestionsProblem.NO_CONNECTION
        is SupportSuggestionsRefresh.Unavailable -> SupportSuggestionsProblem.UNAVAILABLE
        SupportSuggestionsRefresh.CapabilityRequired -> SupportSuggestionsProblem.CAPABILITY_REQUIRED
        SupportSuggestionsRefresh.PermissionRequired -> SupportSuggestionsProblem.PERMISSION_REQUIRED
        SupportSuggestionsRefresh.InvalidRequest -> SupportSuggestionsProblem.INVALID_REQUEST
        SupportSuggestionsRefresh.InvalidResponse -> SupportSuggestionsProblem.INVALID_RESPONSE
        SupportSuggestionsRefresh.StorageFailed -> SupportSuggestionsProblem.STORAGE
    }
}
