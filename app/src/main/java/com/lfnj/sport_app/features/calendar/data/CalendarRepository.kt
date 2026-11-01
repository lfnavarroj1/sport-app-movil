package com.lfnj.sport_app.features.calendar.data

import com.lfnj.sport_app.core.network.ApiResult
import com.lfnj.sport_app.core.network.apiCall
import com.lfnj.sport_app.core.session.AuthorizedResult
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.calendar.data.local.CalendarCacheCodec
import com.lfnj.sport_app.features.calendar.data.local.CalendarCacheDao
import com.lfnj.sport_app.features.calendar.data.local.CalendarCacheEntity
import com.lfnj.sport_app.features.calendar.model.CalendarOwner
import com.lfnj.sport_app.features.calendar.model.CalendarPeriod
import com.lfnj.sport_app.features.calendar.model.CalendarSnapshot
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

/** Resultado de consultar el periodo al BFF. Ninguna variante contiene datos del calendario. */
sealed interface CalendarRefresh {
    /** El BFF confirmó el periodo y la caché local quedó reemplazada. */
    data object Updated : CalendarRefresh
    data object NoConnection : CalendarRefresh
    /** `503`/`504`, tiempo agotado o renovación de sesión no disponible: reintentar luego. */
    data class Unavailable(val correlationId: String?) : CalendarRefresh
    /** `403 capability_required`: el plan vigente no incluye `calendar.sport.view` (CA5). */
    data object CapabilityRequired : CalendarRefresh
    /** `403 permission_required`: la cuenta no es de deportista. */
    data object PermissionRequired : CalendarRefresh
    /** `422 validation_error` (CA6): no debería ocurrir con el periodo mensual del cliente. */
    data class InvalidPeriod(val issues: List<String>) : CalendarRefresh
    data object InvalidResponse : CalendarRefresh
    /** La sesión ya no es válida: volver al acceso. */
    data object SignedOut : CalendarRefresh
    /** El periodo llegó, pero no pudo guardarse en el dispositivo. */
    data object StorageFailed : CalendarRefresh
}

/** Operaciones de la pantalla del calendario; las implementa [CalendarRepository]. */
interface CalendarGateway {
    /** Último periodo confirmado del deportista, o `null` si no existe o es ilegible. */
    fun observe(owner: String): Flow<CalendarSnapshot?>
    suspend fun refresh(owner: String, period: CalendarPeriod): CalendarRefresh
}

/** Limpieza de la caché local (cierre de sesión o cambio de deportista). */
interface CalendarCacheStore {
    suspend fun clear(owner: String)
    suspend fun clearAllExcept(owner: String)
}

/**
 * Calendario deportivo offline first (ADR-031, política offline): la pantalla observa la caché
 * cifrada del deportista y [refresh] la reemplaza con cada periodo confirmado por `bff-mobile`.
 * Es solo lectura: no hay cola de comandos ni WorkManager.
 */
class CalendarRepository(
    private val dao: CalendarCacheDao,
    private val codec: CalendarCacheCodec,
    private val api: CalendarApi?,
    private val sessionManager: SessionManager,
    private val json: Json,
    private val clock: Clock = Clock.systemUTC(),
) : CalendarGateway, CalendarCacheStore {

    override fun observe(owner: String): Flow<CalendarSnapshot?> =
        dao.observe(owner).map { entity -> entity?.let(codec::decode) }.distinctUntilChanged()

    override suspend fun refresh(owner: String, period: CalendarPeriod): CalendarRefresh {
        val api = api ?: return CalendarRefresh.Unavailable(null)
        val outcome = sessionManager.authorized { authorization ->
            apiCall(json) { api.calendar(authorization, period.fromParam, period.toParam) }
        }
        val result = when (outcome) {
            is AuthorizedResult.Completed -> outcome.result
            AuthorizedResult.SignedOut -> return CalendarRefresh.SignedOut
            AuthorizedResult.RenewalUnavailable -> return CalendarRefresh.Unavailable(null)
        }
        return when (result) {
            is ApiResult.Success -> store(owner, period, result.value)
            is ApiResult.HttpFailure -> when {
                result.status == 403 && result.code == CODE_CAPABILITY_REQUIRED -> CalendarRefresh.CapabilityRequired
                result.status == 403 -> CalendarRefresh.PermissionRequired
                result.status == 422 -> CalendarRefresh.InvalidPeriod(result.issues)
                else -> CalendarRefresh.Unavailable(result.correlationId)
            }
            ApiResult.NoConnection -> CalendarRefresh.NoConnection
            ApiResult.Timeout, ApiResult.NotConfigured -> CalendarRefresh.Unavailable(null)
            ApiResult.InvalidResponse -> CalendarRefresh.InvalidResponse
        }
    }

    private suspend fun store(owner: String, period: CalendarPeriod, body: CalendarResponseDto): CalendarRefresh {
        val snapshot = try {
            body.toSnapshot(period.month, clock.instant())
        } catch (_: InvalidCalendarResponse) {
            return CalendarRefresh.InvalidResponse
        }
        return try {
            dao.replace(CalendarCacheEntity(owner, codec.encode(snapshot)))
            CalendarRefresh.Updated
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            CalendarRefresh.StorageFailed
        }
    }

    override suspend fun clear(owner: String) = dao.delete(owner)

    override suspend fun clearAllExcept(owner: String) = dao.deleteAllExcept(owner)

    private companion object {
        const val CODE_CAPABILITY_REQUIRED = "capability_required"
    }
}

/**
 * Elimina la caché del calendario cuando termina la sesión de un deportista (cierre, vencimiento o
 * cambio de cuenta) y, al autenticarse uno, la de cualquier otro deportista que hubiera quedado en
 * el dispositivo (ADR-031, política offline §5).
 */
class CalendarCacheJanitor(
    private val currentUser: StateFlow<SessionUser?>,
    private val store: CalendarCacheStore,
) {
    fun start(scope: CoroutineScope) = scope.launch {
        var previous: String? = null
        currentUser
            .map { user -> user?.userId?.let(CalendarOwner::keyOf) }
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
