package com.lfnj.sport_app.features.events.data

import com.lfnj.sport_app.core.network.ApiResult
import com.lfnj.sport_app.core.network.apiCall
import com.lfnj.sport_app.core.session.AuthorizedResult
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.events.data.local.EventRegistrationsCacheDao
import com.lfnj.sport_app.features.events.data.local.EventRegistrationsCacheEntity
import com.lfnj.sport_app.features.events.data.local.EventRegistrationsCodec
import com.lfnj.sport_app.features.events.model.EventDetail
import com.lfnj.sport_app.features.events.model.EventIds
import com.lfnj.sport_app.features.events.model.EventRegistration
import com.lfnj.sport_app.features.events.model.EventRegistrationsOwner
import com.lfnj.sport_app.features.events.model.RegistrationsSnapshot
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

/** Resultado de consultar el detalle. Ninguna variante de falla contiene datos del evento. */
sealed interface EventDetailResult {
    data class Loaded(val detail: EventDetail) : EventDetailResult
    /** `404 event_not_found`: inexistente o no publicado; nunca se revela un borrador (CA2). */
    data object NotFound : EventDetailResult
    data object NoConnection : EventDetailResult
    /** `503`/`504`, tiempo agotado o renovación de sesión no disponible: reintentar luego. */
    data class Unavailable(val correlationId: String?) : EventDetailResult
    /** `403 permission_required`: la cuenta no es de deportista (CA9). */
    data object PermissionRequired : EventDetailResult
    data object InvalidResponse : EventDetailResult
    /** La sesión ya no es válida: volver al acceso. */
    data object SignedOut : EventDetailResult
}

/** Resultado del comando de inscripción (ADR-032 «Inscribirse» y «Errores»). */
sealed interface RegisterResult {
    /** `201`: inscripción `REGISTERED` confirmada por el backend (CA1, CA6). */
    data class Registered(val registration: EventRegistration) : RegisterResult
    /** `409 event_registration_exists`, con el `registration_id` existente si vino en `details` (CA4). */
    data class AlreadyRegistered(val registrationId: String?) : RegisterResult
    /** `409 event_full` (CA5). */
    data object Full : RegisterResult
    /** `409 event_registration_closed`: el evento ya inició (CA3). */
    data object Closed : RegisterResult
    /** `409 idempotency_key_reused`: la clave pertenece a otro evento; se renueva (CA6). */
    data object KeyReused : RegisterResult
    data object NotFound : RegisterResult
    data object PermissionRequired : RegisterResult
    /** `422 validation_error`: no debería ocurrir con la clave UUID del cliente. */
    data class Invalid(val issues: List<String>) : RegisterResult
    /** Sin red tras agotar los reintentos automáticos; el resultado es desconocido. */
    data object NoConnection : RegisterResult
    /** `502`/`503`/`504` o tiempo agotado tras los reintentos; el resultado es desconocido. */
    data class Unavailable(val correlationId: String?) : RegisterResult
    data object InvalidResponse : RegisterResult
    data object SignedOut : RegisterResult
}

/** Resultado de consultar «Mis inscripciones». Ninguna variante contiene inscripciones. */
sealed interface RegistrationsRefresh {
    /** El BFF confirmó la página y la caché local quedó actualizada. */
    data object Updated : RegistrationsRefresh
    data object NoConnection : RegistrationsRefresh
    data class Unavailable(val correlationId: String?) : RegistrationsRefresh
    data object PermissionRequired : RegistrationsRefresh
    /** `422 validation_error` (`limit` o `cursor`). */
    data class InvalidRequest(val issues: List<String>) : RegistrationsRefresh
    data object InvalidResponse : RegistrationsRefresh
    data object SignedOut : RegistrationsRefresh
    /** La lista llegó, pero no pudo guardarse en el dispositivo. */
    data object StorageFailed : RegistrationsRefresh
}

/** Operaciones de la pantalla de detalle; las implementa [EventRegistrationRepository]. */
interface EventDetailGateway {
    suspend fun detail(eventId: String): EventDetailResult

    /**
     * Envía la inscripción con [idempotencyKey] y la conserva en los reintentos automáticos del
     * mismo intento (red, `502`, `503`, `504`, hasta [MAX_AUTOMATIC_RETRIES] veces). Un `4xx` nunca
     * se repite automáticamente (ADR-032 §8).
     */
    suspend fun register(eventId: String, idempotencyKey: String): RegisterResult

    companion object {
        const val MAX_AUTOMATIC_RETRIES = 3
    }
}

/** Operaciones de «Mis inscripciones». */
interface MyRegistrationsGateway {
    /** Últimas inscripciones confirmadas del deportista, o `null` si no existen o son ilegibles. */
    fun observe(owner: String): Flow<RegistrationsSnapshot?>
    /** Primera página confirmada; reemplaza la caché. */
    suspend fun refresh(owner: String): RegistrationsRefresh
    /** Página siguiente del último cursor guardado, agregada a la caché. */
    suspend fun loadMore(owner: String): RegistrationsRefresh
}

/** Limpieza de la caché local (cierre de sesión o cambio de deportista). */
interface EventRegistrationsCacheStore {
    suspend fun clear(owner: String)
    suspend fun clearAllExcept(owner: String)
}

/**
 * HU041 (ADR-032): detalle e inscripción solo con conectividad, sin cola offline; «Mis
 * inscripciones» offline first desde la caché cifrada del deportista, reemplazada con cada
 * respuesta confirmada por `bff-mobile`.
 */
class EventRegistrationRepository(
    private val dao: EventRegistrationsCacheDao,
    private val codec: EventRegistrationsCodec,
    private val api: EventRegistrationApi?,
    private val sessionManager: SessionManager,
    private val json: Json,
    private val clock: Clock = Clock.systemUTC(),
    /** Espera antes del reintento automático número `attempt` (1, 2, 3). */
    private val retryDelay: suspend (attempt: Int) -> Unit = { attempt -> delay(BASE_RETRY_DELAY_MS shl (attempt - 1)) },
) : EventDetailGateway, MyRegistrationsGateway, EventRegistrationsCacheStore {

    override suspend fun detail(eventId: String): EventDetailResult {
        if (!EventIds.isValid(eventId)) return EventDetailResult.NotFound
        val api = api ?: return EventDetailResult.Unavailable(null)
        val result = when (val outcome = sessionManager.authorized { apiCall(json) { api.detail(it, eventId) } }) {
            is AuthorizedResult.Completed -> outcome.result
            AuthorizedResult.SignedOut -> return EventDetailResult.SignedOut
            AuthorizedResult.RenewalUnavailable -> return EventDetailResult.Unavailable(null)
        }
        return when (result) {
            is ApiResult.Success -> try {
                EventDetailResult.Loaded(result.value.toDetail())
            } catch (_: InvalidEventResponse) {
                EventDetailResult.InvalidResponse
            }
            is ApiResult.HttpFailure -> when (result.status) {
                403 -> EventDetailResult.PermissionRequired
                404 -> EventDetailResult.NotFound
                else -> EventDetailResult.Unavailable(result.correlationId)
            }
            ApiResult.NoConnection -> EventDetailResult.NoConnection
            ApiResult.Timeout, ApiResult.NotConfigured -> EventDetailResult.Unavailable(null)
            ApiResult.InvalidResponse -> EventDetailResult.InvalidResponse
        }
    }

    override suspend fun register(eventId: String, idempotencyKey: String): RegisterResult {
        if (!EventIds.isValid(eventId)) return RegisterResult.NotFound
        val api = api ?: return RegisterResult.Unavailable(null)
        var attempt = 0
        while (true) {
            val result = when (val outcome = sessionManager.authorized { apiCall(json) { api.register(it, idempotencyKey, eventId) } }) {
                is AuthorizedResult.Completed -> outcome.result
                AuthorizedResult.SignedOut -> return RegisterResult.SignedOut
                AuthorizedResult.RenewalUnavailable -> return RegisterResult.Unavailable(null)
            }
            if (result.isTransient() && attempt < EventDetailGateway.MAX_AUTOMATIC_RETRIES) {
                attempt++
                retryDelay(attempt)
                continue
            }
            return result.toRegisterResult()
        }
    }

    private fun ApiResult<*>.isTransient(): Boolean = when (this) {
        ApiResult.NoConnection, ApiResult.Timeout -> true
        is ApiResult.HttpFailure -> status in TRANSIENT_STATUSES
        else -> false
    }

    private fun ApiResult<RegistrationDto>.toRegisterResult(): RegisterResult = when (this) {
        is ApiResult.Success -> try {
            RegisterResult.Registered(value.toRegistration())
        } catch (_: InvalidEventResponse) {
            RegisterResult.InvalidResponse
        }
        is ApiResult.HttpFailure -> when {
            status == 409 && code == CODE_EXISTS -> RegisterResult.AlreadyRegistered(
                fieldIssues.firstOrNull { it.field == FIELD_REGISTRATION_ID }?.issue?.takeIf(EventIds::isValid)
            )
            status == 409 && code == CODE_FULL -> RegisterResult.Full
            status == 409 && code == CODE_CLOSED -> RegisterResult.Closed
            status == 409 && code == CODE_KEY_REUSED -> RegisterResult.KeyReused
            status == 403 -> RegisterResult.PermissionRequired
            status == 404 -> RegisterResult.NotFound
            status == 422 -> RegisterResult.Invalid(issues)
            else -> RegisterResult.Unavailable(correlationId)
        }
        ApiResult.NoConnection -> RegisterResult.NoConnection
        ApiResult.Timeout, ApiResult.NotConfigured -> RegisterResult.Unavailable(null)
        ApiResult.InvalidResponse -> RegisterResult.InvalidResponse
    }

    override fun observe(owner: String): Flow<RegistrationsSnapshot?> =
        dao.observe(owner).map { entity -> entity?.let(codec::decode) }.distinctUntilChanged()

    override suspend fun refresh(owner: String): RegistrationsRefresh = fetch(cursor = null) { page ->
        RegistrationsSnapshot(page.items, page.nextCursor, clock.instant())
    }.let { (outcome, snapshot) -> snapshot?.let { store(owner, it) } ?: outcome }

    override suspend fun loadMore(owner: String): RegistrationsRefresh {
        val current = dao.read(owner)?.let(codec::decode) ?: return refresh(owner)
        val cursor = current.nextCursor ?: return RegistrationsRefresh.Updated
        val (outcome, snapshot) = fetch(cursor) { page -> current.append(page.items, page.nextCursor) }
        // Un cursor que el BFF ya no acepta se reemplaza consultando desde el principio.
        if (outcome is RegistrationsRefresh.InvalidRequest) return refresh(owner)
        return snapshot?.let { store(owner, it) } ?: outcome
    }

    private class Page(val items: List<EventRegistration>, val nextCursor: String?)

    private suspend fun fetch(
        cursor: String?,
        build: (Page) -> RegistrationsSnapshot,
    ): Pair<RegistrationsRefresh, RegistrationsSnapshot?> {
        val api = api ?: return RegistrationsRefresh.Unavailable(null) to null
        val result = when (val outcome = sessionManager.authorized { apiCall(json) { api.myRegistrations(it, PAGE_SIZE, cursor) } }) {
            is AuthorizedResult.Completed -> outcome.result
            AuthorizedResult.SignedOut -> return RegistrationsRefresh.SignedOut to null
            AuthorizedResult.RenewalUnavailable -> return RegistrationsRefresh.Unavailable(null) to null
        }
        val failure = when (result) {
            is ApiResult.Success -> return try {
                RegistrationsRefresh.Updated to build(Page(result.value.items.map { it.toRegistration() }, result.value.nextCursor))
            } catch (_: InvalidEventResponse) {
                RegistrationsRefresh.InvalidResponse to null
            }
            is ApiResult.HttpFailure -> when (result.status) {
                403 -> RegistrationsRefresh.PermissionRequired
                422 -> RegistrationsRefresh.InvalidRequest(result.issues)
                else -> RegistrationsRefresh.Unavailable(result.correlationId)
            }
            ApiResult.NoConnection -> RegistrationsRefresh.NoConnection
            ApiResult.Timeout, ApiResult.NotConfigured -> RegistrationsRefresh.Unavailable(null)
            ApiResult.InvalidResponse -> RegistrationsRefresh.InvalidResponse
        }
        return failure to null
    }

    private suspend fun store(owner: String, snapshot: RegistrationsSnapshot): RegistrationsRefresh = try {
        dao.replace(EventRegistrationsCacheEntity(owner, codec.encode(snapshot)))
        RegistrationsRefresh.Updated
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        RegistrationsRefresh.StorageFailed
    }

    override suspend fun clear(owner: String) = dao.delete(owner)

    override suspend fun clearAllExcept(owner: String) = dao.deleteAllExcept(owner)

    companion object {
        /** Tamaño de página de «Mis inscripciones»: el valor por defecto del contrato (1 a 100). */
        const val PAGE_SIZE = 20
        const val BASE_RETRY_DELAY_MS = 500L
        private val TRANSIENT_STATUSES = setOf(502, 503, 504)
        private const val CODE_EXISTS = "event_registration_exists"
        private const val CODE_FULL = "event_full"
        private const val CODE_CLOSED = "event_registration_closed"
        private const val CODE_KEY_REUSED = "idempotency_key_reused"
        private const val FIELD_REGISTRATION_ID = "registration_id"
    }
}

/**
 * Elimina «Mis inscripciones» cuando termina la sesión de un deportista (cierre, vencimiento o
 * cambio de cuenta) y, al autenticarse uno, la de cualquier otro que hubiera quedado en el
 * dispositivo (ADR-032 §9, como HU035).
 */
class EventRegistrationsCacheJanitor(
    private val currentUser: StateFlow<SessionUser?>,
    private val store: EventRegistrationsCacheStore,
) {
    fun start(scope: CoroutineScope) = scope.launch {
        var previous: String? = null
        currentUser
            .map { user -> user?.userId?.let(EventRegistrationsOwner::keyOf) }
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
