package com.lfnj.sport_app.features.sensors

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.sensors.ble.BleAvailability
import com.lfnj.sport_app.features.sensors.ble.MonitorEvent
import com.lfnj.sport_app.features.sensors.ble.MonitorLink
import com.lfnj.sport_app.features.sensors.data.ConnectableDevices
import com.lfnj.sport_app.features.sensors.data.DevicesGateway
import com.lfnj.sport_app.features.sensors.data.DevicesProblem
import com.lfnj.sport_app.features.sensors.data.DevicesResult
import com.lfnj.sport_app.features.sensors.model.DeviceRules
import com.lfnj.sport_app.features.sensors.model.DiscoveredMonitor
import com.lfnj.sport_app.features.sensors.model.IngestionStatus
import com.lfnj.sport_app.features.sensors.model.LinkedDevice
import com.lfnj.sport_app.features.sensors.model.MonitorSnapshot
import com.lfnj.sport_app.features.training.session.model.TrainingOwner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Búsqueda de monitores con el *Heart Rate Service* `0x180D`. */
sealed interface ScanState {
    data object Idle : ScanState
    data class Scanning(val found: List<DiscoveredMonitor>) : ScanState
    /** Terminó; sin resultados la pantalla muestra «sin dispositivos compatibles» (CA2). */
    data class Finished(val found: List<DiscoveredMonitor>) : ScanState
}

/** Causa por la que no se vinculó un monitor; cada una tiene un mensaje propio. */
enum class LinkProblem {
    INVALID_NAME,
    /** El dispositivo no expone el servicio `0x180D` (CA2). */
    MISSING_HEART_RATE_SERVICE,
    /** Permiso Bluetooth denegado o revocado (CA2). */
    PERMISSION_REQUIRED,
    /** No respondió a la conexión: acercarlo, encenderlo o activar su modo de transmisión. */
    NOT_REACHABLE,
    OFFLINE,
    UNKNOWN_OUTCOME,
    ALREADY_REGISTERED,
    CAPABILITY_REQUIRED,
    KEY_RENEWED,
    INVALID,
}

enum class LinkPhase { EDITING, VERIFYING, SENDING }

/** Vinculación en curso. [idempotencyKey] se conserva mientras el resultado del backend sea desconocido. */
data class LinkDraft(
    val monitor: DiscoveredMonitor,
    val name: String,
    val idempotencyKey: String,
    val phase: LinkPhase = LinkPhase.EDITING,
    val problem: LinkProblem? = null,
)

enum class RevokeProblem { OFFLINE, UNKNOWN_OUTCOME, NOT_FOUND, CAPABILITY_REQUIRED, INVALID }

data class RevokeDraft(val device: LinkedDevice, val sending: Boolean = false, val problem: RevokeProblem? = null)

sealed interface DevicesNotice {
    data class Linked(val device: LinkedDevice) : DevicesNotice
    data class Revoked(val device: LinkedDevice) : DevicesNotice
}

enum class ListProblem { OFFLINE, UNAVAILABLE, CAPABILITY_REQUIRED, INVALID }

data class DevicesUiState(
    val availability: BleAvailability,
    /** La fuente es una simulación de prueba (ADR-044 §4): la pantalla lo declara siempre. */
    val simulatedSource: Boolean,
    val devices: List<LinkedDevice>? = null,
    val refreshing: Boolean = false,
    val listProblem: ListProblem? = null,
    val scan: ScanState = ScanState.Idle,
    val linking: LinkDraft? = null,
    val revoking: RevokeDraft? = null,
    /** Monitor activo vinculado en este teléfono y su conexión actual. */
    val connected: LinkedDevice? = null,
    val monitor: MonitorSnapshot? = null,
    val ingestion: IngestionStatus = IngestionStatus(),
    val notice: DevicesNotice? = null,
    val sessionExpired: Boolean = false,
) {
    val loading: Boolean get() = devices == null && listProblem == null
    val activeDevice: LinkedDevice? get() = devices?.firstOrNull { it.active }

    /**
     * Un solo monitor conectado a la vez (exclusión del paquete: varios dispositivos simultáneos). Un
     * dispositivo activo vinculado desde otro teléfono no bloquea: se vuelve a vincular aquí.
     */
    val canSearch: Boolean get() = availability == BleAvailability.READY && connected == null && linking == null && scan !is ScanState.Scanning
}

/**
 * Pantalla «Dispositivos de medición» de HU037. Lee las vinculaciones cifradas del deportista y las
 * actualiza con el BFF; vincular y revocar solo se confirman con la respuesta del backend (sin red no
 * se envían ni se encolan). Antes de vincular comprueba por GATT que el monitor expone `0x180D`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DevicesViewModel(
    private val user: SessionUser,
    private val gateway: DevicesGateway,
    private val connectable: ConnectableDevices,
    private val link: MonitorLink,
    ingestion: StateFlow<IngestionStatus>,
    private val probeTimeoutMillis: Long = PROBE_TIMEOUT_MILLIS,
) : ViewModel() {
    private val owner = TrainingOwner.keyOf(user.userId)
    private val _uiState = MutableStateFlow(DevicesUiState(availability = link.availability(), simulatedSource = link.simulated))
    val uiState: StateFlow<DevicesUiState> = _uiState.asStateFlow()
    private var scanJob: Job? = null

    init {
        viewModelScope.launch { gateway.observe(owner).collect { list -> _uiState.update { it.copy(devices = list, connected = list.filter { d -> d.connectable }.maxByOrNull { d -> d.updatedAt }) } } }
        viewModelScope.launch {
            connectable.observeConnectable(owner)
                .flatMapLatest { device -> device?.let { link.observe(it.address) } ?: flowOf<MonitorSnapshot?>(null) }
                .collect { snapshot -> _uiState.update { it.copy(monitor = snapshot) } }
        }
        viewModelScope.launch { ingestion.collect { status -> _uiState.update { it.copy(ingestion = status) } } }
        refresh()
    }

    /** Al volver a la pantalla (permisos concedidos o Bluetooth encendido en ajustes). */
    fun refreshAvailability() {
        _uiState.update { it.copy(availability = link.availability()) }
    }

    fun refresh() {
        if (_uiState.value.refreshing) return
        _uiState.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            val result = gateway.refresh(owner)
            _uiState.update { state ->
                when (result) {
                    is DevicesResult.Done -> state.copy(refreshing = false, listProblem = null)
                    is DevicesResult.Failed -> if (result.problem == DevicesProblem.SIGNED_OUT) {
                        state.copy(refreshing = false, sessionExpired = true)
                    } else {
                        state.copy(refreshing = false, listProblem = result.problem.toListProblem())
                    }
                }
            }
        }
    }

    fun search() {
        refreshAvailability()
        if (!_uiState.value.canSearch) return
        _uiState.update { it.copy(scan = ScanState.Scanning(emptyList()), notice = null) }
        scanJob = viewModelScope.launch {
            val found = mutableListOf<DiscoveredMonitor>()
            link.scan().collect { monitor ->
                if (monitor !in found) {
                    found += monitor
                    _uiState.update { it.copy(scan = ScanState.Scanning(found.toList())) }
                }
            }
            _uiState.update { it.copy(scan = ScanState.Finished(found.toList()), availability = link.availability()) }
        }
    }

    fun stopSearch() {
        scanJob?.cancel()
        val found = (_uiState.value.scan as? ScanState.Scanning)?.found.orEmpty()
        _uiState.update { it.copy(scan = ScanState.Finished(found)) }
    }

    /** Abre la confirmación con el texto de transparencia (qué se captura) antes de vincular. */
    fun select(monitor: DiscoveredMonitor) {
        scanJob?.cancel()
        _uiState.update {
            it.copy(
                scan = ScanState.Idle,
                linking = LinkDraft(monitor, DeviceRules.suggestedName(monitor), gateway.newIdempotencyKey()),
            )
        }
    }

    fun onNameChange(name: String) {
        _uiState.update { state -> state.copy(linking = state.linking?.copy(name = name.take(DeviceRules.MAX_NAME_LENGTH), problem = null)) }
    }

    fun cancelLink() {
        _uiState.update { it.copy(linking = null) }
    }

    fun confirmLink() {
        val draft = _uiState.value.linking ?: return
        if (draft.phase != LinkPhase.EDITING) return
        val name = DeviceRules.normalizeName(draft.name) ?: return setLink(draft.copy(problem = LinkProblem.INVALID_NAME))
        setLink(draft.copy(phase = LinkPhase.VERIFYING, problem = null))
        viewModelScope.launch {
            val verified = verify(draft.monitor)
            if (verified != null) return@launch setLink(draft.copy(phase = LinkPhase.EDITING, problem = verified))
            setLink(draft.copy(phase = LinkPhase.SENDING, problem = null))
            when (val result = gateway.link(user, draft.monitor, name, draft.idempotencyKey)) {
                is DevicesResult.Done -> _uiState.update { it.copy(linking = null, notice = DevicesNotice.Linked(result.value)) }
                is DevicesResult.Failed -> when (result.problem) {
                    DevicesProblem.SIGNED_OUT -> _uiState.update { it.copy(linking = null, sessionExpired = true) }
                    DevicesProblem.KEY_REUSED -> setLink(draft.copy(idempotencyKey = gateway.newIdempotencyKey(), problem = LinkProblem.KEY_RENEWED))
                    else -> setLink(draft.copy(problem = result.problem.toLinkProblem()))
                }
            }
        }
    }

    fun askRevoke(device: LinkedDevice) {
        _uiState.update { it.copy(revoking = RevokeDraft(device), notice = null) }
    }

    fun cancelRevoke() {
        _uiState.update { it.copy(revoking = null) }
    }

    fun confirmRevoke() {
        val draft = _uiState.value.revoking ?: return
        if (draft.sending) return
        _uiState.update { it.copy(revoking = draft.copy(sending = true, problem = null)) }
        viewModelScope.launch {
            when (val result = gateway.revoke(owner, draft.device.deviceId)) {
                is DevicesResult.Done -> _uiState.update { it.copy(revoking = null, notice = DevicesNotice.Revoked(result.value)) }
                is DevicesResult.Failed -> if (result.problem == DevicesProblem.SIGNED_OUT) {
                    _uiState.update { it.copy(revoking = null, sessionExpired = true) }
                } else {
                    _uiState.update { it.copy(revoking = draft.copy(sending = false, problem = result.problem.toRevokeProblem())) }
                }
            }
        }
    }

    fun onNoticeShown() {
        _uiState.update { it.copy(notice = null) }
    }

    fun onSessionExpiredHandled() {
        _uiState.update { it.copy(sessionExpired = false) }
    }

    /** `null` si el monitor respondió con el servicio `0x180D` y las notificaciones activas. */
    private suspend fun verify(monitor: DiscoveredMonitor): LinkProblem? {
        val terminal = withTimeoutOrNull(probeTimeoutMillis) { link.probe(monitor.address).firstTerminal() } ?: return LinkProblem.NOT_REACHABLE
        return when (terminal) {
            MonitorEvent.Connected -> null
            MonitorEvent.MissingHeartRateService -> LinkProblem.MISSING_HEART_RATE_SERVICE
            MonitorEvent.PermissionRequired -> LinkProblem.PERMISSION_REQUIRED
            else -> LinkProblem.NOT_REACHABLE
        }
    }

    private suspend fun Flow<MonitorEvent>.firstTerminal(): MonitorEvent? = try {
        first { it !is MonitorEvent.Connecting && it !is MonitorEvent.Battery && it !is MonitorEvent.HeartRate }
    } catch (_: NoSuchElementException) {
        null
    }

    private fun setLink(draft: LinkDraft) = _uiState.update { it.copy(linking = draft) }

    private fun DevicesProblem.toListProblem() = when (this) {
        DevicesProblem.OFFLINE -> ListProblem.OFFLINE
        DevicesProblem.CAPABILITY_REQUIRED, DevicesProblem.PERMISSION_REQUIRED -> ListProblem.CAPABILITY_REQUIRED
        DevicesProblem.INVALID -> ListProblem.INVALID
        else -> ListProblem.UNAVAILABLE
    }

    private fun DevicesProblem.toLinkProblem() = when (this) {
        DevicesProblem.OFFLINE -> LinkProblem.OFFLINE
        DevicesProblem.ALREADY_REGISTERED -> LinkProblem.ALREADY_REGISTERED
        DevicesProblem.CAPABILITY_REQUIRED, DevicesProblem.PERMISSION_REQUIRED -> LinkProblem.CAPABILITY_REQUIRED
        DevicesProblem.INVALID, DevicesProblem.NOT_FOUND -> LinkProblem.INVALID
        else -> LinkProblem.UNKNOWN_OUTCOME
    }

    private fun DevicesProblem.toRevokeProblem() = when (this) {
        DevicesProblem.OFFLINE -> RevokeProblem.OFFLINE
        DevicesProblem.NOT_FOUND -> RevokeProblem.NOT_FOUND
        DevicesProblem.CAPABILITY_REQUIRED, DevicesProblem.PERMISSION_REQUIRED -> RevokeProblem.CAPABILITY_REQUIRED
        DevicesProblem.INVALID -> RevokeProblem.INVALID
        else -> RevokeProblem.UNKNOWN_OUTCOME
    }

    companion object {
        const val PROBE_TIMEOUT_MILLIS = 20_000L
    }
}
