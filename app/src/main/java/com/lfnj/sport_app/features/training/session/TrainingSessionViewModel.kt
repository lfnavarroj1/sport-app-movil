package com.lfnj.sport_app.features.training.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lfnj.sport_app.features.training.session.data.FinishResult
import com.lfnj.sport_app.features.training.session.data.RemoteCheck
import com.lfnj.sport_app.features.training.session.data.StartResult
import com.lfnj.sport_app.features.training.session.data.TrainingSessionGateway
import com.lfnj.sport_app.features.training.session.model.LocalTrainingSession
import com.lfnj.sport_app.features.training.session.model.TrainingSport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Falla de una acción local; nunca describe datos ni detalles técnicos. */
enum class TrainingActionError { STORAGE, ALREADY_OPEN }

data class TrainingSessionUiState(
    val loading: Boolean = true,
    /** Sesión abierta del deportista (cronómetro y «Finalizar»); como máximo una (ADR-029 §3). */
    val current: LocalTrainingSession? = null,
    /** Marca monotónica del inicio de [current] si ocurrió en este proceso (ADR-029 offline §8). */
    val monotonicStartMillis: Long? = null,
    /** Demás registros del dispositivo, del más reciente al más antiguo, incluidos los rechazados. */
    val records: List<LocalTrainingSession> = emptyList(),
    val selectedSport: TrainingSport? = null,
    val sportMissing: Boolean = false,
    /** `false` si las capacidades vigentes no incluyen `training.session.record`. */
    val canStart: Boolean = true,
    /** Acción local en curso: evita inicios o fines duplicados por toques repetidos. */
    val busy: Boolean = false,
    val remoteCheck: RemoteCheck? = null,
    val actionError: TrainingActionError? = null,
    /** La sesión de la aplicación ya no es válida: volver al acceso. */
    val sessionExpired: Boolean = false,
)

/**
 * Pantalla `/entrenamientos/sesion` de HU032. Observa la fuente local del deportista
 * ([owner]); iniciar y finalizar se registran primero en el dispositivo y se sincronizan después,
 * por lo que funcionan sin red y la pantalla muestra el estado real de sincronización.
 */
class TrainingSessionViewModel(
    private val owner: String,
    private val gateway: TrainingSessionGateway,
    canStart: Boolean = true,
) : ViewModel() {
    private val _uiState = MutableStateFlow(TrainingSessionUiState(canStart = canStart))
    val uiState: StateFlow<TrainingSessionUiState> = _uiState.asStateFlow()
    private var actionJob: Job? = null

    init {
        viewModelScope.launch {
            gateway.observe(owner)
                .catch { _uiState.update { it.copy(loading = false, actionError = TrainingActionError.STORAGE) } }
                .collect { sessions -> _uiState.update { it.withSessions(sessions) } }
        }
        viewModelScope.launch {
            val check = gateway.checkRemoteActive(owner)
            _uiState.update { it.copy(remoteCheck = check, sessionExpired = check == RemoteCheck.SIGNED_OUT) }
        }
    }

    private fun TrainingSessionUiState.withSessions(sessions: List<LocalTrainingSession>): TrainingSessionUiState {
        val current = sessions.firstOrNull { it.open }
        return copy(
            loading = false,
            current = current,
            monotonicStartMillis = current?.let { gateway.monotonicStartMillis(it.sessionId) },
            records = sessions.filter { it !== current },
        )
    }

    fun selectSport(sport: TrainingSport) = _uiState.update { it.copy(selectedSport = sport, sportMissing = false) }

    fun start() {
        val state = _uiState.value
        if (actionJob?.isActive == true || state.busy || !state.canStart || state.current?.open == true) return
        val sport = state.selectedSport
        if (sport == null) {
            _uiState.update { it.copy(sportMissing = true) }
            return
        }
        runAction {
            when (gateway.start(owner, sport)) {
                is StartResult.Started -> null
                StartResult.AlreadyOpen -> TrainingActionError.ALREADY_OPEN
                StartResult.StorageFailed -> TrainingActionError.STORAGE
            }
        }
    }

    fun finish() {
        val current = _uiState.value.current
        if (actionJob?.isActive == true || _uiState.value.busy || current?.open != true) return
        runAction {
            when (gateway.finish(owner, current.sessionId)) {
                FinishResult.FINISHED, FinishResult.NOT_OPEN -> null
                FinishResult.STORAGE_FAILED -> TrainingActionError.STORAGE
            }
        }
    }

    fun dismiss(sessionId: String) {
        if (actionJob?.isActive == true) return
        runAction {
            gateway.dismiss(owner, sessionId)
            null
        }
    }

    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    private fun runAction(action: suspend () -> TrainingActionError?) {
        _uiState.update { it.copy(busy = true, actionError = null) }
        actionJob = viewModelScope.launch {
            val error = try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                TrainingActionError.STORAGE
            }
            _uiState.update { it.copy(busy = false, actionError = error) }
        }
    }
}
