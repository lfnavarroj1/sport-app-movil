package com.lfnj.sport_app.features.nutrition.routines.data

import com.lfnj.sport_app.core.network.ApiResult
import com.lfnj.sport_app.core.network.apiCall
import com.lfnj.sport_app.core.session.AuthorizedResult
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.nutrition.routines.data.local.NutritionRoutinesCacheDao
import com.lfnj.sport_app.features.nutrition.routines.data.local.NutritionRoutinesCacheEntity
import com.lfnj.sport_app.features.nutrition.routines.data.local.NutritionRoutinesCodec
import com.lfnj.sport_app.features.nutrition.routines.model.NutritionRoutinesOwner
import com.lfnj.sport_app.features.nutrition.routines.model.NutritionRoutinesSnapshot
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

/** Resultado de consultar las rutinas al BFF. Ninguna variante contiene rutinas, autores ni alérgenos. */
sealed interface NutritionRoutinesRefresh {
    /** El BFF confirmó la lista y la caché local quedó reemplazada. */
    data object Updated : NutritionRoutinesRefresh
    data object NoConnection : NutritionRoutinesRefresh
    /** `503`/`504`, tiempo agotado o renovación de sesión no disponible: reintentar luego. */
    data class Unavailable(val correlationId: String?) : NutritionRoutinesRefresh
    /** `403 capability_required`: el plan vigente no incluye `nutrition.routines.suggest`. */
    data object CapabilityRequired : NutritionRoutinesRefresh
    /** `403 permission_required`: la cuenta no es de deportista. */
    data object PermissionRequired : NutritionRoutinesRefresh
    /** `422 validation_error`: no debería ocurrir con el `limit` fijo del cliente. */
    data object InvalidRequest : NutritionRoutinesRefresh
    data object InvalidResponse : NutritionRoutinesRefresh
    /** La sesión ya no es válida: volver al acceso. */
    data object SignedOut : NutritionRoutinesRefresh
    /** La lista llegó, pero no pudo guardarse en el dispositivo. */
    data object StorageFailed : NutritionRoutinesRefresh
}

/** Operaciones de la pantalla de rutinas; las implementa [NutritionRoutinesRepository]. */
interface NutritionRoutinesGateway {
    /** Última lista confirmada del deportista, o `null` si no existe o es ilegible. */
    fun observe(owner: String): Flow<NutritionRoutinesSnapshot?>
    suspend fun refresh(owner: String): NutritionRoutinesRefresh
}

/** Limpieza de la caché local (cierre de sesión o cambio de deportista). */
interface NutritionRoutinesCacheStore {
    suspend fun clear(owner: String)
    suspend fun clearAllExcept(owner: String)
}

/**
 * Rutinas sugeridas offline first (ADR-041 §5): la pantalla observa la caché cifrada del deportista
 * y [refresh] la reemplaza con cada lista confirmada por `bff-mobile`. Es solo lectura: sugerir no
 * asigna la rutina ni vende o entrega alimentos; no hay cola de comandos ni WorkManager. Las
 * restricciones y alergias del deportista nunca pasan por el cliente: el BFF las aplica.
 */
class NutritionRoutinesRepository(
    private val dao: NutritionRoutinesCacheDao,
    private val codec: NutritionRoutinesCodec,
    private val api: NutritionRoutinesApi?,
    private val sessionManager: SessionManager,
    private val json: Json,
    private val clock: Clock = Clock.systemUTC(),
) : NutritionRoutinesGateway, NutritionRoutinesCacheStore {

    override fun observe(owner: String): Flow<NutritionRoutinesSnapshot?> =
        dao.observe(owner).map { entity -> entity?.let(codec::decode) }.distinctUntilChanged()

    override suspend fun refresh(owner: String): NutritionRoutinesRefresh {
        val api = api ?: return NutritionRoutinesRefresh.Unavailable(null)
        val outcome = sessionManager.authorized { authorization ->
            apiCall(json) { api.nutritionRoutines(authorization, NutritionRoutinesApi.LIMIT) }
        }
        val result = when (outcome) {
            is AuthorizedResult.Completed -> outcome.result
            AuthorizedResult.SignedOut -> return NutritionRoutinesRefresh.SignedOut
            AuthorizedResult.RenewalUnavailable -> return NutritionRoutinesRefresh.Unavailable(null)
        }
        return when (result) {
            is ApiResult.Success -> store(owner, result.value)
            is ApiResult.HttpFailure -> when {
                result.status == 403 && result.code == CODE_CAPABILITY_REQUIRED -> NutritionRoutinesRefresh.CapabilityRequired
                result.status == 403 -> NutritionRoutinesRefresh.PermissionRequired
                result.status == 422 -> NutritionRoutinesRefresh.InvalidRequest
                else -> NutritionRoutinesRefresh.Unavailable(result.correlationId)
            }
            ApiResult.NoConnection -> NutritionRoutinesRefresh.NoConnection
            ApiResult.Timeout, ApiResult.NotConfigured -> NutritionRoutinesRefresh.Unavailable(null)
            ApiResult.InvalidResponse -> NutritionRoutinesRefresh.InvalidResponse
        }
    }

    private suspend fun store(owner: String, body: RoutineSuggestionsResponseDto): NutritionRoutinesRefresh {
        val snapshot = try {
            body.toSnapshot(clock.instant())
        } catch (_: InvalidRoutinesResponse) {
            return NutritionRoutinesRefresh.InvalidResponse
        }
        return try {
            dao.replace(NutritionRoutinesCacheEntity(owner, codec.encode(snapshot)))
            NutritionRoutinesRefresh.Updated
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            NutritionRoutinesRefresh.StorageFailed
        }
    }

    override suspend fun clear(owner: String) = dao.delete(owner)

    override suspend fun clearAllExcept(owner: String) = dao.deleteAllExcept(owner)

    private companion object {
        const val CODE_CAPABILITY_REQUIRED = "capability_required"
    }
}

/**
 * Elimina la caché de rutinas cuando termina la sesión de un deportista (cierre, vencimiento o
 * cambio de cuenta) y, al autenticarse uno, la de cualquier otro deportista que hubiera quedado en
 * el dispositivo (ADR-041 §5, patrón ADR-031).
 */
class NutritionRoutinesCacheJanitor(
    private val currentUser: StateFlow<SessionUser?>,
    private val store: NutritionRoutinesCacheStore,
) {
    fun start(scope: CoroutineScope) = scope.launch {
        var previous: String? = null
        currentUser
            .map { user -> user?.userId?.let(NutritionRoutinesOwner::keyOf) }
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
