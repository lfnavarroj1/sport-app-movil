package com.lfnj.sport_app.features.integrations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lfnj.sport_app.features.integrations.data.StravaFailure
import com.lfnj.sport_app.features.integrations.data.StravaGateway
import com.lfnj.sport_app.features.integrations.data.StravaOutcome
import com.lfnj.sport_app.features.integrations.data.StravaSyncAcceptance
import com.lfnj.sport_app.features.integrations.model.StravaIntegration
import com.lfnj.sport_app.features.integrations.model.StravaLinkResult
import com.lfnj.sport_app.features.integrations.model.StravaLinkStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** Motivo visible por el que una operación no se confirmó; nunca describe datos ni detalles técnicos. */
enum class StravaProblem { NO_CONNECTION, UNAVAILABLE, CAPABILITY_REQUIRED, PERMISSION_REQUIRED, INVALID_RESPONSE, STORAGE, BROWSER_UNAVAILABLE }

/** Comando del deportista en curso o que falló (para reintentarlo). */
enum class StravaAction { REFRESH, LINK, SYNC, UNLINK }

/** Aviso de una sola vez tras un comando o el retorno desde Strava. */
enum class StravaNotice {
    LINKED,
    LINK_DENIED,
    LINK_INVALID_STATE,
    LINK_ERROR,
    LINK_NOT_COMPLETED,
    SYNC_REQUESTED,
    SYNC_ALREADY_RUNNING,
    SYNC_NOT_LINKED,
    UNLINKED,
}

data class StravaUiState(
    /** Último estado confirmado por el BFF (desde la caché cifrada), o `null`. */
    val integration: StravaIntegration? = null,
    /** [integration] tiene más de 24 horas (`STALE`, ADR-042 §13). */
    val stale: Boolean = false,
    /** Consulta del estado en curso. */
    val refreshing: Boolean = false,
    /** Comando en curso: evita acciones duplicadas. */
    val busy: StravaAction? = null,
    /** El consentimiento de Strava está abierto y se espera el retorno por el enlace profundo. */
    val linking: Boolean = false,
    /** URL del consentimiento pendiente de abrir una sola vez; nunca se guarda ni se registra. */
    val authorizationUrl: String? = null,
    val problem: StravaProblem? = null,
    /** Comando que produjo [problem]; [StravaAction.REFRESH] si fue la consulta del estado. */
    val failedAction: StravaAction? = null,
    val notice: StravaNotice? = null,
    /** La sesión de la aplicación ya no es válida: volver al acceso. */
    val sessionExpired: Boolean = false,
) {
    val status: StravaLinkStatus? get() = integration?.status

    /** Sin estado que mostrar y esperando la caché o el BFF. */
    val loading: Boolean get() = integration == null && problem == null

    /** Sin red y sin estado guardado: nunca se ha confirmado el estado en este dispositivo. */
    val neverSynced: Boolean get() = integration == null && problem == StravaProblem.NO_CONNECTION

    /** Vinculando: pidiendo la URL o con el consentimiento abierto. */
    val inLinking: Boolean get() = busy == StravaAction.LINK || linking || authorizationUrl != null

    /** Sincronizando: solicitud enviada o corrida `QUEUED`/`RUNNING` confirmada. */
    val syncing: Boolean get() = busy == StravaAction.SYNC || integration?.syncInProgress == true

    private val idle: Boolean get() = busy == null && !inLinking

    val canLink: Boolean get() = idle && status in RELINKABLE
    val canSync: Boolean get() = idle && status == StravaLinkStatus.ACTIVE && integration?.syncInProgress != true
    val canUnlink: Boolean get() = idle && (status == StravaLinkStatus.ACTIVE || status == StravaLinkStatus.EXPIRED)

    private companion object {
        val RELINKABLE = setOf(StravaLinkStatus.NOT_LINKED, StravaLinkStatus.REVOKED, StravaLinkStatus.EXPIRED)
    }
}

/**
 * Pantalla `/integraciones/strava` de HU031. Observa el último estado confirmado del deportista
 * ([owner]) y lo actualiza con el BFF. Vincular, sincronizar y desvincular requieren conectividad: sin
 * red se informa y se ofrece reintentar, nunca se encolan (ADR-042 §13). El resultado del enlace
 * profundo es solo una indicación: el estado mostrado siempre lo confirma el BFF.
 */
class StravaViewModel(
    private val owner: String,
    private val gateway: StravaGateway,
    private val links: StravaLinkResults,
    private val clock: Clock = Clock.systemUTC(),
    private val newIdempotencyKey: () -> String = { UUID.randomUUID().toString() },
    private val pollInterval: Duration = Duration.ofSeconds(5),
    private val maxPolls: Int = 12,
) : ViewModel() {
    private val _uiState = MutableStateFlow(StravaUiState())
    val uiState: StateFlow<StravaUiState> = _uiState.asStateFlow()
    private var cached: StravaIntegration? = null
    private var refreshJob: Job? = null
    private var pollJob: Job? = null

    /** Clave del intento de sincronización vigente: se repite en cada reintento hasta una respuesta definitiva. */
    private var syncKey: String? = null

    init {
        viewModelScope.launch {
            gateway.observe(owner).catch { emit(null) }.collect { integration ->
                cached = integration
                _uiState.update { it.withCache() }
            }
        }
        viewModelScope.launch {
            links.pending.filterNotNull().collect { links.consume()?.let(::onLinkResult) }
        }
        refresh()
    }

    fun retry() = when (_uiState.value.failedAction) {
        StravaAction.LINK -> requestLink()
        StravaAction.SYNC -> sync()
        StravaAction.UNLINK -> unlink()
        StravaAction.REFRESH, null -> refresh()
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch { refreshNow(reportProblem = true) }
    }

    /** El deportista aceptó el consentimiento: se pide la URL oficial de Strava al BFF (CA1). */
    fun requestLink() {
        if (!_uiState.value.canLink) return
        _uiState.update { it.copy(busy = StravaAction.LINK, problem = null, failedAction = null, notice = null) }
        viewModelScope.launch {
            when (val outcome = guarded { gateway.startAuthorization() }) {
                is StravaOutcome.Done -> _uiState.update { it.copy(busy = null, authorizationUrl = outcome.value) }
                is StravaOutcome.Failed -> fail(StravaAction.LINK, outcome.failure)
            }
        }
    }

    /** La pantalla intentó abrir la URL: queda a la espera del retorno, o informa que no hay navegador. */
    fun onAuthorizationOpened(opened: Boolean) = _uiState.update {
        if (opened) {
            it.copy(authorizationUrl = null, linking = true)
        } else {
            it.copy(authorizationUrl = null, linking = false, problem = StravaProblem.BROWSER_UNAVAILABLE, failedAction = StravaAction.LINK)
        }
    }

    /**
     * La pantalla volvió a primer plano. Si se esperaba el retorno de Strava y no llegó (p. ej. se cerró
     * la pestaña), la vinculación no se completó; se vuelve a consultar el estado por si cambió.
     */
    fun onResumed() {
        val state = _uiState.value
        if (!state.linking || links.pending.value != null) return
        _uiState.update { it.copy(linking = false, notice = StravaNotice.LINK_NOT_COMPLETED) }
        refresh()
    }

    /** Solicita una sincronización manual (CA4); sin respuesta definitiva, el reintento repite la clave. */
    fun sync() {
        if (!_uiState.value.canSync) return
        val key = syncKey ?: newIdempotencyKey().also { syncKey = it }
        _uiState.update { it.copy(busy = StravaAction.SYNC, problem = null, failedAction = null, notice = null) }
        viewModelScope.launch {
            when (val outcome = guarded { gateway.requestSync(key) }) {
                is StravaOutcome.Done -> {
                    syncKey = null
                    val notice = when (outcome.value) {
                        is StravaSyncAcceptance.Accepted -> StravaNotice.SYNC_REQUESTED
                        StravaSyncAcceptance.InProgress -> StravaNotice.SYNC_ALREADY_RUNNING
                        StravaSyncAcceptance.NotLinked -> StravaNotice.SYNC_NOT_LINKED
                    }
                    _uiState.update { it.copy(busy = null, notice = notice) }
                    refreshAfterCommand()
                }
                is StravaOutcome.Failed -> {
                    // Solo sin respuesta definitiva se conserva la clave: el reintento no duplica la corrida.
                    if (outcome.failure != StravaFailure.NO_CONNECTION && outcome.failure != StravaFailure.UNAVAILABLE) syncKey = null
                    fail(StravaAction.SYNC, outcome.failure)
                }
            }
        }
    }

    /** Desvincula tras la confirmación del deportista (CA9). */
    fun unlink() {
        if (!_uiState.value.canUnlink) return
        _uiState.update { it.copy(busy = StravaAction.UNLINK, problem = null, failedAction = null, notice = null) }
        viewModelScope.launch {
            when (val outcome = guarded { gateway.unlink() }) {
                is StravaOutcome.Done -> {
                    pollJob?.cancel()
                    _uiState.update { it.copy(busy = null, notice = StravaNotice.UNLINKED) }
                    refreshAfterCommand()
                }
                is StravaOutcome.Failed -> fail(StravaAction.UNLINK, outcome.failure)
            }
        }
    }

    fun dismissNotice() = _uiState.update { it.copy(notice = null) }

    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    private fun onLinkResult(result: StravaLinkResult) {
        val notice = when (result) {
            StravaLinkResult.LINKED -> StravaNotice.LINKED
            StravaLinkResult.DENIED -> StravaNotice.LINK_DENIED
            StravaLinkResult.INVALID_STATE -> StravaNotice.LINK_INVALID_STATE
            StravaLinkResult.ERROR -> StravaNotice.LINK_ERROR
        }
        _uiState.update { it.copy(linking = false, authorizationUrl = null, notice = notice, problem = null, failedAction = null) }
        refreshAfterCommand()
    }

    /** Tras un comando, el estado se vuelve a consultar aunque haya otra consulta en curso. */
    private fun refreshAfterCommand() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch { refreshNow(reportProblem = true) }
    }

    private suspend fun refreshNow(reportProblem: Boolean): StravaOutcome<StravaIntegration> {
        _uiState.update { it.copy(refreshing = true) }
        val outcome = guarded { gateway.refresh(owner) }
        when (outcome) {
            is StravaOutcome.Done -> {
                _uiState.update {
                    val clear = it.failedAction == StravaAction.REFRESH
                    it.copy(
                        refreshing = false,
                        problem = if (clear) null else it.problem,
                        failedAction = if (clear) null else it.failedAction,
                    ).withCache()
                }
                if (outcome.value.syncInProgress) ensurePolling()
            }
            is StravaOutcome.Failed -> {
                _uiState.update { it.copy(refreshing = false) }
                if (reportProblem) fail(StravaAction.REFRESH, outcome.failure)
            }
        }
        return outcome
    }

    /** Mientras la corrida siga `QUEUED`/`RUNNING`, consulta el estado cada [pollInterval], como máximo [maxPolls] veces. */
    private fun ensurePolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            repeat(maxPolls) {
                delay(pollInterval.toMillis())
                val outcome = refreshNow(reportProblem = false)
                if (outcome !is StravaOutcome.Done || !outcome.value.syncInProgress) return@launch
            }
        }
    }

    private fun fail(action: StravaAction, failure: StravaFailure) {
        if (failure == StravaFailure.SIGNED_OUT) {
            _uiState.update { it.copy(busy = null, refreshing = false, sessionExpired = true) }
            return
        }
        val problem = when (failure) {
            StravaFailure.NO_CONNECTION -> StravaProblem.NO_CONNECTION
            StravaFailure.UNAVAILABLE -> StravaProblem.UNAVAILABLE
            StravaFailure.CAPABILITY_REQUIRED -> StravaProblem.CAPABILITY_REQUIRED
            StravaFailure.PERMISSION_REQUIRED -> StravaProblem.PERMISSION_REQUIRED
            StravaFailure.INVALID_RESPONSE -> StravaProblem.INVALID_RESPONSE
            StravaFailure.STORAGE, StravaFailure.SIGNED_OUT -> StravaProblem.STORAGE
        }
        // Una consulta fallida no libera un comando que siga en curso.
        _uiState.update { it.copy(busy = if (action == StravaAction.REFRESH) it.busy else null, problem = problem, failedAction = action).withCache() }
    }

    private suspend fun <T> guarded(block: suspend () -> StravaOutcome<T>): StravaOutcome<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        StravaOutcome.Failed(StravaFailure.STORAGE)
    }

    /** Sin la capacidad o sin permiso, el BFF ya rechazó el acceso: no se muestran datos guardados. */
    private fun StravaUiState.withCache(): StravaUiState {
        val forbidden = problem == StravaProblem.CAPABILITY_REQUIRED || problem == StravaProblem.PERMISSION_REQUIRED
        val visible = cached.takeIf { !forbidden }
        return copy(integration = visible, stale = visible?.isStale(clock.instant()) == true)
    }
}

/** Resultado pendiente del enlace profundo, provisto por [com.lfnj.sport_app.features.integrations.system.StravaLinkLaunch]. */
interface StravaLinkResults {
    val pending: StateFlow<StravaLinkResult?>
    fun consume(): StravaLinkResult?
}
