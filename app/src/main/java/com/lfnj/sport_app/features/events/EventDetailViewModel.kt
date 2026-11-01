package com.lfnj.sport_app.features.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lfnj.sport_app.features.events.data.ConnectivityMonitor
import com.lfnj.sport_app.features.events.data.EventDetailGateway
import com.lfnj.sport_app.features.events.data.EventDetailResult
import com.lfnj.sport_app.features.events.data.RegisterResult
import com.lfnj.sport_app.features.events.model.EventDetail
import com.lfnj.sport_app.features.events.model.EventRegistration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.ZoneId
import java.util.UUID

/** Motivo por el que el detalle no pudo confirmarse; nunca describe datos ni detalles técnicos. */
enum class EventDetailProblem { NOT_FOUND, NO_CONNECTION, UNAVAILABLE, PERMISSION_REQUIRED, INVALID_RESPONSE }

/** Por qué «Inscribirme» está deshabilitado; la pantalla lo explica (CA10). */
enum class RegisterBlock { ALREADY_REGISTERED, STARTED, OFFLINE }

/** Resultado visible del último intento de inscripción distinto del éxito. */
enum class RegistrationFailure { ALREADY_REGISTERED, FULL, CLOSED, KEY_REUSED, NOT_FOUND, NO_CONNECTION, UNAVAILABLE, PERMISSION_REQUIRED, INVALID }

data class EventDetailUiState(
    val eventId: String,
    /** Zona del dispositivo en la que se presentan fecha y hora. */
    val zone: ZoneId,
    val loading: Boolean = true,
    val detail: EventDetail? = null,
    val problem: EventDetailProblem? = null,
    /** Conectividad observada del dispositivo. */
    val online: Boolean = true,
    /** El evento ya inició según el reloj del dispositivo (el backend decide con `409`). */
    val started: Boolean = false,
    /** Diálogo de confirmación explícita abierto. */
    val confirming: Boolean = false,
    /** Inscripción en curso: el botón queda deshabilitado para evitar el doble envío. */
    val submitting: Boolean = false,
    /** Inscripción confirmada por el `201` del backend en esta pantalla. */
    val confirmed: EventRegistration? = null,
    val failure: RegistrationFailure? = null,
    /** La sesión de la aplicación ya no es válida: volver al acceso. */
    val sessionExpired: Boolean = false,
) {
    /** Inscripción del deportista confirmada por el backend: `201` o `my_registration`. */
    val registered: Boolean get() = confirmed != null || detail?.myRegistration != null

    val block: RegisterBlock?
        get() = when {
            registered -> RegisterBlock.ALREADY_REGISTERED
            started -> RegisterBlock.STARTED
            !online -> RegisterBlock.OFFLINE
            else -> null
        }

    val canRegister: Boolean get() = detail != null && block == null && !submitting
}

/**
 * Detalle de un evento publicado e inscripción de HU041 (ADR-032). La inscripción solo se envía
 * con conectividad y tras confirmación; una `Idempotency-Key` UUID identifica cada intento lógico,
 * se conserva en los reintentos y se renueva tras el éxito o `idempotency_key_reused`. Nada se
 * muestra como inscrito sin la confirmación del backend.
 */
class EventDetailViewModel(
    private val eventId: String,
    private val gateway: EventDetailGateway,
    connectivity: ConnectivityMonitor,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val _uiState = MutableStateFlow(EventDetailUiState(eventId = eventId, zone = clock.zone))
    val uiState: StateFlow<EventDetailUiState> = _uiState.asStateFlow()

    private var idempotencyKey: String = newKey()
    private var loadJob: Job? = null
    private var submitJob: Job? = null

    init {
        viewModelScope.launch {
            connectivity.online()
                .catch { emit(true) }
                .collect { online ->
                    val recovered = online && !_uiState.value.online
                    _uiState.update { it.copy(online = online) }
                    if (recovered && _uiState.value.problem == EventDetailProblem.NO_CONNECTION) load()
                }
        }
        load()
    }

    fun retry() = load()

    /** Abre la confirmación explícita solo si la inscripción está habilitada. */
    fun requestRegister() {
        if (_uiState.value.canRegister) _uiState.update { it.copy(confirming = true, failure = null) }
    }

    fun cancelRegister() = _uiState.update { it.copy(confirming = false) }

    fun confirmRegister() {
        val state = _uiState.value
        if (!state.canRegister || submitJob?.isActive == true) {
            _uiState.update { it.copy(confirming = false) }
            return
        }
        val key = idempotencyKey
        _uiState.update { it.copy(confirming = false, submitting = true, failure = null) }
        submitJob = viewModelScope.launch {
            val result = try {
                gateway.register(eventId, key)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                RegisterResult.Unavailable(null)
            }
            if (result is RegisterResult.Registered || result == RegisterResult.KeyReused) idempotencyKey = newKey()
            _uiState.update { current ->
                when (result) {
                    is RegisterResult.Registered -> current.copy(submitting = false, confirmed = result.registration)
                    RegisterResult.SignedOut -> current.copy(submitting = false, sessionExpired = true)
                    else -> current.copy(submitting = false, failure = result.toFailure())
                }
            }
            // El backend conoce un estado más reciente: el detalle lo muestra («ya inscrito» o iniciado).
            if (result is RegisterResult.AlreadyRegistered || result == RegisterResult.Closed) load()
        }
    }

    fun dismissFailure() = _uiState.update { it.copy(failure = null) }

    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    private fun load() {
        if (loadJob?.isActive == true) return
        _uiState.update { it.copy(loading = true, problem = null) }
        loadJob = viewModelScope.launch {
            val result = try {
                gateway.detail(eventId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                EventDetailResult.Unavailable(null)
            }
            _uiState.update { current ->
                when (result) {
                    is EventDetailResult.Loaded -> current.copy(
                        loading = false,
                        detail = result.detail,
                        problem = null,
                        started = result.detail.hasStarted(clock.instant()),
                    )
                    EventDetailResult.SignedOut -> current.copy(loading = false, sessionExpired = true)
                    // Sin permiso o inexistente, no se conserva un detalle anterior.
                    EventDetailResult.NotFound -> current.copy(loading = false, detail = null, problem = EventDetailProblem.NOT_FOUND)
                    EventDetailResult.PermissionRequired ->
                        current.copy(loading = false, detail = null, problem = EventDetailProblem.PERMISSION_REQUIRED)
                    EventDetailResult.NoConnection -> current.copy(loading = false, problem = EventDetailProblem.NO_CONNECTION)
                    is EventDetailResult.Unavailable -> current.copy(loading = false, problem = EventDetailProblem.UNAVAILABLE)
                    EventDetailResult.InvalidResponse -> current.copy(loading = false, problem = EventDetailProblem.INVALID_RESPONSE)
                }
            }
        }
    }

    private fun RegisterResult.toFailure(): RegistrationFailure = when (this) {
        is RegisterResult.AlreadyRegistered -> RegistrationFailure.ALREADY_REGISTERED
        RegisterResult.Full -> RegistrationFailure.FULL
        RegisterResult.Closed -> RegistrationFailure.CLOSED
        RegisterResult.KeyReused -> RegistrationFailure.KEY_REUSED
        RegisterResult.NotFound -> RegistrationFailure.NOT_FOUND
        RegisterResult.NoConnection -> RegistrationFailure.NO_CONNECTION
        is RegisterResult.Unavailable, RegisterResult.InvalidResponse -> RegistrationFailure.UNAVAILABLE
        RegisterResult.PermissionRequired -> RegistrationFailure.PERMISSION_REQUIRED
        is RegisterResult.Invalid -> RegistrationFailure.INVALID
        is RegisterResult.Registered, RegisterResult.SignedOut -> error("sin falla visible")
    }
}
