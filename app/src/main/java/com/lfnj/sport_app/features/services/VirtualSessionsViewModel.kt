package com.lfnj.sport_app.features.services

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lfnj.sport_app.features.services.data.BookResult
import com.lfnj.sport_app.features.services.data.CancelResult
import com.lfnj.sport_app.features.services.data.VirtualSessionsGateway
import com.lfnj.sport_app.features.services.data.VirtualSessionsRefresh
import com.lfnj.sport_app.features.services.model.Appointment
import com.lfnj.sport_app.features.services.model.AppointmentsSnapshot
import com.lfnj.sport_app.features.services.model.AvailabilitySlot
import com.lfnj.sport_app.features.services.model.CatalogSnapshot
import com.lfnj.sport_app.features.services.model.CoachingService
import com.lfnj.sport_app.features.services.model.VirtualSessionsRules
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
import java.time.ZoneId

enum class VirtualSessionsTab { COACHES, MY_SESSIONS }

/** Motivo por el que una lectura no pudo confirmarse; nunca describe datos ni detalles técnicos. */
enum class VirtualSessionsProblem { NO_CONNECTION, UNAVAILABLE, PERMISSION_REQUIRED, INVALID_REQUEST, INVALID_RESPONSE, STORAGE }

/** Resultado no exitoso de una reserva, con mensaje propio en la pantalla. */
enum class BookingProblem {
    /** Sin conexión conocida: no se envía (ADR-046 §8, no se encola). */
    OFFLINE,
    /** `409 availability_full`: conflicto funcional, elegir otro horario (CA2). */
    FULL,
    /** `409 appointment_exists` (CA2). */
    ALREADY_BOOKED,
    /** `403 capability_required`: explicar el plan requerido (CA3). */
    CAPABILITY_REQUIRED,
    PERMISSION_REQUIRED,
    /** `422 service_not_bookable` (CA3). */
    SERVICE_NOT_BOOKABLE,
    /** `422 availability_not_bookable`. */
    SLOT_NOT_BOOKABLE,
    /** `409 idempotency_key_reused`: se preparó una clave nueva; confirmar de nuevo. */
    KEY_RENEWED,
    /** Sin red o servidor no disponible tras los reintentos: resultado desconocido; reintentar con la misma clave (CA5). */
    UNKNOWN_OUTCOME,
    INVALID,
}

enum class CancelProblem { OFFLINE, WINDOW_CLOSED, NOT_FOUND, PERMISSION_REQUIRED, UNKNOWN_OUTCOME, INVALID }

/** Intento de reserva en confirmación. [idempotencyKey] se conserva mientras el resultado sea desconocido. */
data class BookingDraft(
    val service: CoachingService,
    val slot: AvailabilitySlot,
    val idempotencyKey: String,
    val sending: Boolean = false,
    val problem: BookingProblem? = null,
)

data class CancelDraft(val appointment: Appointment, val sending: Boolean = false, val problem: CancelProblem? = null)

/** Confirmación del backend que la pantalla anuncia una vez. */
sealed interface VirtualSessionsNotice {
    data class Booked(val appointment: Appointment) : VirtualSessionsNotice
    data class Cancelled(val appointment: Appointment) : VirtualSessionsNotice
}

data class VirtualSessionsUiState(
    /** Zona del dispositivo con la que se presentan los intervalos. */
    val zone: ZoneId,
    val now: Instant,
    val tab: VirtualSessionsTab = VirtualSessionsTab.COACHES,
    val catalog: CatalogSnapshot? = null,
    val catalogStale: Boolean = false,
    val catalogRefreshing: Boolean = false,
    val catalogProblem: VirtualSessionsProblem? = null,
    val appointments: AppointmentsSnapshot? = null,
    val appointmentsStale: Boolean = false,
    val appointmentsRefreshing: Boolean = false,
    val appointmentsProblem: VirtualSessionsProblem? = null,
    /** Servicio cuyo detalle (intervalos) está abierto, si sigue en el catálogo confirmado. */
    val selected: CoachingService? = null,
    val booking: BookingDraft? = null,
    val cancelling: CancelDraft? = null,
    val notice: VirtualSessionsNotice? = null,
    /** La última solicitud no llegó por falta de red: solo lectura cacheada, sin reservar ni cancelar. */
    val offline: Boolean = false,
    /** La sesión de la aplicación ya no es válida: volver al acceso. */
    val sessionExpired: Boolean = false,
) {
    val catalogLoading: Boolean get() = catalog == null && catalogProblem == null
    val appointmentsLoading: Boolean get() = appointments == null && appointmentsProblem == null
    val catalogEmpty: Boolean get() = catalog?.services?.isEmpty() == true

    /** Cita activa del deportista en ese intervalo, según «Mis sesiones» confirmadas. */
    fun activeAppointmentIn(availabilityId: String): Appointment? = appointments?.activeIn(availabilityId)

    fun isBookable(slot: AvailabilitySlot): Boolean = VirtualSessionsRules.isBookable(slot.startsAt, now) && activeAppointmentIn(slot.availabilityId) == null
}

/**
 * Pantalla «Sesiones virtuales» de HU040. Observa la caché cifrada del deportista ([owner]) y la
 * actualiza con el BFF. Reservar y cancelar solo se confirman con la respuesta del backend: sin
 * red no se envían ni se encolan, y un resultado desconocido se reintenta con la misma clave.
 */
class VirtualSessionsViewModel(
    private val owner: String,
    private val gateway: VirtualSessionsGateway,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(VirtualSessionsUiState(zone = clock.zone, now = clock.instant()))
    val uiState: StateFlow<VirtualSessionsUiState> = _uiState.asStateFlow()
    private var cachedCatalog: CatalogSnapshot? = null
    private var cachedAppointments: AppointmentsSnapshot? = null
    private var selectedId: String? = null
    private var catalogJob: Job? = null
    private var appointmentsJob: Job? = null
    private var commandJob: Job? = null

    init {
        viewModelScope.launch {
            gateway.observeCatalog(owner).catch { emit(null) }.collect { snapshot ->
                cachedCatalog = snapshot
                _uiState.update { it.withCache() }
            }
        }
        viewModelScope.launch {
            gateway.observeAppointments(owner).catch { emit(null) }.collect { snapshot ->
                cachedAppointments = snapshot
                _uiState.update { it.withCache() }
            }
        }
        refreshCatalog()
        refreshAppointments()
    }

    fun retry() {
        refreshCatalog()
        refreshAppointments()
    }

    fun selectTab(tab: VirtualSessionsTab) = _uiState.update { it.copy(tab = tab).withCache() }

    fun openService(serviceId: String) {
        selectedId = serviceId
        _uiState.update { it.withCache() }
    }

    fun closeService() {
        selectedId = null
        _uiState.update { it.copy(booking = null).withCache() }
    }

    /** Abre la confirmación de un intervalo reservable con una clave nueva para este intento. */
    fun chooseSlot(availabilityId: String) {
        val state = _uiState.value.withCache()
        val service = state.selected ?: return
        val slot = service.slots.firstOrNull { it.availabilityId == availabilityId } ?: return
        if (!state.isBookable(slot) || state.booking?.sending == true) return
        _uiState.update { it.copy(booking = BookingDraft(service, slot, gateway.newIdempotencyKey()), notice = null).withCache() }
    }

    fun dismissBooking() {
        if (_uiState.value.booking?.sending == true) return
        _uiState.update { it.copy(booking = null) }
    }

    /** Envía la reserva; un reintento tras un resultado desconocido reutiliza la misma clave (CA5). */
    fun confirmBooking() {
        val draft = _uiState.value.booking ?: return
        if (draft.sending || commandJob?.isActive == true) return
        // Sin red conocida no se envía; tras un resultado desconocido sí se reintenta con la misma clave.
        if (_uiState.value.offline && draft.problem != BookingProblem.UNKNOWN_OUTCOME) {
            _uiState.update { it.copy(booking = draft.copy(problem = BookingProblem.OFFLINE)) }
            return
        }
        _uiState.update { it.copy(booking = draft.copy(sending = true, problem = null)) }
        commandJob = viewModelScope.launch {
            val result = try {
                gateway.book(owner, draft.service.serviceId, draft.slot.availabilityId, draft.idempotencyKey)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                BookResult.Unavailable(null)
            }
            onBooked(draft, result)
        }
    }

    private fun onBooked(draft: BookingDraft, result: BookResult) {
        val failed = draft.copy(sending = false)
        when (result) {
            is BookResult.Booked -> {
                selectedId = null
                _uiState.update {
                    it.copy(booking = null, notice = VirtualSessionsNotice.Booked(result.appointment), tab = VirtualSessionsTab.MY_SESSIONS, offline = false).withCache()
                }
                return
            }
            BookResult.SignedOut -> {
                _uiState.update { it.copy(booking = null, sessionExpired = true) }
                return
            }
            else -> Unit
        }
        val problem = when (result) {
            BookResult.Full -> BookingProblem.FULL
            is BookResult.AlreadyBooked -> BookingProblem.ALREADY_BOOKED
            BookResult.CapabilityRequired -> BookingProblem.CAPABILITY_REQUIRED
            BookResult.PermissionRequired -> BookingProblem.PERMISSION_REQUIRED
            BookResult.ServiceNotBookable -> BookingProblem.SERVICE_NOT_BOOKABLE
            BookResult.SlotNotBookable -> BookingProblem.SLOT_NOT_BOOKABLE
            BookResult.KeyReused -> BookingProblem.KEY_RENEWED
            BookResult.NoConnection, is BookResult.Unavailable -> BookingProblem.UNKNOWN_OUTCOME
            BookResult.Invalid, BookResult.InvalidResponse -> BookingProblem.INVALID
            is BookResult.Booked, BookResult.SignedOut -> return
        }
        // Una clave rechazada por pertenecer a otro intento se reemplaza; las demás se conservan.
        val key = if (result == BookResult.KeyReused) gateway.newIdempotencyKey() else draft.idempotencyKey
        _uiState.update {
            it.copy(booking = failed.copy(idempotencyKey = key, problem = problem), offline = reachability(result == BookResult.NoConnection, result is BookResult.Unavailable, it.offline))
        }
        // Los conflictos cambian lo disponible: se vuelve a consultar sin cerrar el mensaje.
        when (problem) {
            BookingProblem.FULL, BookingProblem.SLOT_NOT_BOOKABLE, BookingProblem.SERVICE_NOT_BOOKABLE -> refreshCatalog()
            BookingProblem.ALREADY_BOOKED -> refreshAppointments()
            else -> Unit
        }
    }

    /** Abre la confirmación de cancelación si la cita sigue dentro de la ventana de 24 h. */
    fun requestCancel(appointmentId: String) {
        val appointment = cachedAppointments?.items?.firstOrNull { it.appointmentId == appointmentId } ?: return
        if (!appointment.canCancel(clock.instant())) return
        _uiState.update { it.copy(cancelling = CancelDraft(appointment), notice = null).withCache() }
    }

    fun dismissCancel() {
        if (_uiState.value.cancelling?.sending == true) return
        _uiState.update { it.copy(cancelling = null) }
    }

    fun confirmCancel() {
        val draft = _uiState.value.cancelling ?: return
        if (draft.sending || commandJob?.isActive == true) return
        if (_uiState.value.offline && draft.problem != CancelProblem.UNKNOWN_OUTCOME) {
            _uiState.update { it.copy(cancelling = draft.copy(problem = CancelProblem.OFFLINE)) }
            return
        }
        _uiState.update { it.copy(cancelling = draft.copy(sending = true, problem = null)) }
        commandJob = viewModelScope.launch {
            val result = try {
                gateway.cancel(owner, draft.appointment.appointmentId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                CancelResult.Unavailable(null)
            }
            val problem = when (result) {
                is CancelResult.Cancelled -> {
                    _uiState.update { it.copy(cancelling = null, notice = VirtualSessionsNotice.Cancelled(result.appointment), offline = false).withCache() }
                    // El intervalo recuperó el cupo (CA4).
                    refreshCatalog()
                    return@launch
                }
                CancelResult.SignedOut -> {
                    _uiState.update { it.copy(cancelling = null, sessionExpired = true) }
                    return@launch
                }
                CancelResult.WindowClosed -> CancelProblem.WINDOW_CLOSED
                CancelResult.NotFound -> CancelProblem.NOT_FOUND
                CancelResult.PermissionRequired -> CancelProblem.PERMISSION_REQUIRED
                CancelResult.NoConnection, is CancelResult.Unavailable -> CancelProblem.UNKNOWN_OUTCOME
                CancelResult.Invalid, CancelResult.InvalidResponse -> CancelProblem.INVALID
            }
            _uiState.update { it.copy(cancelling = draft.copy(sending = false, problem = problem), offline = reachability(result == CancelResult.NoConnection, result is CancelResult.Unavailable, it.offline)) }
            if (problem == CancelProblem.NOT_FOUND || problem == CancelProblem.WINDOW_CLOSED) refreshAppointments()
        }
    }

    /** Sin red: sin conexión; servidor no disponible: sin cambios; cualquier respuesta del BFF: en línea. */
    private fun reachability(noConnection: Boolean, unavailable: Boolean, current: Boolean): Boolean = when {
        noConnection -> true
        unavailable -> current
        else -> false
    }

    fun onNoticeShown() = _uiState.update { it.copy(notice = null) }

    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    private fun refreshCatalog() {
        if (catalogJob?.isActive == true) return
        _uiState.update { it.copy(catalogRefreshing = true, catalogProblem = null) }
        catalogJob = viewModelScope.launch {
            val result = safely { gateway.refreshCatalog(owner) }
            _uiState.update {
                it.copy(catalogRefreshing = false, catalogProblem = result.toProblem()).afterRead(result).withCache()
            }
        }
    }

    private fun refreshAppointments() {
        if (appointmentsJob?.isActive == true) return
        _uiState.update { it.copy(appointmentsRefreshing = true, appointmentsProblem = null) }
        appointmentsJob = viewModelScope.launch {
            val result = safely { gateway.refreshAppointments(owner) }
            _uiState.update {
                it.copy(appointmentsRefreshing = false, appointmentsProblem = result.toProblem()).afterRead(result).withCache()
            }
        }
    }

    private suspend fun safely(block: suspend () -> VirtualSessionsRefresh): VirtualSessionsRefresh = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        VirtualSessionsRefresh.StorageFailed
    }

    /** Conectividad observada en la última lectura y fin de sesión. */
    private fun VirtualSessionsUiState.afterRead(result: VirtualSessionsRefresh): VirtualSessionsUiState = when (result) {
        VirtualSessionsRefresh.NoConnection -> copy(offline = true)
        VirtualSessionsRefresh.SignedOut -> copy(sessionExpired = true)
        is VirtualSessionsRefresh.Unavailable -> this
        else -> copy(offline = false)
    }

    /** Recalcula lo visible a partir de las cachés, el servicio abierto y el instante actual. */
    private fun VirtualSessionsUiState.withCache(): VirtualSessionsUiState {
        val now = clock.instant()
        // Sin permiso de deportista, el BFF ya rechazó el acceso: no se muestran datos guardados.
        val catalog = cachedCatalog?.takeIf { catalogProblem != VirtualSessionsProblem.PERMISSION_REQUIRED }
        val appointments = cachedAppointments?.takeIf { appointmentsProblem != VirtualSessionsProblem.PERMISSION_REQUIRED }
        // Un servicio que dejó de ofrecerse cierra su detalle.
        val detail = selectedId?.let { id -> catalog?.find(id) }
        if (detail == null) selectedId = null
        return copy(
            now = now,
            catalog = catalog,
            catalogStale = catalog?.isStale(now) == true,
            appointments = appointments,
            appointmentsStale = appointments?.isStale(now) == true,
            selected = detail,
        )
    }

    private fun VirtualSessionsRefresh.toProblem(): VirtualSessionsProblem? = when (this) {
        VirtualSessionsRefresh.Updated, VirtualSessionsRefresh.SignedOut -> null
        VirtualSessionsRefresh.NoConnection -> VirtualSessionsProblem.NO_CONNECTION
        is VirtualSessionsRefresh.Unavailable -> VirtualSessionsProblem.UNAVAILABLE
        VirtualSessionsRefresh.PermissionRequired -> VirtualSessionsProblem.PERMISSION_REQUIRED
        VirtualSessionsRefresh.InvalidRequest -> VirtualSessionsProblem.INVALID_REQUEST
        VirtualSessionsRefresh.InvalidResponse -> VirtualSessionsProblem.INVALID_RESPONSE
        VirtualSessionsRefresh.StorageFailed -> VirtualSessionsProblem.STORAGE
    }
}
