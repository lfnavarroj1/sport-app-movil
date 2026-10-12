package com.lfnj.sport_app.core.session

import com.lfnj.sport_app.core.network.ApiResult
import com.lfnj.sport_app.core.network.apiCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

sealed interface LoginOutcome {
    data class Authenticated(val user: SessionUser) : LoginOutcome
    /** `401 invalid_credentials`: incluye correo inexistente y cuenta deshabilitada (mensaje genérico). */
    data object InvalidCredentials : LoginOutcome
    data class TooManyAttempts(val retryAfterSeconds: Long?) : LoginOutcome
    data object InvalidInput : LoginOutcome
    /** El BFF autenticó, pero el `actor_type` falta o no pertenece al catálogo: no se crea sesión (CA10). */
    data object UnsupportedActor : LoginOutcome
    /**
     * Cuenta válida de `organizer` o `service_provider`: la aplicación móvil es exclusiva para
     * deportistas (CA11). Las credenciales recibidas se descartan sin guardarse.
     */
    data object ActorNotAllowedInChannel : LoginOutcome
    data object NoConnection : LoginOutcome
    data class Unavailable(val correlationId: String?) : LoginOutcome
    data object NotConfigured : LoginOutcome
}

sealed interface RestoreOutcome {
    data class Restored(val user: SessionUser) : RestoreOutcome
    data object NoSession : RestoreOutcome
    data object Expired : RestoreOutcome
    data object UnsupportedActor : RestoreOutcome
    data object NoConnection : RestoreOutcome
    data class Unavailable(val correlationId: String?) : RestoreOutcome
    data object NotConfigured : RestoreOutcome
}

sealed interface AccessOutcome {
    data class Loaded(val access: AccessSnapshot) : AccessOutcome
    data object SessionExpired : AccessOutcome
    /** Actor ausente, desconocido o distinto del firmado: la sesión local se elimina (CA10). */
    data object Unauthorized : AccessOutcome
    data object NoConnection : AccessOutcome
    data class Unavailable(val correlationId: String?) : AccessOutcome
}

/** Resultado del cierre de sesión (HU004). La sesión local siempre queda eliminada. */
sealed interface LogoutOutcome {
    /** Revocación confirmada o idempotente (CA5, CA7). */
    data object LoggedOut : LogoutOutcome
    /** Cierre local realizado, pero la revocación remota no pudo confirmarse (CA6). */
    data object LoggedOutRemoteUnconfirmed : LogoutOutcome
}

/**
 * Sesión del usuario en el dispositivo (HU003 y HU004).
 *
 * - El access token vive solo en memoria; el refresh token se guarda únicamente mediante
 *   [SecureTokenStore] (Android Keystore, CA6).
 * - Las renovaciones se serializan: el BFF rota el refresh token en cada uso y reutilizar uno
 *   rotado revoca toda la familia, por lo que dos renovaciones simultáneas cerrarían la sesión.
 * - Ningún método registra credenciales, tokens ni respuestas.
 */
class SessionManager(
    private val api: SessionApi?,
    private val store: SecureTokenStore,
    private val json: Json,
    private val clock: () -> Long = System::currentTimeMillis,
    private val revoker: SessionRevoker? = null,
) {
    private val mutex = Mutex()
    private var session: AuthSession? = null
    private val _currentUser = MutableStateFlow<SessionUser?>(null)

    /** Usuario autenticado, o `null` sin sesión. Lo usan las guardas de navegación. */
    val currentUser: StateFlow<SessionUser?> = _currentUser.asStateFlow()

    /** `false` mientras el contrato de `bff-mobile` no exponga la operación de cierre (HU004). */
    val canLogout: Boolean get() = revoker != null

    suspend fun login(email: String, password: String): LoginOutcome {
        val api = api ?: return LoginOutcome.NotConfigured
        return mutex.withLock {
            when (val result = apiCall(json) { api.createSession(LoginRequestDto(email, password)) }) {
                is ApiResult.Success -> when (val established = establish(result.value)) {
                    is Established.Ok -> LoginOutcome.Authenticated(established.user)
                    Established.UnknownActor -> LoginOutcome.UnsupportedActor
                    Established.NotAllowedInChannel -> LoginOutcome.ActorNotAllowedInChannel
                    Established.Invalid -> LoginOutcome.Unavailable(null)
                }
                is ApiResult.HttpFailure -> when (result.status) {
                    401 -> LoginOutcome.InvalidCredentials
                    429 -> LoginOutcome.TooManyAttempts(result.retryAfterSeconds)
                    422 -> LoginOutcome.InvalidInput
                    else -> LoginOutcome.Unavailable(result.correlationId)
                }
                ApiResult.NoConnection -> LoginOutcome.NoConnection
                ApiResult.NotConfigured -> LoginOutcome.NotConfigured
                ApiResult.Timeout, ApiResult.InvalidResponse -> LoginOutcome.Unavailable(null)
            }
        }
    }

    /** Recupera la sesión al abrir la aplicación renovándola con el refresh token cifrado. */
    suspend fun restore(): RestoreOutcome {
        val api = api ?: return RestoreOutcome.NotConfigured
        return mutex.withLock {
            session?.let { return@withLock RestoreOutcome.Restored(it.user) }
            val refreshToken = store.read() ?: return@withLock RestoreOutcome.NoSession
            when (val refreshed = refreshLocked(api, refreshToken)) {
                is Refreshed.Ok -> RestoreOutcome.Restored(refreshed.session.user)
                Refreshed.Expired -> RestoreOutcome.Expired
                Refreshed.UnknownActor -> RestoreOutcome.UnsupportedActor
                Refreshed.NoConnection -> RestoreOutcome.NoConnection
                is Refreshed.Unavailable -> RestoreOutcome.Unavailable(refreshed.correlationId)
            }
        }
    }

    /** Identidad, permisos y capacidades vigentes (`GET /v1/me/capabilities`). */
    suspend fun loadAccess(): AccessOutcome {
        val api = api ?: return AccessOutcome.Unavailable(null)
        var current = when (val fresh = freshSession(api, rejectedAccessToken = null)) {
            is Refreshed.Ok -> fresh.session
            else -> return fresh.toAccessFailure()
        }
        var result = apiCall(json) { api.capabilities(current.bearer()) }
        if (result is ApiResult.HttpFailure && result.status == 401) {
            current = when (val fresh = freshSession(api, rejectedAccessToken = current.accessToken)) {
                is Refreshed.Ok -> fresh.session
                else -> return fresh.toAccessFailure()
            }
            result = apiCall(json) { api.capabilities(current.bearer()) }
        }
        return when (result) {
            is ApiResult.Success -> toSnapshot(result.value, current.user)
                ?: clearAndReturn(AccessOutcome.Unauthorized)
            is ApiResult.HttpFailure -> when (result.status) {
                401 -> clearAndReturn(AccessOutcome.SessionExpired)
                403 -> clearAndReturn(AccessOutcome.Unauthorized)
                else -> AccessOutcome.Unavailable(result.correlationId)
            }
            ApiResult.NoConnection -> AccessOutcome.NoConnection
            ApiResult.Timeout, ApiResult.InvalidResponse, ApiResult.NotConfigured -> AccessOutcome.Unavailable(null)
        }
    }

    /**
     * Elimina la sesión local (memoria y almacenamiento seguro). Se usa ante un actor no
     * válido o una sesión vencida; la revocación en el servidor corresponde a HU004.
     */
    suspend fun clearLocalSession() = mutex.withLock { clearLocked() }

    /**
     * Cierra la sesión (HU004). Primero elimina la sesión local —memoria y almacenamiento
     * seguro— para que nunca pueda restaurarse, aunque el proceso termine durante la llamada;
     * luego solicita la revocación con el refresh token retenido solo en esta llamada. Sin
     * confirmación remota no se guarda el token para reintentos (CA6).
     */
    suspend fun logout(): LogoutOutcome = mutex.withLock {
        val refreshToken = session?.refreshToken ?: store.read()
        clearLocked()
        val result = when {
            refreshToken == null -> RevocationResult.ALREADY_CLOSED
            revoker == null -> RevocationResult.NOT_CONFIRMED
            else -> try {
                revoker.revoke(refreshToken)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                RevocationResult.NOT_CONFIRMED
            }
        }
        if (result == RevocationResult.NOT_CONFIRMED) LogoutOutcome.LoggedOutRemoteUnconfirmed else LogoutOutcome.LoggedOut
    }

    // ---------------------------------------------------------------- internos

    private sealed interface Established {
        data class Ok(val user: SessionUser) : Established
        data object UnknownActor : Established
        data object NotAllowedInChannel : Established
        data object Invalid : Established
    }

    private sealed interface Refreshed {
        data class Ok(val session: AuthSession) : Refreshed
        data object Expired : Refreshed
        data object UnknownActor : Refreshed
        data object NoConnection : Refreshed
        data class Unavailable(val correlationId: String?) : Refreshed
    }

    /**
     * Crea la sesión solo para un deportista con tokens válidos. En cualquier otro caso descarta
     * los tokens recibidos sin persistirlos y elimina el estado previo. Debe llamarse con [mutex] tomado.
     */
    private fun establish(response: SessionResponseDto): Established {
        val actor = ActorType.fromWire(response.user.actorType)
        val valid = response.tokenType.equals("Bearer", ignoreCase = true) &&
            response.accessToken.isNotBlank() && response.refreshToken.isNotBlank() && response.expiresIn > 0
        val rejection = when {
            actor == null -> Established.UnknownActor
            !actor.allowedInMobile -> Established.NotAllowedInChannel
            !valid -> Established.Invalid
            else -> null
        }
        if (rejection != null) {
            clearLocked()
            return rejection
        }
        val user = SessionUser(response.user.userId, response.user.fullName, response.user.email, checkNotNull(actor))
        store.write(response.refreshToken)
        session = AuthSession(
            user = user,
            accessToken = response.accessToken,
            accessExpiresAtMillis = clock() + response.expiresIn * 1000,
            refreshToken = response.refreshToken,
        )
        _currentUser.value = user
        return Established.Ok(user)
    }

    /** Debe llamarse con [mutex] tomado. */
    private suspend fun refreshLocked(api: SessionApi, refreshToken: String): Refreshed =
        when (val result = apiCall(json) { api.refreshSession(RefreshSessionRequestDto(refreshToken)) }) {
            is ApiResult.Success -> when (establish(result.value)) {
                is Established.Ok -> Refreshed.Ok(checkNotNull(session))
                Established.UnknownActor, Established.NotAllowedInChannel -> Refreshed.UnknownActor
                Established.Invalid -> Refreshed.Unavailable(null)
            }
            is ApiResult.HttpFailure -> when (result.status) {
                401, 422 -> {
                    clearLocked()
                    Refreshed.Expired
                }
                else -> Refreshed.Unavailable(result.correlationId)
            }
            ApiResult.NoConnection -> Refreshed.NoConnection
            ApiResult.Timeout, ApiResult.InvalidResponse, ApiResult.NotConfigured -> Refreshed.Unavailable(null)
        }

    /**
     * Devuelve una sesión con access token vigente. Renueva si está por vencer o si el BFF
     * rechazó [rejectedAccessToken] y nadie lo renovó ya mientras se esperaba el candado.
     */
    private suspend fun freshSession(api: SessionApi, rejectedAccessToken: String?): Refreshed = mutex.withLock {
        val current = session ?: return@withLock Refreshed.Expired
        val rejected = rejectedAccessToken != null && current.accessToken == rejectedAccessToken
        val expiring = current.accessExpiresAtMillis - clock() <= EXPIRY_MARGIN_MILLIS
        if (rejected || expiring) refreshLocked(api, current.refreshToken) else Refreshed.Ok(current)
    }

    private fun toSnapshot(dto: CapabilitiesResponseDto, user: SessionUser): AccessOutcome.Loaded? {
        val actor = ActorType.fromWire(dto.actorType)
        val status = SubscriptionStatus.fromWire(dto.subscriptionStatus)
        if (actor == null || actor != user.actorType || status == null) return null
        return AccessOutcome.Loaded(
            AccessSnapshot(
                actorType = actor,
                subscriptionStatus = status,
                planCode = dto.planCode,
                permissions = dto.permissions.toSet(),
                capabilities = dto.capabilities.toSet(),
            )
        )
    }

    private suspend fun clearAndReturn(outcome: AccessOutcome): AccessOutcome {
        clearLocalSession()
        return outcome
    }

    private fun Refreshed.toAccessFailure(): AccessOutcome = when (this) {
        is Refreshed.Ok -> error("No es una falla")
        Refreshed.Expired -> AccessOutcome.SessionExpired
        Refreshed.UnknownActor -> AccessOutcome.Unauthorized
        Refreshed.NoConnection -> AccessOutcome.NoConnection
        is Refreshed.Unavailable -> AccessOutcome.Unavailable(correlationId)
    }

    private fun clearLocked() {
        session = null
        store.clear()
        _currentUser.value = null
    }

    private fun AuthSession.bearer(): String = "Bearer $accessToken"

    private companion object {
        /** Renueva antes del vencimiento para no enviar un access token a punto de expirar. */
        const val EXPIRY_MARGIN_MILLIS = 30_000L
    }
}
