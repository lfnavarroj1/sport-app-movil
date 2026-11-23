package com.lfnj.sport_app.features.training.monitoring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lfnj.sport_app.features.events.data.ConnectivityMonitor
import com.lfnj.sport_app.features.notifications.model.SystemNotificationsStatus
import com.lfnj.sport_app.features.sensors.data.ConnectableDevices
import com.lfnj.sport_app.features.sensors.model.IngestionState
import com.lfnj.sport_app.features.sensors.model.IngestionStatus
import com.lfnj.sport_app.features.sensors.model.MonitorSnapshot
import com.lfnj.sport_app.features.training.monitoring.data.MonitoringGateway
import com.lfnj.sport_app.features.training.monitoring.data.SettingsProblem
import com.lfnj.sport_app.features.training.monitoring.data.SettingsResult
import com.lfnj.sport_app.features.training.monitoring.model.EvaluationStatus
import com.lfnj.sport_app.features.training.monitoring.model.LiveReading
import com.lfnj.sport_app.features.training.monitoring.model.LiveReadingRules
import com.lfnj.sport_app.features.training.monitoring.model.MonitoringSettings
import com.lfnj.sport_app.features.training.monitoring.model.RemoteEvaluation
import com.lfnj.sport_app.features.training.monitoring.model.RiskAlert
import com.lfnj.sport_app.features.training.monitoring.model.ThresholdRules
import com.lfnj.sport_app.features.training.session.data.FinishResult
import com.lfnj.sport_app.features.training.session.foreground.ActiveSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant

/** Detiene la práctica con `reason = RISK_STOP` mediante el fin de HU032 (CA4). */
fun interface PracticeStopper {
    suspend fun stop(owner: String, sessionId: String): FinishResult
}

/** Situación de la evaluación remota que ve el deportista. */
enum class RemoteStatus {
    /** Aún no llega ninguna evaluación en esta sesión. */
    WAITING,
    OK,
    RISK,
    /** El backend respondió `NO_DATA`: pérdida de monitoreo, no ausencia de riesgo (CA3). */
    NO_DATA,
    /** Sin red o servicio no disponible: datos locales y «sin evaluación remota» (ADR-044 §12). */
    UNAVAILABLE,
}

/** Umbral de alerta en pantalla. */
data class ThresholdState(
    val loading: Boolean = true,
    val settings: MonitoringSettings? = null,
    val problem: SettingsProblem? = null,
    val draft: String = "",
    val saving: Boolean = false,
    val saveProblem: SettingsProblem? = null,
    val saved: Boolean = false,
    val invalidDraft: Boolean = false,
)

enum class StopPhase { IDLE, CONFIRMING, STOPPING, STOPPED, FAILED }

data class MonitoringUiState(
    /** Sin `training.realtime.monitor` (menú o `403 capability_required`): se explica el plan (CA5). */
    val capabilityRequired: Boolean,
    /** La fuente es una simulación de prueba (ADR-044 §4): la pantalla lo declara siempre. */
    val simulatedSource: Boolean,
    val session: ActiveSession? = null,
    /** `null` mientras se lee; `false` sin monitor activo vinculado en este teléfono. */
    val hasDevice: Boolean? = null,
    val monitor: MonitorSnapshot? = null,
    val monitoringSince: Instant? = null,
    val now: Instant,
    val online: Boolean = true,
    val ingestion: IngestionStatus = IngestionStatus(),
    val evaluation: RemoteEvaluation? = null,
    val alerts: List<RiskAlert> = emptyList(),
    val threshold: ThresholdState = ThresholdState(),
    val stop: StopPhase = StopPhase.IDLE,
    val notifications: SystemNotificationsStatus = SystemNotificationsStatus.ENABLED,
    val sessionExpired: Boolean = false,
) {
    /** Lectura dibujada con los datos locales: nunca muestra un valor anterior como actual (CA1, CA3). */
    val live: LiveReading get() = LiveReadingRules.of(monitor, now, monitoringSince)

    val blockedByPlan: Boolean get() = capabilityRequired || ingestion.state == IngestionState.CAPABILITY_REQUIRED

    val remote: RemoteStatus
        get() = when {
            !online || ingestion.state == IngestionState.PENDING_RETRY -> RemoteStatus.UNAVAILABLE
            else -> when (evaluation?.status) {
                null -> RemoteStatus.WAITING
                EvaluationStatus.OK -> RemoteStatus.OK
                EvaluationStatus.RISK -> RemoteStatus.RISK
                EvaluationStatus.NO_DATA -> RemoteStatus.NO_DATA
            }
        }

    /** Detener solo con una sesión abierta y sin otra detención en curso o ya registrada. */
    val canStop: Boolean get() = session != null && stop in setOf(StopPhase.IDLE, StopPhase.CONFIRMING, StopPhase.FAILED)
}

/**
 * Pantalla «Monitoreo en tiempo real» de HU036. Observa la sesión `ACTIVE` de HU032 que sigue el
 * servicio en primer plano, el monitor activo de HU037 por su conexión compartida, la ingestión de
 * lotes cada 5 s y la evaluación de riesgo de cada lote. El valor se dibuja con datos locales; sin
 * red sigue mostrándolos y declara «sin evaluación remota». Detener finaliza la sesión una sola vez.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MonitoringViewModel(
    private val owner: String,
    canUse: Boolean,
    active: StateFlow<ActiveSession?>,
    connectable: ConnectableDevices,
    observeMonitor: (address: String) -> StateFlow<MonitorSnapshot>,
    simulatedSource: Boolean,
    ingestion: StateFlow<IngestionStatus>,
    risk: StateFlow<RiskState>,
    connectivity: ConnectivityMonitor,
    private val gateway: MonitoringGateway,
    private val stopper: PracticeStopper,
    private val notifier: RiskAlertNotifier,
    private val clock: Clock = Clock.systemUTC(),
    ticks: Flow<Instant> = ticker(clock, TICK_MILLIS),
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        MonitoringUiState(
            capabilityRequired = !canUse,
            simulatedSource = simulatedSource,
            now = clock.instant(),
            notifications = notifier.status(),
        )
    )
    val uiState: StateFlow<MonitoringUiState> = _uiState.asStateFlow()

    init {
        if (canUse) {
            viewModelScope.launch { active.collect { session -> _uiState.update { it.copy(session = session) } } }
            viewModelScope.launch {
                val sessionId = active.map { it?.sessionId }.distinctUntilChanged()
                combine(sessionId, connectable.observeConnectable(owner).catch { emit(null) }) { id, device -> id to device }
                    .flatMapLatest { (id, device) ->
                        // Sin ninguna muestra, la pérdida de monitoreo se cuenta desde que se observa el monitor.
                        val watched = device?.takeIf { id != null }
                        _uiState.update { it.copy(hasDevice = device != null, monitoringSince = watched?.let { clock.instant() }) }
                        // Sin sesión activa la pantalla no abre la conexión GATT: el monitoreo acompaña a la sesión.
                        watched?.let { observeMonitor(it.address) } ?: flowOf<MonitorSnapshot?>(null)
                    }
                    .collect { snapshot -> _uiState.update { it.copy(monitor = snapshot) } }
            }
            viewModelScope.launch { ingestion.collect { status -> _uiState.update { it.copy(ingestion = status) } } }
            viewModelScope.launch {
                combine(risk, active.map { it?.sessionId }.distinctUntilChanged()) { state, id -> state.forSession(id) }
                    .collect { state -> _uiState.update { it.copy(evaluation = state.evaluation, alerts = state.alerts) } }
            }
            viewModelScope.launch {
                connectivity.online().catch { emit(true) }.collect { online -> _uiState.update { it.copy(online = online) } }
            }
            viewModelScope.launch { ticks.collect { now -> _uiState.update { it.copy(now = now) } } }
            loadSettings()
        }
    }

    fun retrySettings() = loadSettings()

    fun refreshNotifications() {
        _uiState.update { it.copy(notifications = notifier.status()) }
    }

    fun onDraftChange(value: String) {
        _uiState.update { it.copy(threshold = it.threshold.copy(draft = value.filter(Char::isDigit).take(3), invalidDraft = false, saved = false, saveProblem = null)) }
    }

    fun saveThreshold() {
        val threshold = _uiState.value.threshold
        if (threshold.saving) return
        val bpm = ThresholdRules.parse(threshold.draft)
            ?: return _uiState.update { it.copy(threshold = it.threshold.copy(invalidDraft = true)) }
        _uiState.update { it.copy(threshold = it.threshold.copy(saving = true, saveProblem = null, saved = false)) }
        viewModelScope.launch {
            when (val result = gateway.update(bpm)) {
                is SettingsResult.Done -> _uiState.update {
                    it.copy(threshold = it.threshold.copy(saving = false, settings = result.settings, draft = result.settings.maxHeartRateAlertBpm.toString(), saved = true, problem = null))
                }
                is SettingsResult.Failed -> onSettingsFailure(result.problem) { problem ->
                    copy(saving = false, saveProblem = problem)
                }
            }
        }
    }

    fun askStop() {
        if (!_uiState.value.canStop) return
        _uiState.update { it.copy(stop = StopPhase.CONFIRMING) }
    }

    fun cancelStop() {
        _uiState.update { if (it.stop == StopPhase.CONFIRMING) it.copy(stop = StopPhase.IDLE) else it }
    }

    /** Una sola transición: los toques repetidos se ignoran mientras se registra o tras registrarse. */
    fun confirmStop() {
        val state = _uiState.value
        val session = state.session ?: return
        if (state.stop != StopPhase.CONFIRMING) return
        _uiState.update { it.copy(stop = StopPhase.STOPPING) }
        viewModelScope.launch {
            val result = stopper.stop(owner, session.sessionId)
            _uiState.update {
                it.copy(
                    stop = when (result) {
                        FinishResult.FINISHED, FinishResult.NOT_OPEN -> StopPhase.STOPPED
                        FinishResult.STORAGE_FAILED -> StopPhase.FAILED
                    }
                )
            }
        }
    }

    fun onSessionExpiredHandled() {
        _uiState.update { it.copy(sessionExpired = false) }
    }

    private fun loadSettings() {
        _uiState.update { it.copy(threshold = it.threshold.copy(loading = true, problem = null)) }
        viewModelScope.launch {
            when (val result = gateway.settings()) {
                is SettingsResult.Done -> _uiState.update {
                    it.copy(threshold = it.threshold.copy(loading = false, settings = result.settings, draft = result.settings.maxHeartRateAlertBpm.toString(), problem = null))
                }
                is SettingsResult.Failed -> onSettingsFailure(result.problem) { problem -> copy(loading = false, problem = problem) }
            }
        }
    }

    private fun onSettingsFailure(problem: SettingsProblem, apply: ThresholdState.(SettingsProblem) -> ThresholdState) {
        _uiState.update { state ->
            when (problem) {
                SettingsProblem.SIGNED_OUT -> state.copy(threshold = state.threshold.copy(loading = false, saving = false), sessionExpired = true)
                SettingsProblem.CAPABILITY_REQUIRED -> state.copy(threshold = state.threshold.apply(problem), capabilityRequired = true)
                else -> state.copy(threshold = state.threshold.apply(problem))
            }
        }
    }

    companion object {
        /** La antigüedad de la lectura se actualiza cada segundo. */
        const val TICK_MILLIS = 1_000L

        /** Instante actual cada [periodMillis] mientras la pantalla está abierta. */
        fun ticker(clock: Clock, periodMillis: Long): Flow<Instant> = flow {
            while (true) {
                delay(periodMillis)
                emit(clock.instant())
            }
        }
    }
}
