package com.lfnj.sport_app.features.events.data

import com.lfnj.sport_app.core.network.ApiResult
import com.lfnj.sport_app.core.network.apiCall
import com.lfnj.sport_app.core.session.AuthorizedResult
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.events.data.local.EventSuggestionsCacheDao
import com.lfnj.sport_app.features.events.data.local.EventSuggestionsCacheEntity
import com.lfnj.sport_app.features.events.data.local.EventSuggestionsCodec
import com.lfnj.sport_app.features.events.model.EventSuggestionsOwner
import com.lfnj.sport_app.features.events.model.EventSuggestionsSnapshot
import com.lfnj.sport_app.features.recommendations.data.InvalidSuggestionsResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.Clock

/** Resultado de consultar los eventos sugeridos al BFF. Ninguna variante contiene eventos ni lugares. */
sealed interface EventSuggestionsRefresh {
    /** El BFF confirmó la lista y la caché local quedó reemplazada. */
    data object Updated : EventSuggestionsRefresh
    data object NoConnection : EventSuggestionsRefresh
    /** `503`/`504`, tiempo agotado o renovación de sesión no disponible: reintentar luego. */
    data class Unavailable(val correlationId: String?) : EventSuggestionsRefresh
    /** `403 capability_required`: el plan vigente no incluye `events.suggest` (o lo que exige Eventos). */
    data object CapabilityRequired : EventSuggestionsRefresh
    /** `403 permission_required`: la cuenta no es de deportista. */
    data object PermissionRequired : EventSuggestionsRefresh
    /** `422 validation_error`: no debería ocurrir con el `limit` fijo del cliente. */
    data object InvalidRequest : EventSuggestionsRefresh
    data object InvalidResponse : EventSuggestionsRefresh
    /** La sesión ya no es válida: volver al acceso. */
    data object SignedOut : EventSuggestionsRefresh
    /** La lista llegó, pero no pudo guardarse en el dispositivo. */
    data object StorageFailed : EventSuggestionsRefresh
}

/** Operaciones de la pantalla de eventos sugeridos; las implementa [EventSuggestionsRepository]. */
interface EventSuggestionsGateway {
    /** Última lista confirmada del deportista, o `null` si no existe o es ilegible. */
    fun observe(owner: String): Flow<EventSuggestionsSnapshot?>
    suspend fun refresh(owner: String): EventSuggestionsRefresh
}

/** Limpieza de la caché local (cierre de sesión o cambio de deportista). */
interface EventSuggestionsCacheStore {
    suspend fun clear(owner: String)
    suspend fun clearAllExcept(owner: String)
}

/**
 * Eventos sugeridos offline first (ADR-041 §5): la pantalla observa la caché cifrada del deportista
 * y [refresh] la reemplaza con cada lista confirmada por `bff-mobile`. Es solo lectura: sugerir no
 * inscribe (HU041), no hay cola de comandos ni WorkManager.
 */
class EventSuggestionsRepository(
    private val dao: EventSuggestionsCacheDao,
    private val codec: EventSuggestionsCodec,
    private val api: EventSuggestionsApi?,
    private val sessionManager: SessionManager,
    private val json: Json,
    private val clock: Clock = Clock.systemUTC(),
) : EventSuggestionsGateway, EventSuggestionsCacheStore {

    override fun observe(owner: String): Flow<EventSuggestionsSnapshot?> =
        dao.observe(owner).map { entity -> entity?.let(codec::decode) }.distinctUntilChanged()

    override suspend fun refresh(owner: String): EventSuggestionsRefresh {
        val api = api ?: return EventSuggestionsRefresh.Unavailable(null)
        val outcome = sessionManager.authorized { authorization ->
            apiCall(json) { api.events(authorization, EventSuggestionsApi.LIMIT) }
        }
        val result = when (outcome) {
            is AuthorizedResult.Completed -> outcome.result
            AuthorizedResult.SignedOut -> return EventSuggestionsRefresh.SignedOut
            AuthorizedResult.RenewalUnavailable -> return EventSuggestionsRefresh.Unavailable(null)
        }
        return when (result) {
            is ApiResult.Success -> store(owner, result.value)
            is ApiResult.HttpFailure -> when {
                result.status == 403 && result.code == CODE_CAPABILITY_REQUIRED -> EventSuggestionsRefresh.CapabilityRequired
                result.status == 403 -> EventSuggestionsRefresh.PermissionRequired
                result.status == 422 -> EventSuggestionsRefresh.InvalidRequest
                else -> EventSuggestionsRefresh.Unavailable(result.correlationId)
            }
            ApiResult.NoConnection -> EventSuggestionsRefresh.NoConnection
            ApiResult.Timeout, ApiResult.NotConfigured -> EventSuggestionsRefresh.Unavailable(null)
            ApiResult.InvalidResponse -> EventSuggestionsRefresh.InvalidResponse
        }
    }

    private suspend fun store(owner: String, body: EventSuggestionsResponseDto): EventSuggestionsRefresh {
        val snapshot = try {
            body.toSnapshot(clock.instant())
        } catch (_: InvalidSuggestionsResponse) {
            return EventSuggestionsRefresh.InvalidResponse
        }
        return try {
            dao.replace(EventSuggestionsCacheEntity(owner, codec.encode(snapshot)))
            EventSuggestionsRefresh.Updated
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            EventSuggestionsRefresh.StorageFailed
        }
    }

    override suspend fun clear(owner: String) = dao.delete(owner)

    override suspend fun clearAllExcept(owner: String) = dao.deleteAllExcept(owner)

    private companion object {
        const val CODE_CAPABILITY_REQUIRED = "capability_required"
    }
}

/**
 * Elimina la caché de eventos sugeridos cuando termina la sesión de un deportista (cierre,
 * vencimiento o cambio de cuenta) y, al autenticarse uno, la de cualquier otro deportista que
 * hubiera quedado en el dispositivo (ADR-041 §5, patrón ADR-031).
 */
class EventSuggestionsCacheJanitor(
    private val currentUser: StateFlow<SessionUser?>,
    private val store: EventSuggestionsCacheStore,
) {
    fun start(scope: CoroutineScope) = scope.launch {
        var previous: String? = null
        currentUser
            .map { user -> user?.userId?.let(EventSuggestionsOwner::keyOf) }
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
