package com.lfnj.sport_app.features.training.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lfnj.sport_app.features.training.dashboard.data.DashboardGateway
import com.lfnj.sport_app.features.training.dashboard.data.DashboardRefresh
import com.lfnj.sport_app.features.training.dashboard.model.CustomRangeError
import com.lfnj.sport_app.features.training.dashboard.model.DashboardPeriod
import com.lfnj.sport_app.features.training.dashboard.model.DashboardRange
import com.lfnj.sport_app.features.training.dashboard.model.DashboardSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** Motivo por el que el periodo no pudo confirmarse; nunca describe datos ni detalles técnicos. */
enum class DashboardProblem { NO_CONNECTION, UNAVAILABLE, CAPABILITY_REQUIRED, PERMISSION_REQUIRED, INVALID_PERIOD, INVALID_RESPONSE, STORAGE }

data class TrainingDashboardUiState(
    /** Zona del dispositivo con la que se calculan el periodo y se presentan las fechas (CA3). */
    val zone: ZoneId,
    val today: LocalDate,
    val range: DashboardRange = DashboardRange.DEFAULT,
    /** Periodo consultado; `null` con [DashboardRange.CUSTOM] mientras no se elige un rango válido. */
    val period: DashboardPeriod? = null,
    /** Extremos del último rango personalizado válido (para volver a mostrarlo en el selector). */
    val customStart: LocalDate? = null,
    val customEnd: LocalDate? = null,
    val customError: CustomRangeError? = null,
    /** Consulta al BFF en curso. */
    val refreshing: Boolean = false,
    /** Tablero confirmado del periodo elegido (desde la caché), o `null`. */
    val snapshot: DashboardSnapshot? = null,
    /** [snapshot] tiene más de 24 horas (ADR-043 §5). */
    val stale: Boolean = false,
    val problem: DashboardProblem? = null,
    /** La sesión de la aplicación ya no es válida: volver al acceso. */
    val sessionExpired: Boolean = false,
) {
    /** Sin datos que mostrar y esperando la caché o el BFF. */
    val loading: Boolean get() = period != null && snapshot == null && problem == null

    /** Rango personalizado aún sin fechas: no se consulta nada. */
    val awaitingCustomRange: Boolean get() = range == DashboardRange.CUSTOM && period == null

    /** Sin red y sin tablero guardado para este periodo. */
    val neverSynced: Boolean get() = snapshot == null && problem == DashboardProblem.NO_CONNECTION
}

/**
 * Pantalla `/entrenamientos/tablero` de HU033. Observa la caché cifrada del deportista ([owner])
 * para la opción elegida y consulta el periodo al BFF; con red la respuesta reemplaza la caché y la
 * pantalla se actualiza desde ella. Sin red conserva el último tablero confirmado con su antigüedad.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrainingDashboardViewModel(
    private val owner: String,
    private val gateway: DashboardGateway,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        LocalDate.now(clock).let { today ->
            TrainingDashboardUiState(
                zone = clock.zone,
                today = today,
                period = DashboardPeriod.preset(DashboardRange.DEFAULT, today, clock.zone),
            )
        }
    )
    val uiState: StateFlow<TrainingDashboardUiState> = _uiState.asStateFlow()
    private val observedRange = MutableStateFlow(DashboardRange.DEFAULT)
    private var cached: DashboardSnapshot? = null
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            observedRange
                .flatMapLatest { range -> gateway.observe(owner, range).catch { emit(null) }.map { range to it } }
                .collect { (range, snapshot) ->
                    if (range != _uiState.value.range) return@collect
                    cached = snapshot
                    _uiState.update { it.withCache() }
                }
        }
        refresh()
    }

    /** Elige 7, 30 o 90 días. Para [DashboardRange.CUSTOM] usa [selectCustomRange]. */
    fun selectRange(range: DashboardRange) {
        if (range == DashboardRange.CUSTOM) return showCustom()
        val state = _uiState.value
        if (range == state.range) return
        switchTo(range, DashboardPeriod.preset(range, state.today, state.zone))
    }

    /** Rango personalizado con ambos extremos incluidos; uno inválido no se consulta. */
    fun selectCustomRange(start: LocalDate, end: LocalDate) {
        val error = DashboardPeriod.validateCustom(start, end)
        if (error != null) {
            _uiState.update { it.copy(customError = error) }
            return
        }
        val period = checkNotNull(DashboardPeriod.custom(start, end, _uiState.value.zone))
        _uiState.update { it.copy(customStart = start, customEnd = end, customError = null) }
        switchTo(DashboardRange.CUSTOM, period, force = true)
    }

    fun dismissCustomError() = _uiState.update { it.copy(customError = null) }

    fun retry() = refresh()

    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    /** Muestra el rango personalizado anterior, si existe; si no, espera a que se elijan fechas. */
    private fun showCustom() {
        val state = _uiState.value
        if (state.range == DashboardRange.CUSTOM) return
        val start = state.customStart
        val end = state.customEnd
        val period = if (start != null && end != null) DashboardPeriod.custom(start, end, state.zone) else null
        switchTo(DashboardRange.CUSTOM, period)
    }

    private fun switchTo(range: DashboardRange, period: DashboardPeriod?, force: Boolean = false) {
        val state = _uiState.value
        if (!force && state.range == range && state.period == period) return
        refreshJob?.cancel()
        if (range != state.range) cached = null
        _uiState.update {
            it.copy(range = range, period = period, problem = null, refreshing = false).withCache()
        }
        observedRange.value = range
        if (period != null) refresh()
    }

    private fun refresh() {
        val period = _uiState.value.period ?: return
        if (refreshJob?.isActive == true) return
        _uiState.update { it.copy(refreshing = true, problem = null) }
        refreshJob = viewModelScope.launch {
            val result = try {
                gateway.refresh(owner, period)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                DashboardRefresh.StorageFailed
            }
            _uiState.update { current ->
                if (current.period != period) return@update current
                current.copy(
                    refreshing = false,
                    problem = result.toProblem(),
                    sessionExpired = result == DashboardRefresh.SignedOut,
                ).withCache()
            }
        }
    }

    /**
     * Recalcula lo visible a partir de la caché. Un rango personalizado solo muestra el tablero
     * guardado si cubre exactamente el periodo elegido; uno predefinido muestra el último confirmado
     * con sus propias fechas y su antigüedad.
     */
    private fun TrainingDashboardUiState.withCache(): TrainingDashboardUiState {
        val snapshot = cached
        val period = period
        // Sin la capacidad o sin permiso, el BFF ya rechazó el acceso: no se muestran datos guardados.
        val forbidden = problem == DashboardProblem.CAPABILITY_REQUIRED || problem == DashboardProblem.PERMISSION_REQUIRED
        val visible = snapshot?.takeIf {
            period != null && !forbidden && it.range == range &&
                (range != DashboardRange.CUSTOM || period.sameInstants(it.from, it.to))
        }
        return copy(snapshot = visible, stale = visible?.isStale(clock.instant()) == true)
    }

    private fun DashboardRefresh.toProblem(): DashboardProblem? = when (this) {
        DashboardRefresh.Updated, DashboardRefresh.SignedOut -> null
        DashboardRefresh.NoConnection -> DashboardProblem.NO_CONNECTION
        is DashboardRefresh.Unavailable -> DashboardProblem.UNAVAILABLE
        DashboardRefresh.CapabilityRequired -> DashboardProblem.CAPABILITY_REQUIRED
        DashboardRefresh.PermissionRequired -> DashboardProblem.PERMISSION_REQUIRED
        is DashboardRefresh.InvalidPeriod -> DashboardProblem.INVALID_PERIOD
        DashboardRefresh.InvalidResponse -> DashboardProblem.INVALID_RESPONSE
        DashboardRefresh.StorageFailed -> DashboardProblem.STORAGE
    }
}
