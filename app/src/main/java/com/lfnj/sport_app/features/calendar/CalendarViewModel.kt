package com.lfnj.sport_app.features.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lfnj.sport_app.features.calendar.data.CalendarGateway
import com.lfnj.sport_app.features.calendar.data.CalendarRefresh
import com.lfnj.sport_app.features.calendar.model.CalendarDay
import com.lfnj.sport_app.features.calendar.model.CalendarDays
import com.lfnj.sport_app.features.calendar.model.CalendarPeriod
import com.lfnj.sport_app.features.calendar.model.CalendarSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

enum class CalendarMode { MONTH, LIST }

/** Motivo por el que el periodo visible no pudo confirmarse; nunca describe datos ni detalles técnicos. */
enum class CalendarProblem { NO_CONNECTION, UNAVAILABLE, CAPABILITY_REQUIRED, PERMISSION_REQUIRED, INVALID_PERIOD, INVALID_RESPONSE, STORAGE }

data class CalendarUiState(
    /** Mes visible en la zona del dispositivo. */
    val month: YearMonth,
    /** Zona del dispositivo con la que se calculan el periodo y se presentan fechas y horas (CA4). */
    val zone: ZoneId,
    val today: LocalDate,
    val mode: CalendarMode = CalendarMode.MONTH,
    val selectedDay: LocalDate? = null,
    /** Consulta al BFF en curso. */
    val refreshing: Boolean = false,
    /** Periodo confirmado del mes visible (desde la caché), o `null`. */
    val snapshot: CalendarSnapshot? = null,
    val days: List<CalendarDay> = emptyList(),
    /** [snapshot] tiene más de 24 horas (ADR-031, política offline §4). */
    val stale: Boolean = false,
    val problem: CalendarProblem? = null,
    /** Mes del último periodo confirmado cuando es distinto del visible (disponible sin red). */
    val syncedMonth: YearMonth? = null,
    /** La sesión de la aplicación ya no es válida: volver al acceso. */
    val sessionExpired: Boolean = false,
) {
    /**
     * Sin datos que mostrar y esperando la caché o el BFF; incluye el instante entre la respuesta
     * confirmada y la lectura de la caché que la reemplazó.
     */
    val loading: Boolean get() = snapshot == null && problem == null

    /** Sin red y sin ningún periodo confirmado: «aún no has sincronizado tu calendario» (CA8). */
    val neverSynced: Boolean get() = snapshot == null && syncedMonth == null && problem == CalendarProblem.NO_CONNECTION
}

/**
 * Pantalla `/calendario` de HU035. Observa la caché cifrada del deportista ([owner]) y consulta el
 * mes visible al BFF; con red, la respuesta reemplaza la caché y la pantalla se actualiza desde
 * ella. Sin red conserva el último periodo confirmado con su antigüedad.
 */
class CalendarViewModel(
    private val owner: String,
    private val gateway: CalendarGateway,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        LocalDate.now(clock).let { today -> CalendarUiState(month = YearMonth.from(today), zone = clock.zone, today = today) }
    )
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()
    private var cached: CalendarSnapshot? = null
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

    fun previousMonth() = showMonth(_uiState.value.month.minusMonths(1))

    fun nextMonth() = showMonth(_uiState.value.month.plusMonths(1))

    fun currentMonth() = showMonth(YearMonth.from(_uiState.value.today))

    /** Muestra el último periodo confirmado (útil sin red) e intenta actualizarlo. */
    fun showSyncedPeriod() {
        _uiState.value.syncedMonth?.let(::showMonth)
    }

    fun showMonth(month: YearMonth) {
        if (month == _uiState.value.month) return
        refreshJob?.cancel()
        _uiState.update { it.copy(month = month, selectedDay = null, problem = null, refreshing = false).withCache() }
        refresh()
    }

    fun retry() = refresh()

    fun setMode(mode: CalendarMode) = _uiState.update { it.copy(mode = mode) }

    fun selectDay(date: LocalDate) = _uiState.update { it.copy(selectedDay = date) }

    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    private fun refresh() {
        if (refreshJob?.isActive == true) return
        val state = _uiState.value
        val period = CalendarPeriod.forMonth(state.month, state.zone)
        _uiState.update { it.copy(refreshing = true, problem = null) }
        refreshJob = viewModelScope.launch {
            val result = try {
                gateway.refresh(owner, period)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                CalendarRefresh.StorageFailed
            }
            _uiState.update { current ->
                if (current.month != period.month) return@update current
                current.copy(
                    refreshing = false,
                    problem = result.toProblem(),
                    sessionExpired = result == CalendarRefresh.SignedOut,
                ).withCache()
            }
        }
    }

    /** Recalcula lo visible a partir de la caché: solo se muestra si corresponde al mes visible. */
    private fun CalendarUiState.withCache(): CalendarUiState {
        val snapshot = cached
        // Sin la capacidad o sin permiso, el BFF ya rechazó el acceso: no se muestran datos guardados.
        val forbidden = problem == CalendarProblem.CAPABILITY_REQUIRED || problem == CalendarProblem.PERMISSION_REQUIRED
        val visible = snapshot?.takeIf { it.month == month && !forbidden }
        val now: Instant = clock.instant()
        return copy(
            snapshot = visible,
            days = visible?.let { CalendarDays.group(it.items, month, zone) }.orEmpty(),
            stale = visible?.isStale(now) == true,
            syncedMonth = snapshot?.month?.takeIf { it != month && !forbidden },
        )
    }

    private fun CalendarRefresh.toProblem(): CalendarProblem? = when (this) {
        CalendarRefresh.Updated, CalendarRefresh.SignedOut -> null
        CalendarRefresh.NoConnection -> CalendarProblem.NO_CONNECTION
        is CalendarRefresh.Unavailable -> CalendarProblem.UNAVAILABLE
        CalendarRefresh.CapabilityRequired -> CalendarProblem.CAPABILITY_REQUIRED
        CalendarRefresh.PermissionRequired -> CalendarProblem.PERMISSION_REQUIRED
        is CalendarRefresh.InvalidPeriod -> CalendarProblem.INVALID_PERIOD
        CalendarRefresh.InvalidResponse -> CalendarProblem.INVALID_RESPONSE
        CalendarRefresh.StorageFailed -> CalendarProblem.STORAGE
    }
}
