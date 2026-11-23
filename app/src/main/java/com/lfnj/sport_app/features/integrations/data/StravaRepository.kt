package com.lfnj.sport_app.features.integrations.data

import com.lfnj.sport_app.core.network.ApiResult
import com.lfnj.sport_app.core.network.apiCall
import com.lfnj.sport_app.core.network.apiCallAllowingNoContent
import com.lfnj.sport_app.core.session.AuthorizedResult
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.integrations.data.local.StravaCacheDao
import com.lfnj.sport_app.features.integrations.data.local.StravaCacheEntity
import com.lfnj.sport_app.features.integrations.data.local.StravaCodec
import com.lfnj.sport_app.features.integrations.model.StravaIntegration
import com.lfnj.sport_app.features.integrations.model.StravaSyncStatus
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
import java.net.URI
import java.net.URISyntaxException
import java.time.Clock
import java.time.DateTimeException
import java.time.OffsetDateTime

/** Motivo por el que una operación no se confirmó. Nunca describe datos ni detalles técnicos. */
enum class StravaFailure {
    /** Sin red: no se encola nada (ADR-042 §13). */
    NO_CONNECTION,

    /** `503`/`504`, tiempo agotado, BFF no configurado o renovación de sesión no disponible: reintentar luego. */
    UNAVAILABLE,

    /** `403 capability_required`: el plan vigente no incluye `integrations.strava.connect`. */
    CAPABILITY_REQUIRED,

    /** `403 permission_required`: la cuenta no es de deportista. */
    PERMISSION_REQUIRED,

    /** La respuesta no corresponde al contrato 0.22.0. */
    INVALID_RESPONSE,

    /** La sesión de la aplicación ya no es válida: volver al acceso. */
    SIGNED_OUT,

    /** El estado llegó, pero no pudo guardarse en el dispositivo. */
    STORAGE,
}

/** Resultado de una operación con el BFF. */
sealed interface StravaOutcome<out T> {
    data class Done<T>(val value: T) : StravaOutcome<T>
    data class Failed(val failure: StravaFailure) : StravaOutcome<Nothing>
}

/** Respuesta de `POST /v1/me/integrations/strava/syncs` aceptada por el contrato (CA4). */
sealed interface StravaSyncAcceptance {
    /** `202`: corrida aceptada (o la original de la misma clave). */
    data class Accepted(val status: StravaSyncStatus) : StravaSyncAcceptance

    /** `409 sync_in_progress`: ya hay una corrida `QUEUED`/`RUNNING`. */
    data object InProgress : StravaSyncAcceptance

    /** `409 integration_not_linked`: no hay vinculación `ACTIVE`. */
    data object NotLinked : StravaSyncAcceptance
}

/** Operaciones de la pantalla de Strava; las implementa [StravaRepository]. */
interface StravaGateway {
    /** Último estado confirmado del deportista, o `null` si no existe o es ilegible. */
    fun observe(owner: String): Flow<StravaIntegration?>

    /** Consulta el estado al BFF y, si es válido, reemplaza la caché cifrada. */
    suspend fun refresh(owner: String): StravaOutcome<StravaIntegration>

    /** Pide la URL del consentimiento oficial; solo se acepta una URL `https`. Nunca se guarda. */
    suspend fun startAuthorization(): StravaOutcome<String>

    /** Solicita una sincronización manual con [idempotencyKey] (la misma en cada reintento del mismo intento). */
    suspend fun requestSync(idempotencyKey: String): StravaOutcome<StravaSyncAcceptance>

    /** Desvincula la cuenta (`204`, idempotente). */
    suspend fun unlink(): StravaOutcome<Unit>
}

/** Limpieza de la caché local (cierre de sesión o cambio de deportista). */
interface StravaCacheStore {
    suspend fun clear(owner: String)
    suspend fun clearAllExcept(owner: String)
}

/**
 * Integración con Strava (HU031, ADR-042 §13). La pantalla observa el último estado confirmado, cifrado
 * por deportista; los comandos (vincular, sincronizar, desvincular) requieren conectividad y no se
 * encolan. Nunca recibe ni guarda tokens de Strava: los custodia Terceros.
 */
class StravaRepository(
    private val dao: StravaCacheDao,
    private val codec: StravaCodec,
    private val api: StravaApi?,
    private val sessionManager: SessionManager,
    private val json: Json,
    private val clock: Clock = Clock.systemUTC(),
) : StravaGateway, StravaCacheStore {

    override fun observe(owner: String): Flow<StravaIntegration?> =
        dao.observe(owner).map { entity -> entity?.let(codec::decode) }.distinctUntilChanged()

    override suspend fun refresh(owner: String): StravaOutcome<StravaIntegration> {
        val api = api ?: return StravaOutcome.Failed(StravaFailure.UNAVAILABLE)
        return when (val result = call { apiCall(json) { api.integration(it) } }) {
            is StravaOutcome.Failed -> result
            is StravaOutcome.Done -> store(owner, result.value)
        }
    }

    private suspend fun store(owner: String, body: StravaIntegrationResponseDto): StravaOutcome<StravaIntegration> {
        val integration = try {
            body.toModel(clock.instant())
        } catch (_: InvalidStravaResponse) {
            return StravaOutcome.Failed(StravaFailure.INVALID_RESPONSE)
        }
        return try {
            dao.replace(StravaCacheEntity(owner, codec.encode(integration)))
            StravaOutcome.Done(integration)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            StravaOutcome.Failed(StravaFailure.STORAGE)
        }
    }

    override suspend fun startAuthorization(): StravaOutcome<String> {
        val api = api ?: return StravaOutcome.Failed(StravaFailure.UNAVAILABLE)
        return when (val result = call { apiCall(json) { api.startAuthorization(it) } }) {
            is StravaOutcome.Failed -> result
            is StravaOutcome.Done -> result.value.takeIf { it.isValid() }
                ?.let { StravaOutcome.Done(it.authorizationUrl) }
                ?: StravaOutcome.Failed(StravaFailure.INVALID_RESPONSE)
        }
    }

    override suspend fun requestSync(idempotencyKey: String): StravaOutcome<StravaSyncAcceptance> {
        val api = api ?: return StravaOutcome.Failed(StravaFailure.UNAVAILABLE)
        return when (val result = call(conflict = ::syncConflict) { apiCall(json) { api.requestSync(it, idempotencyKey) } }) {
            is StravaOutcome.Failed -> result
            is StravaOutcome.Done -> when (val value = result.value) {
                is SyncCall.Conflict -> StravaOutcome.Done(value.acceptance)
                is SyncCall.Ok -> StravaSyncStatus.fromWire(value.body.status)
                    ?.takeIf { value.body.syncRunId.isNotBlank() }
                    ?.let { StravaOutcome.Done(StravaSyncAcceptance.Accepted(it)) }
                    ?: StravaOutcome.Failed(StravaFailure.INVALID_RESPONSE)
            }
        }
    }

    override suspend fun unlink(): StravaOutcome<Unit> {
        val api = api ?: return StravaOutcome.Failed(StravaFailure.UNAVAILABLE)
        return when (val result = call { apiCallAllowingNoContent(json) { api.unlink(it) } }) {
            is StravaOutcome.Failed -> result
            is StravaOutcome.Done -> StravaOutcome.Done(Unit)
        }
    }

    override suspend fun clear(owner: String) = dao.delete(owner)

    override suspend fun clearAllExcept(owner: String) = dao.deleteAllExcept(owner)

    /** Respuesta de la sincronización: `202` o uno de los `409` documentados. */
    private sealed interface SyncCall {
        data class Ok(val body: StravaSyncResponseDto) : SyncCall
        data class Conflict(val acceptance: StravaSyncAcceptance) : SyncCall
    }

    private fun syncConflict(failure: ApiResult.HttpFailure): StravaSyncAcceptance? = when {
        failure.status != 409 -> null
        failure.code == CODE_SYNC_IN_PROGRESS -> StravaSyncAcceptance.InProgress
        failure.code == CODE_NOT_LINKED -> StravaSyncAcceptance.NotLinked
        else -> null
    }

    @JvmName("callSync")
    private suspend fun call(
        conflict: (ApiResult.HttpFailure) -> StravaSyncAcceptance?,
        block: suspend (String) -> ApiResult<StravaSyncResponseDto>,
    ): StravaOutcome<SyncCall> = when (val outcome = sessionManager.authorized(block)) {
        is AuthorizedResult.Completed -> when (val result = outcome.result) {
            is ApiResult.Success -> StravaOutcome.Done(SyncCall.Ok(result.value))
            is ApiResult.HttpFailure -> conflict(result)?.let { StravaOutcome.Done(SyncCall.Conflict(it)) }
                ?: StravaOutcome.Failed(result.toFailure())
            is ApiResult.Failure -> StravaOutcome.Failed(result.toFailure())
        }
        AuthorizedResult.SignedOut -> StravaOutcome.Failed(StravaFailure.SIGNED_OUT)
        AuthorizedResult.RenewalUnavailable -> StravaOutcome.Failed(StravaFailure.UNAVAILABLE)
    }

    private suspend fun <T> call(block: suspend (String) -> ApiResult<T>): StravaOutcome<T> =
        when (val outcome = sessionManager.authorized(block)) {
            is AuthorizedResult.Completed -> when (val result = outcome.result) {
                is ApiResult.Success -> StravaOutcome.Done(result.value)
                is ApiResult.Failure -> StravaOutcome.Failed(result.toFailure())
            }
            AuthorizedResult.SignedOut -> StravaOutcome.Failed(StravaFailure.SIGNED_OUT)
            AuthorizedResult.RenewalUnavailable -> StravaOutcome.Failed(StravaFailure.UNAVAILABLE)
        }

    private fun ApiResult.Failure.toFailure(): StravaFailure = when (this) {
        is ApiResult.HttpFailure -> when {
            status == 403 && code == CODE_CAPABILITY_REQUIRED -> StravaFailure.CAPABILITY_REQUIRED
            status == 403 -> StravaFailure.PERMISSION_REQUIRED
            status == 422 || status == 409 -> StravaFailure.INVALID_RESPONSE
            else -> StravaFailure.UNAVAILABLE
        }
        ApiResult.NoConnection -> StravaFailure.NO_CONNECTION
        ApiResult.Timeout, ApiResult.NotConfigured -> StravaFailure.UNAVAILABLE
        ApiResult.InvalidResponse -> StravaFailure.INVALID_RESPONSE
    }

    /** Solo una URL `https` absoluta con vencimiento válido: nunca se abre otro esquema (p. ej. `intent:`). */
    private fun StravaAuthorizationResponseDto.isValid(): Boolean {
        val uri = try {
            URI(authorizationUrl)
        } catch (_: URISyntaxException) {
            return false
        }
        try {
            OffsetDateTime.parse(expiresAt)
        } catch (_: DateTimeException) {
            return false
        }
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
    }

    private companion object {
        const val CODE_CAPABILITY_REQUIRED = "capability_required"
        const val CODE_SYNC_IN_PROGRESS = "sync_in_progress"
        const val CODE_NOT_LINKED = "integration_not_linked"
    }
}

/**
 * Elimina la caché de Strava cuando termina la sesión de un deportista (cierre, vencimiento o cambio
 * de cuenta) y, al autenticarse uno, la de cualquier otro deportista que hubiera quedado (ADR-026 §9).
 */
class StravaCacheJanitor(
    private val currentUser: StateFlow<SessionUser?>,
    private val store: StravaCacheStore,
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
