package com.lfnj.sport_app.features.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lfnj.sport_app.features.events.data.MyRegistrationsGateway
import com.lfnj.sport_app.features.events.data.RegistrationsRefresh
import com.lfnj.sport_app.features.events.model.EventRegistration
import com.lfnj.sport_app.features.events.model.RegistrationsSnapshot
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
enum class RegistrationsProblem { NO_CONNECTION, UNAVAILABLE, PERMISSION_REQUIRED, INVALID_REQUEST, INVALID_RESPONSE, STORAGE }

data class MyRegistrationsUiState(
    val zone: ZoneId,
    /** Consulta de la primera página en curso. */
    val refreshing: Boolean = false,
    /** Consulta de la página siguiente en curso. */
    val loadingMore: Boolean = false,
    /** Últimas inscripciones confirmadas (desde la caché), o `null`. */
    val snapshot: RegistrationsSnapshot? = null,
    /** [snapshot] en el orden del contrato: fecha del evento (CA8). */
    val items: List<EventRegistration> = emptyList(),
    /** [snapshot] tiene más de 24 horas (ADR-032 §9). */
    val stale: Boolean = false,
    val problem: RegistrationsProblem? = null,
    /** La sesión de la aplicación ya no es válida: volver al acceso. */
    val sessionExpired: Boolean = false,
) {
    /** Sin datos que mostrar y esperando la caché o el BFF. */
    val loading: Boolean get() = snapshot == null && problem == null

    /** Confirmado por el BFF y sin inscripciones: estado vacío, no error (CA8). */
    val empty: Boolean get() = snapshot != null && snapshot.items.isEmpty()

    val canLoadMore: Boolean get() = snapshot?.nextCursor != null && !loadingMore && !refreshing

    /** Sin red y sin ninguna lista confirmada en el dispositivo. */
    val neverSynced: Boolean get() = snapshot == null && problem == RegistrationsProblem.NO_CONNECTION
}

/**
 * «Mis inscripciones» de HU041. Observa la caché cifrada del deportista ([owner]) y consulta la
 * primera página al BFF; con red la respuesta reemplaza la caché y la pantalla se actualiza desde
 * ella. Sin red conserva la última lista confirmada con su `last_synced_at` (CA10).
 */
class MyRegistrationsViewModel(
    private val owner: String,
    private val gateway: MyRegistrationsGateway,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(MyRegistrationsUiState(zone = clock.zone))
    val uiState: StateFlow<MyRegistrationsUiState> = _uiState.asStateFlow()
    private var cached: RegistrationsSnapshot? = null
    private var job: Job? = null

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

    fun loadMore() {
        if (!_uiState.value.canLoadMore || job?.isActive == true) return
        _uiState.update { it.copy(loadingMore = true, problem = null) }
        job = viewModelScope.launch { finish(attempt { gateway.loadMore(owner) }) }
    }

    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    private fun refresh() {
        if (job?.isActive == true) return
        _uiState.update { it.copy(refreshing = true, problem = null) }
        job = viewModelScope.launch { finish(attempt { gateway.refresh(owner) }) }
    }

    private suspend fun attempt(block: suspend () -> RegistrationsRefresh): RegistrationsRefresh = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        RegistrationsRefresh.StorageFailed
    }

    private fun finish(result: RegistrationsRefresh) = _uiState.update { current ->
        current.copy(
            refreshing = false,
            loadingMore = false,
            problem = result.toProblem(),
            sessionExpired = result == RegistrationsRefresh.SignedOut,
        ).withCache()
    }

    /** Sin permiso, el BFF ya rechazó el acceso: no se muestran datos guardados. */
    private fun MyRegistrationsUiState.withCache(): MyRegistrationsUiState {
        val visible = cached?.takeIf { problem != RegistrationsProblem.PERMISSION_REQUIRED }
        return copy(
            snapshot = visible,
            items = visible?.ordered.orEmpty(),
            stale = visible?.isStale(clock.instant()) == true,
        )
    }

    private fun RegistrationsRefresh.toProblem(): RegistrationsProblem? = when (this) {
        RegistrationsRefresh.Updated, RegistrationsRefresh.SignedOut -> null
        RegistrationsRefresh.NoConnection -> RegistrationsProblem.NO_CONNECTION
        is RegistrationsRefresh.Unavailable -> RegistrationsProblem.UNAVAILABLE
        RegistrationsRefresh.PermissionRequired -> RegistrationsProblem.PERMISSION_REQUIRED
        is RegistrationsRefresh.InvalidRequest -> RegistrationsProblem.INVALID_REQUEST
        RegistrationsRefresh.InvalidResponse -> RegistrationsProblem.INVALID_RESPONSE
        RegistrationsRefresh.StorageFailed -> RegistrationsProblem.STORAGE
    }
}
