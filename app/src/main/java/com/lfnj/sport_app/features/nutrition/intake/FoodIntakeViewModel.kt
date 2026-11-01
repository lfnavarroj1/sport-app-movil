package com.lfnj.sport_app.features.nutrition.intake

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lfnj.sport_app.features.nutrition.intake.data.CatalogRefresh
import com.lfnj.sport_app.features.nutrition.intake.data.FoodIntakeDraftItem
import com.lfnj.sport_app.features.nutrition.intake.data.FoodIntakeGateway
import com.lfnj.sport_app.features.nutrition.intake.data.FoodLocalState
import com.lfnj.sport_app.features.nutrition.intake.data.FoodSubmitResult
import com.lfnj.sport_app.features.nutrition.intake.model.ConsumedAtError
import com.lfnj.sport_app.features.nutrition.intake.model.FoodCatalog
import com.lfnj.sport_app.features.nutrition.intake.model.FoodCatalogItem
import com.lfnj.sport_app.features.nutrition.intake.model.FoodIntakeValidation
import com.lfnj.sport_app.features.nutrition.intake.model.FoodSyncState
import com.lfnj.sport_app.features.nutrition.intake.model.FoodUnit
import com.lfnj.sport_app.features.nutrition.intake.model.LocalFoodIntake
import com.lfnj.sport_app.features.nutrition.intake.model.MealType
import com.lfnj.sport_app.features.nutrition.intake.model.QuantityError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Aviso sobre la última consulta del catálogo; nunca contiene datos ni detalles técnicos. */
enum class FoodCatalogNotice { NO_CONNECTION, UNAVAILABLE, PERMISSION_REQUIRED, INVALID_RESPONSE, STORAGE }

/** Resultado de la última captura; nunca contiene datos del consumo. */
enum class FoodSubmitNotice { QUEUED, STORAGE, CATALOG_UNAVAILABLE }

/** Alimento elegido en el formulario, con la cantidad como texto decimal (sin calorías). */
data class FoodDraftLine(
    val foodCode: String,
    val labelKey: String,
    val allowedUnits: List<FoodUnit>,
    val unit: FoodUnit,
    val quantity: String = "",
    val error: QuantityError? = null,
) {
    override fun toString(): String = "FoodDraftLine(<oculto>)"
}

data class FoodIntakeUiState(
    val loading: Boolean = true,
    /** Último catálogo confirmado; `null` si nunca se confirmó (sin red no se ofrece ningún alimento, CA5). */
    val catalog: FoodCatalog? = null,
    /** El catálogo tiene más de 24 horas (`STALE`, CA6). */
    val catalogStale: Boolean = false,
    /** Consulta del catálogo en curso. */
    val refreshing: Boolean = false,
    val mealType: MealType? = null,
    val mealMissing: Boolean = false,
    /** Momento del consumo (`consumed_at`), por defecto el de abrir el formulario. */
    val consumedAt: Instant = Instant.EPOCH,
    val consumedAtError: ConsumedAtError? = null,
    val query: String = "",
    /** Resultados del buscador local; solo alimentos del catálogo en caché. */
    val results: List<FoodCatalogItem> = emptyList(),
    val lines: List<FoodDraftLine> = emptyList(),
    val itemsMissing: Boolean = false,
    val busy: Boolean = false,
    /** Conectividad del dispositivo: sin red el consumo se guarda y se envía al recuperarla. */
    val online: Boolean = true,
    /** Consumos del día y cualquier consumo aún sin confirmar o rechazado. */
    val intakes: List<LocalFoodIntake> = emptyList(),
    val notice: FoodCatalogNotice? = null,
    val submitNotice: FoodSubmitNotice? = null,
    /** La fuente local no pudo leerse. */
    val storageProblem: Boolean = false,
    /** La sesión ya no es válida: volver al acceso. */
    val sessionExpired: Boolean = false,
) {
    val canAddMore: Boolean get() = lines.size < FoodIntakeValidation.MAX_ITEMS
}

/**
 * Pantalla «Mi alimentación» de HU043. Observa la fuente local cifrada del deportista ([owner]):
 * el formulario solo ofrece alimentos y unidades del catálogo en caché, un consumo se encola
 * cifrado como `PENDING_SYNC` y solo pasa a confirmado con el `201` del BFF (CA5, CA6).
 * [labelOf] traduce el `label_key` (o devuelve el `food_code`) para el buscador local.
 */
class FoodIntakeViewModel(
    private val owner: String,
    private val gateway: FoodIntakeGateway,
    online: Flow<Boolean>,
    private val labelOf: (FoodCatalogItem) -> String,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(FoodIntakeUiState(consumedAt = now()))
    val uiState: StateFlow<FoodIntakeUiState> = _uiState.asStateFlow()
    private var allIntakes: List<LocalFoodIntake> = emptyList()
    private var actionJob: Job? = null
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            gateway.observe(owner)
                .catch { _uiState.update { it.copy(loading = false, storageProblem = true) } }
                .collect { local -> _uiState.update { it.withLocal(local) } }
        }
        viewModelScope.launch {
            online.catch { emit(true) }.collect { value -> _uiState.update { it.copy(online = value) } }
        }
        refresh()
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.SECONDS)

    private fun FoodIntakeUiState.withLocal(local: FoodLocalState): FoodIntakeUiState {
        allIntakes = local.intakes
        val catalog = local.catalog
        // Un alimento que ya no está en el catálogo confirmado sale del formulario (CA6).
        val kept = lines.mapNotNull { line ->
            val food = catalog?.item(line.foodCode) ?: return@mapNotNull null
            line.copy(allowedUnits = food.allowedUnits, unit = line.unit.takeIf(food::allows) ?: food.defaultUnit)
        }
        return copy(
            loading = false,
            storageProblem = false,
            catalog = catalog,
            catalogStale = catalog?.isStale(clock.instant()) == true,
            lines = kept,
            results = catalog?.search(query, labelOf).orEmpty(),
            intakes = visibleIntakes(),
        )
    }

    /** Consumos de hoy (zona del dispositivo) y los pendientes o fallidos de cualquier día. */
    private fun visibleIntakes(): List<LocalFoodIntake> {
        val today = clock.instant().atZone(zone).toLocalDate()
        return allIntakes.filter { intake ->
            intake.state != FoodSyncState.SYNCED || intake.details?.consumedAt?.atZone(zone)?.toLocalDate() == today
        }
    }

    /** Consulta el catálogo con su `ETag`; sin red la pantalla sigue usando lo confirmado en caché. */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        _uiState.update { it.copy(refreshing = true) }
        refreshJob = viewModelScope.launch {
            val refresh = try {
                gateway.refreshCatalog(owner)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                CatalogRefresh.StorageFailed
            }
            _uiState.update { state ->
                state.copy(
                    refreshing = false,
                    notice = refresh.toNotice(),
                    sessionExpired = refresh == CatalogRefresh.SignedOut,
                    catalogStale = state.catalog?.isStale(clock.instant()) == true,
                    intakes = visibleIntakes(),
                )
            }
        }
    }

    fun selectMeal(mealType: MealType) = _uiState.update { it.copy(mealType = mealType, mealMissing = false, submitNotice = null) }

    fun setConsumedAt(consumedAt: Instant) =
        _uiState.update { it.copy(consumedAt = consumedAt.truncatedTo(ChronoUnit.SECONDS), consumedAtError = null, submitNotice = null) }

    fun onQueryChange(query: String) = _uiState.update { state ->
        val text = query.take(MAX_QUERY_LENGTH)
        state.copy(query = text, results = state.catalog?.search(text, labelOf).orEmpty())
    }

    /** Agrega un alimento del catálogo en caché con su unidad por defecto; nunca uno ausente (CA6). */
    fun addFood(foodCode: String) = _uiState.update { state ->
        val food = state.catalog?.item(foodCode) ?: return@update state
        if (!state.canAddMore) return@update state
        state.copy(
            lines = state.lines + FoodDraftLine(food.foodCode, food.labelKey, food.allowedUnits, food.defaultUnit),
            itemsMissing = false,
            submitNotice = null,
        )
    }

    fun removeLine(index: Int) = _uiState.update { state ->
        if (index !in state.lines.indices) state else state.copy(lines = state.lines.filterIndexed { i, _ -> i != index }, submitNotice = null)
    }

    fun onQuantityChange(index: Int, text: String) = updateLine(index) {
        it.copy(quantity = text.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(MAX_QUANTITY_LENGTH), error = null)
    }

    /** Solo unidades permitidas para el alimento (`allowed_units`). */
    fun selectUnit(index: Int, unit: FoodUnit) = updateLine(index) { if (unit in it.allowedUnits) it.copy(unit = unit) else it }

    private fun updateLine(index: Int, transform: (FoodDraftLine) -> FoodDraftLine) = _uiState.update { state ->
        if (index !in state.lines.indices) state
        else state.copy(lines = state.lines.mapIndexed { i, line -> if (i == index) transform(line) else line }, submitNotice = null)
    }

    /** Valida para la experiencia y, si todo es válido, guarda el consumo cifrado en la cola. */
    fun submit() {
        val state = _uiState.value
        if (state.busy || actionJob?.isActive == true || state.catalog == null) return
        val parsed = state.lines.map { FoodIntakeValidation.parseQuantity(it.quantity) }
        val lines = state.lines.mapIndexed { i, line -> line.copy(error = (parsed[i] as? FoodIntakeValidation.Quantity.Invalid)?.error) }
        val timeError = FoodIntakeValidation.validateConsumedAt(state.consumedAt, clock.instant())
        val mealMissing = state.mealType == null
        val itemsMissing = lines.isEmpty()
        if (mealMissing || itemsMissing || timeError != null || lines.any { it.error != null }) {
            _uiState.update { it.copy(lines = lines, mealMissing = mealMissing, itemsMissing = itemsMissing, consumedAtError = timeError, submitNotice = null) }
            return
        }
        val items = lines.mapIndexed { i, line ->
            FoodIntakeDraftItem(line.foodCode, (parsed[i] as FoodIntakeValidation.Quantity.Valid).normalized, line.unit)
        }
        val mealType = checkNotNull(state.mealType)
        _uiState.update { it.copy(busy = true, submitNotice = null) }
        actionJob = viewModelScope.launch {
            val notice = try {
                when (gateway.submit(owner, mealType, state.consumedAt, items)) {
                    is FoodSubmitResult.Queued -> FoodSubmitNotice.QUEUED
                    FoodSubmitResult.CatalogUnavailable -> FoodSubmitNotice.CATALOG_UNAVAILABLE
                    FoodSubmitResult.StorageFailed -> FoodSubmitNotice.STORAGE
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                FoodSubmitNotice.STORAGE
            }
            _uiState.update {
                if (notice == FoodSubmitNotice.QUEUED) {
                    it.copy(busy = false, submitNotice = notice, mealType = null, lines = emptyList(), query = "", consumedAt = now(),
                        results = it.catalog?.search("", labelOf).orEmpty())
                } else {
                    it.copy(busy = false, submitNotice = notice)
                }
            }
        }
    }

    fun dismiss(intakeId: String) {
        if (actionJob?.isActive == true) return
        actionJob = viewModelScope.launch { gateway.dismiss(owner, intakeId) }
    }

    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    private fun CatalogRefresh.toNotice(): FoodCatalogNotice? = when (this) {
        CatalogRefresh.Updated, CatalogRefresh.SignedOut -> null
        CatalogRefresh.NoConnection -> FoodCatalogNotice.NO_CONNECTION
        is CatalogRefresh.Unavailable -> FoodCatalogNotice.UNAVAILABLE
        CatalogRefresh.PermissionRequired -> FoodCatalogNotice.PERMISSION_REQUIRED
        CatalogRefresh.InvalidResponse -> FoodCatalogNotice.INVALID_RESPONSE
        CatalogRefresh.StorageFailed -> FoodCatalogNotice.STORAGE
    }

    private companion object {
        const val MAX_QUERY_LENGTH = 60
        const val MAX_QUANTITY_LENGTH = 6
    }
}
