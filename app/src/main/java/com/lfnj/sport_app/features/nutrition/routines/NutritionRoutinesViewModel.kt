package com.lfnj.sport_app.features.nutrition.routines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lfnj.sport_app.features.nutrition.intake.model.FoodCatalog
import com.lfnj.sport_app.features.nutrition.routines.data.NutritionRoutinesGateway
import com.lfnj.sport_app.features.nutrition.routines.data.NutritionRoutinesRefresh
import com.lfnj.sport_app.features.nutrition.routines.model.NutritionRoutineSuggestion
import com.lfnj.sport_app.features.nutrition.routines.model.NutritionRoutinesSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.ZoneId

/** Motivo por el que la lista no pudo confirmarse; nunca describe datos ni detalles técnicos. */
enum class NutritionRoutinesProblem { NO_CONNECTION, UNAVAILABLE, CAPABILITY_REQUIRED, PERMISSION_REQUIRED, INVALID_REQUEST, INVALID_RESPONSE, STORAGE }

data class NutritionRoutinesUiState(
    /** Zona del dispositivo con la que se presentan las fechas. */
    val zone: ZoneId,
    /** Consulta al BFF en curso. */
    val refreshing: Boolean = false,
    /** Última lista confirmada (desde la caché), o `null`. */
    val snapshot: NutritionRoutinesSnapshot? = null,
    /** [snapshot] tiene más de 24 horas (ADR-041 §5). */
    val stale: Boolean = false,
    val problem: NutritionRoutinesProblem? = null,
    /** Rutina cuyo detalle está abierto, si sigue en la lista confirmada. */
    val selected: NutritionRoutineSuggestion? = null,
    /**
     * `label_key` por `food_code` del catálogo de HU043 ya guardado en el dispositivo; vacío si no
     * existe, y entonces se muestra el `food_code` (ADR-037: los textos se resuelven en el cliente).
     */
    val foodLabelKeys: Map<String, String> = emptyMap(),
    /** La sesión de la aplicación ya no es válida: volver al acceso. */
    val sessionExpired: Boolean = false,
) {
    /** Sin datos que mostrar y esperando la caché o el BFF. */
    val loading: Boolean get() = snapshot == null && problem == null

    /** Sin red y sin ninguna lista confirmada en el dispositivo. */
    val neverSynced: Boolean get() = snapshot == null && problem == NutritionRoutinesProblem.NO_CONNECTION

    /** Lista confirmada sin rutinas: estado vacío, no error. */
    val empty: Boolean get() = snapshot?.items?.isEmpty() == true
}

/**
 * Pantalla «Rutinas de alimentación» de HU030. Observa la caché cifrada del deportista ([owner]) y
 * consulta la lista al BFF; con red, la respuesta reemplaza la caché y la pantalla se actualiza
 * desde ella. Sin red conserva la última lista confirmada con su antigüedad. Solo lectura: no
 * asigna rutinas ni ofrece compra o entrega. [foodCatalog] es la lectura local del catálogo de
 * HU043 para nombrar los alimentos; no provoca ninguna consulta al BFF.
 */
class NutritionRoutinesViewModel(
    private val owner: String,
    private val gateway: NutritionRoutinesGateway,
    foodCatalog: Flow<FoodCatalog?> = flowOf(null),
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(NutritionRoutinesUiState(zone = clock.zone))
    val uiState: StateFlow<NutritionRoutinesUiState> = _uiState.asStateFlow()
    private var cached: NutritionRoutinesSnapshot? = null
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
        viewModelScope.launch {
            foodCatalog
                .catch { emit(null) }
                .collect { catalog ->
                    val labels = catalog?.items?.associate { it.foodCode to it.labelKey }.orEmpty()
                    _uiState.update { it.copy(foodLabelKeys = labels) }
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
                NutritionRoutinesRefresh.StorageFailed
            }
            _uiState.update {
                it.copy(
                    refreshing = false,
                    problem = result.toProblem(),
                    sessionExpired = result == NutritionRoutinesRefresh.SignedOut,
                ).withCache()
            }
        }
    }

    /** Recalcula lo visible a partir de la caché y del detalle abierto. */
    private fun NutritionRoutinesUiState.withCache(): NutritionRoutinesUiState {
        // Sin la capacidad o sin permiso, el BFF ya rechazó el acceso: no se muestran datos guardados.
        val forbidden = problem == NutritionRoutinesProblem.CAPABILITY_REQUIRED || problem == NutritionRoutinesProblem.PERMISSION_REQUIRED
        val visible = cached?.takeIf { !forbidden }
        // Una rutina que dejó de sugerirse (retirada o ya incompatible, CA2) cierra su detalle.
        val detail = selectedId?.let { id -> visible?.find(id) }
        if (detail == null) selectedId = null
        return copy(
            snapshot = visible,
            stale = visible?.isStale(clock.instant()) == true,
            selected = detail,
        )
    }

    private fun NutritionRoutinesRefresh.toProblem(): NutritionRoutinesProblem? = when (this) {
        NutritionRoutinesRefresh.Updated, NutritionRoutinesRefresh.SignedOut -> null
        NutritionRoutinesRefresh.NoConnection -> NutritionRoutinesProblem.NO_CONNECTION
        is NutritionRoutinesRefresh.Unavailable -> NutritionRoutinesProblem.UNAVAILABLE
        NutritionRoutinesRefresh.CapabilityRequired -> NutritionRoutinesProblem.CAPABILITY_REQUIRED
        NutritionRoutinesRefresh.PermissionRequired -> NutritionRoutinesProblem.PERMISSION_REQUIRED
        NutritionRoutinesRefresh.InvalidRequest -> NutritionRoutinesProblem.INVALID_REQUEST
        NutritionRoutinesRefresh.InvalidResponse -> NutritionRoutinesProblem.INVALID_RESPONSE
        NutritionRoutinesRefresh.StorageFailed -> NutritionRoutinesProblem.STORAGE
    }
}
