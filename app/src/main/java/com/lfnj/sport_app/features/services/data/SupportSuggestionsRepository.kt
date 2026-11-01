package com.lfnj.sport_app.features.services.data

import com.lfnj.sport_app.core.network.ApiResult
import com.lfnj.sport_app.core.network.apiCall
import com.lfnj.sport_app.core.session.AuthorizedResult
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.services.data.local.SupportSuggestionsCacheDao
import com.lfnj.sport_app.features.services.data.local.SupportSuggestionsCacheEntity
import com.lfnj.sport_app.features.services.data.local.SupportSuggestionsCodec
import com.lfnj.sport_app.features.services.model.SupportSuggestionsOwner
import com.lfnj.sport_app.features.services.model.SupportSuggestionsSnapshot
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

/** Resultado de consultar las sugerencias al BFF. Ninguna variante contiene servicios ni proveedores. */
sealed interface SupportSuggestionsRefresh {
    /** El BFF confirmó la lista y la caché local quedó reemplazada. */
    data object Updated : SupportSuggestionsRefresh
    data object NoConnection : SupportSuggestionsRefresh
    /** `503`/`504` (Servicios o el plan sin respuesta, CA5), tiempo agotado o renovación no disponible. */
    data class Unavailable(val correlationId: String?) : SupportSuggestionsRefresh
    /** `403 capability_required`: el plan vigente no incluye `services.support.suggest`. */
    data object CapabilityRequired : SupportSuggestionsRefresh
    /** `403 permission_required`: la cuenta no es de deportista. */
    data object PermissionRequired : SupportSuggestionsRefresh
    /** `422 validation_error`: no debería ocurrir con el `limit` fijo del cliente. */
    data object InvalidRequest : SupportSuggestionsRefresh
    data object InvalidResponse : SupportSuggestionsRefresh
    /** La sesión ya no es válida: volver al acceso. */
    data object SignedOut : SupportSuggestionsRefresh
    /** La lista llegó, pero no pudo guardarse en el dispositivo. */
    data object StorageFailed : SupportSuggestionsRefresh
}

/** Operaciones de la pantalla de sugerencias; las implementa [SupportSuggestionsRepository]. */
interface SupportSuggestionsGateway {
    /** Última lista confirmada del deportista, o `null` si no existe o es ilegible. */
    fun observe(owner: String): Flow<SupportSuggestionsSnapshot?>
    suspend fun refresh(owner: String): SupportSuggestionsRefresh
}

/** Limpieza de la caché local (cierre de sesión o cambio de deportista). */
interface SupportSuggestionsCacheStore {
    suspend fun clear(owner: String)
    suspend fun clearAllExcept(owner: String)
}

/**
 * Servicios de acompañamiento sugeridos offline first (ADR-041 §5): la pantalla observa la caché
 * cifrada del deportista y [refresh] la reemplaza con cada lista confirmada por `bff-mobile`. Es
 * solo lectura: sugerir no contrata ni reserva, no hay cola de comandos ni WorkManager.
 */
class SupportSuggestionsRepository(
    private val dao: SupportSuggestionsCacheDao,
    private val codec: SupportSuggestionsCodec,
    private val api: SupportSuggestionsApi?,
    private val sessionManager: SessionManager,
    private val json: Json,
    private val clock: Clock = Clock.systemUTC(),
) : SupportSuggestionsGateway, SupportSuggestionsCacheStore {

    override fun observe(owner: String): Flow<SupportSuggestionsSnapshot?> =
        dao.observe(owner).map { entity -> entity?.let(codec::decode) }.distinctUntilChanged()

    override suspend fun refresh(owner: String): SupportSuggestionsRefresh {
        val api = api ?: return SupportSuggestionsRefresh.Unavailable(null)
        val outcome = sessionManager.authorized { authorization ->
            apiCall(json) { api.services(authorization, SupportSuggestionsApi.LIMIT) }
        }
        val result = when (outcome) {
            is AuthorizedResult.Completed -> outcome.result
            AuthorizedResult.SignedOut -> return SupportSuggestionsRefresh.SignedOut
            AuthorizedResult.RenewalUnavailable -> return SupportSuggestionsRefresh.Unavailable(null)
        }
        return when (result) {
            is ApiResult.Success -> store(owner, result.value)
            is ApiResult.HttpFailure -> when {
                result.status == 403 && result.code == CODE_CAPABILITY_REQUIRED -> SupportSuggestionsRefresh.CapabilityRequired
                result.status == 403 -> SupportSuggestionsRefresh.PermissionRequired
                result.status == 422 -> SupportSuggestionsRefresh.InvalidRequest
                else -> SupportSuggestionsRefresh.Unavailable(result.correlationId)
            }
            ApiResult.NoConnection -> SupportSuggestionsRefresh.NoConnection
            ApiResult.Timeout, ApiResult.NotConfigured -> SupportSuggestionsRefresh.Unavailable(null)
            ApiResult.InvalidResponse -> SupportSuggestionsRefresh.InvalidResponse
        }
    }

    private suspend fun store(owner: String, body: ServiceSuggestionsResponseDto): SupportSuggestionsRefresh {
        val snapshot = try {
            body.toSnapshot(clock.instant())
        } catch (_: InvalidSupportSuggestionsResponse) {
            return SupportSuggestionsRefresh.InvalidResponse
        }
        return try {
            dao.replace(SupportSuggestionsCacheEntity(owner, codec.encode(snapshot)))
            SupportSuggestionsRefresh.Updated
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            SupportSuggestionsRefresh.StorageFailed
        }
    }

    override suspend fun clear(owner: String) = dao.delete(owner)

    override suspend fun clearAllExcept(owner: String) = dao.deleteAllExcept(owner)

    private companion object {
        const val CODE_CAPABILITY_REQUIRED = "capability_required"
    }
}

/**
 * Elimina la caché de sugerencias cuando termina la sesión de un deportista (cierre, vencimiento o
 * cambio de cuenta) y, al autenticarse uno, la de cualquier otro deportista que hubiera quedado en
 * el dispositivo (ADR-041 §5, patrón ADR-031).
 */
class SupportSuggestionsCacheJanitor(
    private val currentUser: StateFlow<SessionUser?>,
    private val store: SupportSuggestionsCacheStore,
) {
    fun start(scope: CoroutineScope) = scope.launch {
        var previous: String? = null
        currentUser
            .map { user -> user?.userId?.let(SupportSuggestionsOwner::keyOf) }
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
