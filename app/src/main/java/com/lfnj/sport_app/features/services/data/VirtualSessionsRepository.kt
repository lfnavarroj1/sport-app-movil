package com.lfnj.sport_app.features.services.data

import com.lfnj.sport_app.core.network.ApiResult
import com.lfnj.sport_app.core.network.apiCall
import com.lfnj.sport_app.core.session.AuthorizedResult
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.services.data.local.VirtualSessionsAppointmentsEntity
import com.lfnj.sport_app.features.services.data.local.VirtualSessionsCacheDao
import com.lfnj.sport_app.features.services.data.local.VirtualSessionsCatalogEntity
import com.lfnj.sport_app.features.services.data.local.VirtualSessionsCodec
import com.lfnj.sport_app.features.services.model.Appointment
import com.lfnj.sport_app.features.services.model.AppointmentsSnapshot
import com.lfnj.sport_app.features.services.model.CatalogSnapshot
import com.lfnj.sport_app.features.services.model.CoachingService
import com.lfnj.sport_app.features.services.model.ServiceModality
import com.lfnj.sport_app.features.services.model.VirtualSessionsOwner
import com.lfnj.sport_app.features.services.model.VirtualSessionsRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** Resultado de consultar el catálogo o «Mis sesiones». Ninguna variante contiene servicios ni citas. */
sealed interface VirtualSessionsRefresh {
    /** El BFF confirmó la lectura y la caché local quedó reemplazada. */
    data object Updated : VirtualSessionsRefresh
    data object NoConnection : VirtualSessionsRefresh
    /** `503`/`504`, tiempo agotado o renovación de sesión no disponible: reintentar luego. */
    data class Unavailable(val correlationId: String?) : VirtualSessionsRefresh
    /** `403 permission_required`: la cuenta no es de deportista. */
    data object PermissionRequired : VirtualSessionsRefresh
    /** `422 validation_error`: no debería ocurrir con los parámetros fijos del cliente. */
    data object InvalidRequest : VirtualSessionsRefresh
    data object InvalidResponse : VirtualSessionsRefresh
    /** La sesión ya no es válida: volver al acceso. */
    data object SignedOut : VirtualSessionsRefresh
    /** La respuesta llegó, pero no pudo guardarse en el dispositivo. */
    data object StorageFailed : VirtualSessionsRefresh
}

/** Resultado de `POST /v1/me/appointments` (ADR-046 §2). */
sealed interface BookResult {
    /** `201`: cita `BOOKED` confirmada por el backend (CA1); también la respuesta original de un reintento (CA5). */
    data class Booked(val appointment: Appointment) : BookResult
    /** `409 availability_full`: el intervalo ya no tiene cupo; conflicto funcional (CA2). */
    data object Full : BookResult
    /** `409 appointment_exists`, con el identificador de la cita activa si vino en `details` (CA2). */
    data class AlreadyBooked(val appointmentId: String?) : BookResult
    /** `409 idempotency_key_reused`: la clave pertenece a otro intento; se usa una nueva. */
    data object KeyReused : BookResult
    /** `403 capability_required`: el plan no incluye `services.virtual_sessions.schedule` (CA3). */
    data object CapabilityRequired : BookResult
    data object PermissionRequired : BookResult
    /** `422 service_not_bookable` (CA3). */
    data object ServiceNotBookable : BookResult
    /** `422 availability_not_bookable`: el intervalo no es del servicio o empieza en menos de 1 h. */
    data object SlotNotBookable : BookResult
    /** `422 validation_error`: no debería ocurrir con los identificadores del catálogo. */
    data object Invalid : BookResult
    /** Sin red tras los reintentos automáticos: el resultado es desconocido. */
    data object NoConnection : BookResult
    /** `502`/`503`/`504` o tiempo agotado tras los reintentos: el resultado es desconocido. */
    data class Unavailable(val correlationId: String?) : BookResult
    data object InvalidResponse : BookResult
    data object SignedOut : BookResult
}

/** Resultado de `DELETE /v1/me/appointments/{appointment_id}` (ADR-046 §3). */
sealed interface CancelResult {
    /** `200`: la cita quedó `CANCELLED_BY_ATHLETE` (CA4); repetirla devuelve la misma. */
    data class Cancelled(val appointment: Appointment) : CancelResult
    /** `422 cancellation_window_closed`: faltan menos de 24 h (CA4). */
    data object WindowClosed : CancelResult
    /** `404 appointment_not_found`. */
    data object NotFound : CancelResult
    data object PermissionRequired : CancelResult
    data object Invalid : CancelResult
    data object NoConnection : CancelResult
    data class Unavailable(val correlationId: String?) : CancelResult
    data object InvalidResponse : CancelResult
    data object SignedOut : CancelResult
}

/** Operaciones de la pantalla «Sesiones virtuales»; las implementa [VirtualSessionsRepository]. */
interface VirtualSessionsGateway {
    /** Último catálogo confirmado del deportista, o `null` si no existe o es ilegible. */
    fun observeCatalog(owner: String): Flow<CatalogSnapshot?>
    fun observeAppointments(owner: String): Flow<AppointmentsSnapshot?>
    suspend fun refreshCatalog(owner: String): VirtualSessionsRefresh
    suspend fun refreshAppointments(owner: String): VirtualSessionsRefresh

    /**
     * Envía la reserva con [idempotencyKey] y la conserva en los reintentos automáticos del mismo
     * intento (red, `502`, `503`, `504`, hasta [MAX_AUTOMATIC_RETRIES] veces). Un `4xx` nunca se
     * repite automáticamente. Una reserva confirmada se agrega a «Mis sesiones».
     */
    suspend fun book(owner: String, serviceId: String, availabilityId: String, idempotencyKey: String): BookResult

    /** Cancela la cita propia; una cancelación confirmada reemplaza la cita en «Mis sesiones». */
    suspend fun cancel(owner: String, appointmentId: String): CancelResult

    /** Clave UUID nueva para cada intento de reserva. */
    fun newIdempotencyKey(): String = UUID.randomUUID().toString()

    companion object {
        const val MAX_AUTOMATIC_RETRIES = 3
    }
}

/** Limpieza de la caché local (cierre de sesión o cambio de deportista). */
interface VirtualSessionsCacheStore {
    suspend fun clear(owner: String)
    suspend fun clearAllExcept(owner: String)
}

/**
 * HU040 (ADR-046 §8): catálogo de entrenadores virtuales y «Mis sesiones» offline first desde la
 * caché cifrada del deportista, reemplazada con cada respuesta confirmada por `bff-mobile`.
 * Reservar y cancelar son comandos solo en línea: no hay cola ni WorkManager, y nada se confirma
 * en el dispositivo sin la respuesta del backend.
 */
class VirtualSessionsRepository(
    private val dao: VirtualSessionsCacheDao,
    private val codec: VirtualSessionsCodec,
    private val api: VirtualSessionsApi?,
    private val sessionManager: SessionManager,
    private val json: Json,
    private val clock: Clock = Clock.systemDefaultZone(),
    /** Espera antes del reintento automático número `attempt` (1, 2, 3). */
    private val retryDelay: suspend (attempt: Int) -> Unit = { attempt -> delay(BASE_RETRY_DELAY_MS shl (attempt - 1)) },
) : VirtualSessionsGateway, VirtualSessionsCacheStore {

    override fun observeCatalog(owner: String): Flow<CatalogSnapshot?> =
        dao.observeCatalog(owner).map { entity -> entity?.let(codec::decodeCatalog) }.distinctUntilChanged()

    override fun observeAppointments(owner: String): Flow<AppointmentsSnapshot?> =
        dao.observeAppointments(owner).map { entity -> entity?.let(codec::decodeAppointments) }.distinctUntilChanged()

    override suspend fun refreshCatalog(owner: String): VirtualSessionsRefresh {
        val api = api ?: return VirtualSessionsRefresh.Unavailable(null)
        val now = clock.instant()
        val from = wire(now)
        val to = wire(now.plus(VirtualSessionsRules.CATALOG_RANGE))
        val services = mutableListOf<CoachingService>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val page = when (val result = read { auth -> api.availability(auth, from, to, CATEGORIES, MODALITIES, VirtualSessionsApi.CATALOG_LIMIT, cursor) }) {
                is Read.Ok -> result.value
                is Read.Failed -> return result.refresh
            }
            services += try {
                page.toServices()
            } catch (_: InvalidVirtualSessionsResponse) {
                return VirtualSessionsRefresh.InvalidResponse
            }
            cursor = page.nextCursor ?: return storeCatalog(owner, CatalogSnapshot(services, truncated = false, syncedAt = now))
        }
        return storeCatalog(owner, CatalogSnapshot(services, truncated = true, syncedAt = now))
    }

    override suspend fun refreshAppointments(owner: String): VirtualSessionsRefresh {
        val api = api ?: return VirtualSessionsRefresh.Unavailable(null)
        val now = clock.instant()
        val items = mutableListOf<Appointment>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val page = when (val result = read { auth -> api.appointments(auth, VirtualSessionsApi.APPOINTMENTS_LIMIT, cursor) }) {
                is Read.Ok -> result.value
                is Read.Failed -> return result.refresh
            }
            items += try {
                page.items.map { it.toAppointment() }
            } catch (_: InvalidVirtualSessionsResponse) {
                return VirtualSessionsRefresh.InvalidResponse
            }
            cursor = page.nextCursor ?: return storeAppointments(owner, AppointmentsSnapshot(items, now))
        }
        return storeAppointments(owner, AppointmentsSnapshot(items, now))
    }

    override suspend fun book(owner: String, serviceId: String, availabilityId: String, idempotencyKey: String): BookResult {
        val api = api ?: return BookResult.Unavailable(null)
        val body = BookAppointmentRequestDto(serviceId, availabilityId)
        val result = when (val outcome = command { auth -> api.book(auth, idempotencyKey, body) }) {
            is Command.Done -> outcome.result
            Command.SignedOut -> return BookResult.SignedOut
            Command.RenewalUnavailable -> return BookResult.Unavailable(null)
        }
        return when (result) {
            is ApiResult.Success -> try {
                result.value.toAppointment().also { remember(owner, it) }.let(BookResult::Booked)
            } catch (_: InvalidVirtualSessionsResponse) {
                BookResult.InvalidResponse
            }
            is ApiResult.HttpFailure -> when {
                result.status == 409 && result.code == CODE_FULL -> BookResult.Full
                result.status == 409 && result.code == CODE_EXISTS ->
                    BookResult.AlreadyBooked(result.fieldIssues.firstOrNull { it.field == FIELD_APPOINTMENT_ID }?.issue)
                result.status == 409 && result.code == CODE_KEY_REUSED -> BookResult.KeyReused
                result.status == 403 && result.code == CODE_CAPABILITY_REQUIRED -> BookResult.CapabilityRequired
                result.status == 403 -> BookResult.PermissionRequired
                result.status == 422 && result.code == CODE_SERVICE_NOT_BOOKABLE -> BookResult.ServiceNotBookable
                result.status == 422 && result.code == CODE_AVAILABILITY_NOT_BOOKABLE -> BookResult.SlotNotBookable
                result.status == 422 -> BookResult.Invalid
                else -> BookResult.Unavailable(result.correlationId)
            }
            ApiResult.NoConnection -> BookResult.NoConnection
            ApiResult.Timeout, ApiResult.NotConfigured -> BookResult.Unavailable(null)
            ApiResult.InvalidResponse -> BookResult.InvalidResponse
        }
    }

    override suspend fun cancel(owner: String, appointmentId: String): CancelResult {
        val api = api ?: return CancelResult.Unavailable(null)
        val result = when (val outcome = command { auth -> api.cancel(auth, appointmentId) }) {
            is Command.Done -> outcome.result
            Command.SignedOut -> return CancelResult.SignedOut
            Command.RenewalUnavailable -> return CancelResult.Unavailable(null)
        }
        return when (result) {
            is ApiResult.Success -> try {
                result.value.toAppointment().also { remember(owner, it) }.let(CancelResult::Cancelled)
            } catch (_: InvalidVirtualSessionsResponse) {
                CancelResult.InvalidResponse
            }
            is ApiResult.HttpFailure -> when {
                result.status == 422 && result.code == CODE_WINDOW_CLOSED -> CancelResult.WindowClosed
                result.status == 422 -> CancelResult.Invalid
                result.status == 404 -> CancelResult.NotFound
                result.status == 403 -> CancelResult.PermissionRequired
                else -> CancelResult.Unavailable(result.correlationId)
            }
            ApiResult.NoConnection -> CancelResult.NoConnection
            ApiResult.Timeout, ApiResult.NotConfigured -> CancelResult.Unavailable(null)
            ApiResult.InvalidResponse -> CancelResult.InvalidResponse
        }
    }

    override suspend fun clear(owner: String) {
        dao.deleteCatalog(owner)
        dao.deleteAppointments(owner)
    }

    override suspend fun clearAllExcept(owner: String) {
        dao.deleteCatalogsExcept(owner)
        dao.deleteAppointmentsExcept(owner)
    }

    // ----------------------------------------------------------------- transporte

    private sealed interface Read<out T> {
        data class Ok<T>(val value: T) : Read<T>
        data class Failed(val refresh: VirtualSessionsRefresh) : Read<Nothing>
    }

    private suspend fun <T : Any> read(call: suspend (authorization: String) -> retrofit2.Response<T>): Read<T> {
        val result = when (val outcome = sessionManager.authorized { authorization -> apiCall(json) { call(authorization) } }) {
            is AuthorizedResult.Completed -> outcome.result
            AuthorizedResult.SignedOut -> return Read.Failed(VirtualSessionsRefresh.SignedOut)
            AuthorizedResult.RenewalUnavailable -> return Read.Failed(VirtualSessionsRefresh.Unavailable(null))
        }
        return when (result) {
            is ApiResult.Success -> Read.Ok(result.value)
            is ApiResult.HttpFailure -> Read.Failed(
                when (result.status) {
                    403 -> VirtualSessionsRefresh.PermissionRequired
                    422 -> VirtualSessionsRefresh.InvalidRequest
                    else -> VirtualSessionsRefresh.Unavailable(result.correlationId)
                }
            )
            ApiResult.NoConnection -> Read.Failed(VirtualSessionsRefresh.NoConnection)
            ApiResult.Timeout, ApiResult.NotConfigured -> Read.Failed(VirtualSessionsRefresh.Unavailable(null))
            ApiResult.InvalidResponse -> Read.Failed(VirtualSessionsRefresh.InvalidResponse)
        }
    }

    private sealed interface Command<out T> {
        data class Done<T>(val result: ApiResult<T>) : Command<T>
        data object SignedOut : Command<Nothing>
        data object RenewalUnavailable : Command<Nothing>
    }

    /** Ejecuta el comando con reintentos automáticos acotados ante fallas transitorias. */
    private suspend fun <T : Any> command(call: suspend (authorization: String) -> retrofit2.Response<T>): Command<T> {
        var attempt = 0
        while (true) {
            val result = when (val outcome = sessionManager.authorized { authorization -> apiCall(json) { call(authorization) } }) {
                is AuthorizedResult.Completed -> outcome.result
                AuthorizedResult.SignedOut -> return Command.SignedOut
                AuthorizedResult.RenewalUnavailable -> return Command.RenewalUnavailable
            }
            if (result.isTransient() && attempt < VirtualSessionsGateway.MAX_AUTOMATIC_RETRIES) {
                attempt++
                retryDelay(attempt)
                continue
            }
            return Command.Done(result)
        }
    }

    private fun ApiResult<*>.isTransient(): Boolean = when (this) {
        ApiResult.NoConnection, ApiResult.Timeout -> true
        is ApiResult.HttpFailure -> status in TRANSIENT_STATUSES
        else -> false
    }

    // ----------------------------------------------------------------- caché

    private suspend fun storeCatalog(owner: String, snapshot: CatalogSnapshot): VirtualSessionsRefresh = store {
        dao.replaceCatalog(VirtualSessionsCatalogEntity(owner, codec.encodeCatalog(snapshot)))
    }

    private suspend fun storeAppointments(owner: String, snapshot: AppointmentsSnapshot): VirtualSessionsRefresh = store {
        dao.replaceAppointments(VirtualSessionsAppointmentsEntity(owner, codec.encodeAppointments(snapshot)))
    }

    private suspend fun store(write: suspend () -> Unit): VirtualSessionsRefresh = try {
        write()
        VirtualSessionsRefresh.Updated
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        VirtualSessionsRefresh.StorageFailed
    }

    /**
     * Incorpora a «Mis sesiones» la cita que el backend acaba de confirmar. Si la caché no puede
     * actualizarse, el resultado del comando sigue siendo válido: la próxima consulta converge.
     */
    private suspend fun remember(owner: String, appointment: Appointment) {
        try {
            val current = dao.readAppointments(owner)?.let(codec::decodeAppointments)
            val updated = current?.upsert(appointment, current.syncedAt) ?: AppointmentsSnapshot(listOf(appointment), clock.instant())
            dao.replaceAppointments(VirtualSessionsAppointmentsEntity(owner, codec.encodeAppointments(updated)))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // La consulta siguiente de «Mis sesiones» vuelve a traer la cita confirmada.
        }
    }

    private fun wire(instant: Instant): String = WIRE.format(instant.atZone(clock.zone))

    companion object {
        /** Páginas máximas por lectura (hasta 250 servicios o 500 citas); más allá se declara incompleta. */
        const val MAX_PAGES = 5
        const val BASE_RETRY_DELAY_MS = 500L
        private val CATEGORIES = listOf(VirtualSessionsApi.CATEGORY_COACHING)
        private val MODALITIES = ServiceModality.entries.map { it.wire }
        private val TRANSIENT_STATUSES = setOf(502, 503, 504)
        private val WIRE: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)
        private const val CODE_FULL = "availability_full"
        private const val CODE_EXISTS = "appointment_exists"
        private const val CODE_KEY_REUSED = "idempotency_key_reused"
        private const val CODE_CAPABILITY_REQUIRED = "capability_required"
        private const val CODE_SERVICE_NOT_BOOKABLE = "service_not_bookable"
        private const val CODE_AVAILABILITY_NOT_BOOKABLE = "availability_not_bookable"
        private const val CODE_WINDOW_CLOSED = "cancellation_window_closed"
        private const val FIELD_APPOINTMENT_ID = "appointment_id"
    }
}

/**
 * Elimina el catálogo y «Mis sesiones» cuando termina la sesión de un deportista (cierre,
 * vencimiento o cambio de cuenta) y, al autenticarse uno, los de cualquier otro deportista que
 * hubieran quedado en el dispositivo (patrón ADR-031).
 */
class VirtualSessionsCacheJanitor(
    private val currentUser: StateFlow<SessionUser?>,
    private val store: VirtualSessionsCacheStore,
) {
    fun start(scope: CoroutineScope) = scope.launch {
        var previous: String? = null
        currentUser
            .map { user -> user?.userId?.let(VirtualSessionsOwner::keyOf) }
            .distinctUntilChanged()
            .catch { }
            .collect { owner ->
                try {
                    previous?.takeIf { it != owner }?.let { store.clear(it) }
                    owner?.let { store.clearAllExcept(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // La limpieza se reintenta en el próximo cambio de sesión; la partición protege mientras tanto.
                }
                previous = owner
            }
    }
}
