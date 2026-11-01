package com.lfnj.sport_app.features.training.metrics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lfnj.sport_app.features.training.metrics.data.MetricsLocalState
import com.lfnj.sport_app.features.training.metrics.data.MetricsRefresh
import com.lfnj.sport_app.features.training.metrics.data.MoreResults
import com.lfnj.sport_app.features.training.metrics.data.SubmitResult
import com.lfnj.sport_app.features.training.metrics.data.TrainingMetricsGateway
import com.lfnj.sport_app.features.training.metrics.model.InputError
import com.lfnj.sport_app.features.training.metrics.model.MeasuredAtError
import com.lfnj.sport_app.features.training.metrics.model.MetricCatalog
import com.lfnj.sport_app.features.training.metrics.model.MetricInputValidation
import com.lfnj.sport_app.features.training.metrics.model.MetricInputValue
import com.lfnj.sport_app.features.training.metrics.model.MetricMethod
import com.lfnj.sport_app.features.training.metrics.model.MetricResult
import com.lfnj.sport_app.features.training.metrics.model.PendingCalculation
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
import java.time.temporal.ChronoUnit

/** Aviso sobre la última consulta al BFF; nunca contiene datos ni detalles técnicos. */
enum class MetricsNotice { NO_CONNECTION, UNAVAILABLE, CAPABILITY_REQUIRED, PERMISSION_REQUIRED, INVALID_RESPONSE, STORAGE }

/** Resultado de la última captura. */
enum class SubmitNotice { QUEUED, STORAGE, METHOD_UNAVAILABLE }

data class TrainingMetricsUiState(
    val loading: Boolean = true,
    /** Último catálogo confirmado; `null` si nunca se confirmó (sin red no se ofrece ningún método). */
    val catalog: MetricCatalog? = null,
    /** El catálogo tiene más de 24 horas (`STALE`, CA9). */
    val catalogStale: Boolean = false,
    val selectedMethodId: String? = null,
    /** Texto de cada entrada por `code`; el teclado es numérico. */
    val inputs: Map<String, String> = emptyMap(),
    val inputErrors: Map<String, InputError> = emptyMap(),
    /** Fin de la prueba (`measured_at`), por defecto el momento de abrir el formulario. */
    val measuredAt: Instant = Instant.EPOCH,
    val measuredAtError: MeasuredAtError? = null,
    /** Diálogo con el aviso de estimación no diagnóstica antes de enviar (ADR-030 §6). */
    val confirming: Boolean = false,
    val busy: Boolean = false,
    val results: List<MetricResult> = emptyList(),
    val resultsSyncedAt: Instant? = null,
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    val pending: List<PendingCalculation> = emptyList(),
    val notice: MetricsNotice? = null,
    val submitNotice: SubmitNotice? = null,
    /** La sesión ya no es válida: volver al acceso. */
    val sessionExpired: Boolean = false,
) {
    /** Métodos que el formulario sabe capturar, solo del catálogo confirmado (CA7, CA9). */
    val methods: List<MetricMethod> get() = catalog?.methods?.filter(MetricMethod::supported).orEmpty()

    val selectedMethod: MetricMethod? get() = selectedMethodId?.let { id -> methods.firstOrNull { it.id == id } }
}

/**
 * Pantalla `/entrenamientos/metricas` de HU034. Observa la fuente local cifrada del deportista
 * ([owner]): el formulario y las tarjetas se construyen con los metadatos del catálogo confirmado,
 * un cálculo se encola cifrado como `PENDING_SYNC` y el resultado siempre lo da el backend.
 */
class TrainingMetricsViewModel(
    private val owner: String,
    private val gateway: TrainingMetricsGateway,
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(TrainingMetricsUiState(measuredAt = now()))
    val uiState: StateFlow<TrainingMetricsUiState> = _uiState.asStateFlow()
    private var extraResults: List<MetricResult> = emptyList()
    private var cachedResults: List<MetricResult> = emptyList()
    private var actionJob: Job? = null
    private var moreJob: Job? = null

    init {
        viewModelScope.launch {
            gateway.observe(owner)
                .catch { _uiState.update { it.copy(loading = false, notice = MetricsNotice.STORAGE) } }
                .collect { local -> _uiState.update { it.withLocal(local) } }
        }
        refresh()
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.SECONDS)

    private fun TrainingMetricsUiState.withLocal(local: MetricsLocalState): TrainingMetricsUiState {
        cachedResults = local.results.items
        val catalog = local.catalog
        val selected = selectedMethodId?.takeIf { id -> catalog?.methods?.any { it.id == id && it.supported } == true }
        return copy(
            loading = false,
            catalog = catalog,
            catalogStale = catalog?.isStale(clock.instant()) == true,
            selectedMethodId = selected,
            results = merged(),
            resultsSyncedAt = local.results.syncedAt,
            nextCursor = if (extraResults.isEmpty()) local.results.nextCursor else nextCursor,
            pending = local.pending,
        )
    }

    private fun merged(): List<MetricResult> =
        (cachedResults + extraResults.filter { e -> cachedResults.none { it.calculationId == e.calculationId } })

    /** Consulta catálogo e historial; sin red la pantalla sigue mostrando lo confirmado en caché. */
    fun refresh() {
        viewModelScope.launch {
            val catalog = gateway.refreshCatalog(owner)
            val results = if (catalog == MetricsRefresh.SignedOut) catalog else gateway.refreshResults(owner)
            extraResults = emptyList()
            _uiState.update { state ->
                val notice = listOf(catalog, results).firstNotNullOfOrNull { it.toNotice() }
                state.copy(
                    notice = notice,
                    sessionExpired = catalog == MetricsRefresh.SignedOut || results == MetricsRefresh.SignedOut,
                    results = merged(),
                    catalogStale = state.catalog?.isStale(clock.instant()) == true,
                )
            }
        }
    }

    fun selectMethod(methodId: String) = _uiState.update { state ->
        if (state.methods.none { it.id == methodId }) return@update state
        state.copy(selectedMethodId = methodId, inputs = emptyMap(), inputErrors = emptyMap(), submitNotice = null)
    }

    fun onInputChange(code: String, text: String) = _uiState.update { state ->
        val digits = text.filter(Char::isDigit).take(MAX_INPUT_LENGTH)
        state.copy(inputs = state.inputs + (code to digits), inputErrors = state.inputErrors - code, submitNotice = null)
    }

    fun setMeasuredAt(measuredAt: Instant) = _uiState.update {
        it.copy(measuredAt = measuredAt.truncatedTo(ChronoUnit.SECONDS), measuredAtError = null, submitNotice = null)
    }

    /** Valida para la experiencia y, si todo es válido, muestra el aviso no diagnóstico antes de enviar. */
    fun requestSubmit() {
        val state = _uiState.value
        if (state.busy || actionJob?.isActive == true) return
        val method = state.selectedMethod ?: return
        val errors = method.inputs.mapNotNull { definition ->
            (MetricInputValidation.validate(definition, state.inputs[definition.code].orEmpty()) as? MetricInputValidation.Result.Invalid)
                ?.let { definition.code to it.error }
        }.toMap()
        val timeError = MetricInputValidation.validateMeasuredAt(state.measuredAt, clock.instant())
        _uiState.update { it.copy(inputErrors = errors, measuredAtError = timeError, confirming = errors.isEmpty() && timeError == null) }
    }

    fun cancelSubmit() = _uiState.update { it.copy(confirming = false) }

    /** Encola el cálculo confirmado por el deportista; no calcula ni muestra ningún resultado local. */
    fun confirmSubmit() {
        val state = _uiState.value
        val method = state.selectedMethod
        if (!state.confirming || method == null || state.busy || actionJob?.isActive == true) return
        val values = method.inputs.mapNotNull { definition ->
            (MetricInputValidation.validate(definition, state.inputs[definition.code].orEmpty()) as? MetricInputValidation.Result.Valid)
                ?.let { MetricInputValue(definition.code, it.value, definition.unit) }
        }
        _uiState.update { it.copy(confirming = false, busy = true, submitNotice = null) }
        actionJob = viewModelScope.launch {
            val notice = try {
                when (gateway.submit(owner, method.id, values, state.measuredAt)) {
                    is SubmitResult.Queued -> SubmitNotice.QUEUED
                    SubmitResult.MethodUnavailable -> SubmitNotice.METHOD_UNAVAILABLE
                    SubmitResult.StorageFailed -> SubmitNotice.STORAGE
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                SubmitNotice.STORAGE
            }
            _uiState.update {
                if (notice == SubmitNotice.QUEUED) {
                    it.copy(busy = false, submitNotice = notice, inputs = emptyMap(), inputErrors = emptyMap(), measuredAt = now())
                } else {
                    it.copy(busy = false, submitNotice = notice)
                }
            }
        }
    }

    fun dismiss(calculationId: String) {
        if (actionJob?.isActive == true) return
        actionJob = viewModelScope.launch { gateway.dismiss(owner, calculationId) }
    }

    fun loadMore() {
        val cursor = _uiState.value.nextCursor ?: return
        if (moreJob?.isActive == true) return
        _uiState.update { it.copy(loadingMore = true) }
        moreJob = viewModelScope.launch {
            when (val more = gateway.loadMore(owner, cursor)) {
                is MoreResults.Loaded -> {
                    extraResults = extraResults + more.items
                    _uiState.update { it.copy(loadingMore = false, results = merged(), nextCursor = more.nextCursor) }
                }
                is MoreResults.Failed -> _uiState.update {
                    it.copy(loadingMore = false, notice = more.refresh.toNotice(), sessionExpired = more.refresh == MetricsRefresh.SignedOut)
                }
            }
        }
    }

    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    private fun MetricsRefresh.toNotice(): MetricsNotice? = when (this) {
        MetricsRefresh.Updated, MetricsRefresh.SignedOut -> null
        MetricsRefresh.NoConnection -> MetricsNotice.NO_CONNECTION
        is MetricsRefresh.Unavailable -> MetricsNotice.UNAVAILABLE
        MetricsRefresh.CapabilityRequired -> MetricsNotice.CAPABILITY_REQUIRED
        MetricsRefresh.PermissionRequired -> MetricsNotice.PERMISSION_REQUIRED
        MetricsRefresh.InvalidResponse -> MetricsNotice.INVALID_RESPONSE
        MetricsRefresh.StorageFailed -> MetricsNotice.STORAGE
    }

    private companion object {
        const val MAX_INPUT_LENGTH = 9
    }
}
