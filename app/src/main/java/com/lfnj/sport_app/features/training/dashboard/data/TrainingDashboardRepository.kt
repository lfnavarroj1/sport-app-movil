package com.lfnj.sport_app.features.training.dashboard.data

import com.lfnj.sport_app.core.network.ApiResult
import com.lfnj.sport_app.core.network.apiCall
import com.lfnj.sport_app.core.session.AuthorizedResult
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.training.dashboard.data.local.TrainingDashboardCacheDao
import com.lfnj.sport_app.features.training.dashboard.data.local.TrainingDashboardCacheEntity
import com.lfnj.sport_app.features.training.dashboard.data.local.TrainingDashboardCodec
import com.lfnj.sport_app.features.training.dashboard.model.DashboardPeriod
import com.lfnj.sport_app.features.training.dashboard.model.DashboardRange
import com.lfnj.sport_app.features.training.dashboard.model.DashboardSnapshot
import com.lfnj.sport_app.features.training.session.model.TrainingOwner
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

/** Resultado de consultar el periodo al BFF. Ninguna variante contiene datos del tablero. */
sealed interface DashboardRefresh {
    /** El BFF confirmó el periodo y la caché local quedó reemplazada. */
    data object Updated : DashboardRefresh
    data object NoConnection : DashboardRefresh
    /** `503`/`504`, tiempo agotado o renovación de sesión no disponible: reintentar luego. */
    data class Unavailable(val correlationId: String?) : DashboardRefresh
    /** `403 capability_required`: el plan vigente no incluye `training.dashboard.view`. */
    data object CapabilityRequired : DashboardRefresh
    /** `403 permission_required`: la cuenta no es de deportista (CA4). */
    data object PermissionRequired : DashboardRefresh
    /** `422 validation_error` (CA4): periodo inválido o mayor de 366 días. */
    data class InvalidPeriod(val issues: List<String>) : DashboardRefresh
    data object InvalidResponse : DashboardRefresh
    /** La sesión ya no es válida: volver al acceso. */
    data object SignedOut : DashboardRefresh
    /** El tablero llegó, pero no pudo guardarse en el dispositivo. */
    data object StorageFailed : DashboardRefresh
}

/** Operaciones de la pantalla del tablero; las implementa [TrainingDashboardRepository]. */
interface DashboardGateway {
    /** Último tablero confirmado del deportista para [range], o `null` si no existe o es ilegible. */
    fun observe(owner: String, range: DashboardRange): Flow<DashboardSnapshot?>
    suspend fun refresh(owner: String, period: DashboardPeriod): DashboardRefresh
}

/** Limpieza de la caché local (cierre de sesión o cambio de deportista). */
interface DashboardCacheStore {
    suspend fun clear(owner: String)
    suspend fun clearAllExcept(owner: String)
}

/**
 * Tablero de entrenamientos offline first (ADR-043 §5): la pantalla observa la caché cifrada del
 * deportista y [refresh] la reemplaza con cada periodo confirmado por `bff-mobile`. Es solo
 * lectura: no hay cola de comandos ni WorkManager.
 */
class TrainingDashboardRepository(
    private val dao: TrainingDashboardCacheDao,
    private val codec: TrainingDashboardCodec,
    private val api: TrainingDashboardApi?,
    private val sessionManager: SessionManager,
    private val json: Json,
    private val clock: Clock = Clock.systemUTC(),
) : DashboardGateway, DashboardCacheStore {

    override fun observe(owner: String, range: DashboardRange): Flow<DashboardSnapshot?> =
        dao.observe(owner, range.cacheKey).map { entity -> entity?.let(codec::decode) }.distinctUntilChanged()

    override suspend fun refresh(owner: String, period: DashboardPeriod): DashboardRefresh {
        val api = api ?: return DashboardRefresh.Unavailable(null)
        val outcome = sessionManager.authorized { authorization ->
            apiCall(json) { api.dashboard(authorization, period.fromParam, period.toParam) }
        }
        val result = when (outcome) {
            is AuthorizedResult.Completed -> outcome.result
            AuthorizedResult.SignedOut -> return DashboardRefresh.SignedOut
            AuthorizedResult.RenewalUnavailable -> return DashboardRefresh.Unavailable(null)
        }
        return when (result) {
            is ApiResult.Success -> store(owner, period, result.value)
            is ApiResult.HttpFailure -> when {
                result.status == 403 && result.code == CODE_CAPABILITY_REQUIRED -> DashboardRefresh.CapabilityRequired
                result.status == 403 -> DashboardRefresh.PermissionRequired
                result.status == 422 -> DashboardRefresh.InvalidPeriod(result.issues)
                else -> DashboardRefresh.Unavailable(result.correlationId)
            }
            ApiResult.NoConnection -> DashboardRefresh.NoConnection
            ApiResult.Timeout, ApiResult.NotConfigured -> DashboardRefresh.Unavailable(null)
            ApiResult.InvalidResponse -> DashboardRefresh.InvalidResponse
        }
    }

    private suspend fun store(owner: String, period: DashboardPeriod, body: TrainingDashboardResponseDto): DashboardRefresh {
        val snapshot = try {
            body.toSnapshot(period.range, clock.instant())
        } catch (_: InvalidDashboardResponse) {
            return DashboardRefresh.InvalidResponse
        }
        // El BFF responde el periodo consultado; otro distinto no se guarda como si lo fuera.
        if (!period.sameInstants(snapshot.from, snapshot.to)) return DashboardRefresh.InvalidResponse
        return try {
            dao.replace(TrainingDashboardCacheEntity(owner, period.range.cacheKey, codec.encode(snapshot)))
            DashboardRefresh.Updated
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            DashboardRefresh.StorageFailed
        }
    }

    override suspend fun clear(owner: String) = dao.delete(owner)

    override suspend fun clearAllExcept(owner: String) = dao.deleteAllExcept(owner)

    private companion object {
        const val CODE_CAPABILITY_REQUIRED = "capability_required"
    }
}

/**
 * Elimina la caché del tablero cuando termina la sesión de un deportista (cierre, vencimiento o
 * cambio de cuenta) y, al autenticarse uno, la de cualquier otro deportista que hubiera quedado en
 * el dispositivo (ADR-026 §9).
 */
class TrainingDashboardCacheJanitor(
    private val currentUser: StateFlow<SessionUser?>,
    private val store: DashboardCacheStore,
) {
    fun start(scope: CoroutineScope) = scope.launch {
        var previous: String? = null
        currentUser
            .map { user -> user?.userId?.let(TrainingOwner::keyOf) }
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
