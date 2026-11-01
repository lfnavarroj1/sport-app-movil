package com.lfnj.sport_app.features.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lfnj.sport_app.features.health.data.HealthReportsGateway
import com.lfnj.sport_app.features.health.data.HealthSubmitResult
import com.lfnj.sport_app.features.health.model.BodyArea
import com.lfnj.sport_app.features.health.model.HealthReportDetails
import com.lfnj.sport_app.features.health.model.HealthReportType
import com.lfnj.sport_app.features.health.model.HealthReportValidation
import com.lfnj.sport_app.features.health.model.HealthSeverity
import com.lfnj.sport_app.features.health.model.LocalHealthReport
import com.lfnj.sport_app.features.health.model.OccurredAtError
import com.lfnj.sport_app.features.health.model.RecentTrainingSession
import com.lfnj.sport_app.features.health.model.RecoveryDaysError
import com.lfnj.sport_app.features.training.session.model.LocalTrainingSession
import com.lfnj.sport_app.features.training.session.model.SyncState
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
import java.time.temporal.ChronoUnit

/** Máximo de sesiones recientes que ofrece el selector opcional. */
const val RECENT_SESSIONS_LIMIT = 10

/**
 * Sesiones propias de HU032 que se pueden asociar: solo las confirmadas por el backend (`SYNCED`),
 * para no enviar un `session_id` que el servidor todavía no conoce (`404 training_session_not_found`).
 */
fun List<LocalTrainingSession>.recentConfirmedSessions(limit: Int = RECENT_SESSIONS_LIMIT): List<RecentTrainingSession> =
    filter { it.syncState == SyncState.SYNCED && it.details != null }
        .map { RecentTrainingSession(it.sessionId, checkNotNull(it.details).startedAt) }
        .sortedByDescending { it.startedAt }
        .take(limit)

/** Campo obligatorio sin elegir en el formulario. */
enum class HealthField { TYPE, BODY_AREA, SEVERITY, CONSENT }

/** Resultado de la última captura; nunca contiene datos del reporte. */
enum class HealthSubmitNotice { QUEUED, STORAGE, CONSENT_UNAVAILABLE }

data class HealthReportUiState(
    val loading: Boolean = true,
    val type: HealthReportType? = null,
    val bodyArea: BodyArea? = null,
    val severity: HealthSeverity? = null,
    /** Momento de la molestia (`occurred_at`), por defecto el de abrir el formulario. */
    val occurredAt: Instant = Instant.EPOCH,
    val occurredAtError: OccurredAtError? = null,
    /** Sesiones propias confirmadas de HU032 que se pueden asociar (opcional). */
    val sessions: List<RecentTrainingSession> = emptyList(),
    val sessionId: String? = null,
    val recoveryDays: String = "",
    val recoveryDaysError: RecoveryDaysError? = null,
    val consentAccepted: Boolean = false,
    /** `false` si la variante no tiene versión de consentimiento: el formulario no envía nada. */
    val consentAvailable: Boolean = true,
    val missing: Set<HealthField> = emptySet(),
    val busy: Boolean = false,
    /** Conectividad del dispositivo: sin red el reporte se guarda y se envía al recuperarla. */
    val online: Boolean = true,
    val reports: List<LocalHealthReport> = emptyList(),
    val submitNotice: HealthSubmitNotice? = null,
    /** La fuente local no pudo leerse. */
    val storageProblem: Boolean = false,
) {
    /** Con severidad alta se muestra el texto de consulta profesional (CA6); nunca un diagnóstico. */
    val showsProfessionalAdvice: Boolean get() = severity?.suggestsProfessional == true
}

/**
 * Pantalla «Mi salud» de HU042. Observa la fuente local cifrada del deportista ([owner]); un reporte
 * se encola cifrado como `PENDING_SYNC` y solo pasa a confirmado con el `201` del BFF (CA5).
 */
class HealthReportViewModel(
    private val owner: String,
    private val gateway: HealthReportsGateway,
    sessions: Flow<List<RecentTrainingSession>>,
    online: Flow<Boolean>,
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        HealthReportUiState(occurredAt = now(), consentAvailable = gateway.consentVersion != null)
    )
    val uiState: StateFlow<HealthReportUiState> = _uiState.asStateFlow()
    private var actionJob: Job? = null

    init {
        viewModelScope.launch {
            gateway.observe(owner)
                .catch { _uiState.update { it.copy(loading = false, storageProblem = true) } }
                .collect { reports -> _uiState.update { it.copy(loading = false, reports = reports, storageProblem = false) } }
        }
        viewModelScope.launch {
            sessions.catch { emit(emptyList()) }.collect { list ->
                _uiState.update { state ->
                    state.copy(sessions = list, sessionId = state.sessionId?.takeIf { id -> list.any { it.sessionId == id } })
                }
            }
        }
        viewModelScope.launch {
            online.catch { emit(true) }.collect { value -> _uiState.update { it.copy(online = value) } }
        }
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.SECONDS)

    fun selectType(type: HealthReportType) = _uiState.update { it.copy(type = type, missing = it.missing - HealthField.TYPE, submitNotice = null) }

    fun selectBodyArea(area: BodyArea) =
        _uiState.update { it.copy(bodyArea = area, missing = it.missing - HealthField.BODY_AREA, submitNotice = null) }

    fun selectSeverity(severity: HealthSeverity) =
        _uiState.update { it.copy(severity = severity, missing = it.missing - HealthField.SEVERITY, submitNotice = null) }

    fun setOccurredAt(occurredAt: Instant) =
        _uiState.update { it.copy(occurredAt = occurredAt.truncatedTo(ChronoUnit.SECONDS), occurredAtError = null, submitNotice = null) }

    /** `null` quita la sesión asociada. */
    fun selectSession(sessionId: String?) = _uiState.update { state ->
        state.copy(sessionId = sessionId?.takeIf { id -> state.sessions.any { it.sessionId == id } }, submitNotice = null)
    }

    fun onRecoveryDaysChange(text: String) = _uiState.update {
        it.copy(recoveryDays = text.filter(Char::isDigit).take(MAX_RECOVERY_LENGTH), recoveryDaysError = null, submitNotice = null)
    }

    fun setConsent(accepted: Boolean) = _uiState.update {
        it.copy(consentAccepted = accepted, missing = if (accepted) it.missing - HealthField.CONSENT else it.missing, submitNotice = null)
    }

    /** Valida para la experiencia y, si todo es válido, guarda el reporte cifrado en la cola. */
    fun submit() {
        val state = _uiState.value
        if (state.busy || actionJob?.isActive == true) return
        if (!state.consentAvailable) {
            _uiState.update { it.copy(submitNotice = HealthSubmitNotice.CONSENT_UNAVAILABLE) }
            return
        }
        val missing = buildSet {
            if (state.type == null) add(HealthField.TYPE)
            if (state.bodyArea == null) add(HealthField.BODY_AREA)
            if (state.severity == null) add(HealthField.SEVERITY)
            if (!state.consentAccepted) add(HealthField.CONSENT)
        }
        val occurredAtError = HealthReportValidation.validateOccurredAt(state.occurredAt, clock.instant())
        val recovery = HealthReportValidation.parseRecoveryDays(state.recoveryDays)
        val recoveryError = (recovery as? HealthReportValidation.Recovery.Invalid)?.error
        if (missing.isNotEmpty() || occurredAtError != null || recoveryError != null) {
            _uiState.update { it.copy(missing = missing, occurredAtError = occurredAtError, recoveryDaysError = recoveryError, submitNotice = null) }
            return
        }
        val details = HealthReportDetails(
            type = checkNotNull(state.type),
            bodyArea = checkNotNull(state.bodyArea),
            severity = checkNotNull(state.severity),
            occurredAt = state.occurredAt,
            sessionId = state.sessionId,
            expectedRecoveryDays = (recovery as HealthReportValidation.Recovery.Valid).days,
        )
        _uiState.update { it.copy(busy = true, submitNotice = null, missing = emptySet()) }
        actionJob = viewModelScope.launch {
            val notice = try {
                when (gateway.submit(owner, details)) {
                    is HealthSubmitResult.Queued -> HealthSubmitNotice.QUEUED
                    HealthSubmitResult.ConsentUnavailable -> HealthSubmitNotice.CONSENT_UNAVAILABLE
                    HealthSubmitResult.StorageFailed -> HealthSubmitNotice.STORAGE
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                HealthSubmitNotice.STORAGE
            }
            _uiState.update {
                if (notice == HealthSubmitNotice.QUEUED) {
                    // El consentimiento se vuelve a aceptar en cada reporte; el aviso de severidad alta sigue en la tarjeta del reporte.
                    it.copy(
                        busy = false, submitNotice = notice, type = null, bodyArea = null, severity = null, sessionId = null,
                        recoveryDays = "", consentAccepted = false, occurredAt = now(),
                    )
                } else {
                    it.copy(busy = false, submitNotice = notice)
                }
            }
        }
    }

    fun dismiss(reportId: String) {
        if (actionJob?.isActive == true) return
        actionJob = viewModelScope.launch { gateway.dismiss(owner, reportId) }
    }

    private companion object {
        const val MAX_RECOVERY_LENGTH = 3
    }
}
