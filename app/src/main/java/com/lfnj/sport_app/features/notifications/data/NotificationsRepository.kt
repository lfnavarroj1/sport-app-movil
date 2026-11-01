package com.lfnj.sport_app.features.notifications.data

import com.lfnj.sport_app.core.network.ApiResult
import com.lfnj.sport_app.core.network.apiCall
import com.lfnj.sport_app.core.session.AuthorizedResult
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.notifications.data.local.NotificationsCacheDao
import com.lfnj.sport_app.features.notifications.data.local.NotificationsCacheEntity
import com.lfnj.sport_app.features.notifications.data.local.NotificationsCodec
import com.lfnj.sport_app.features.notifications.model.InboxNotification
import com.lfnj.sport_app.features.notifications.model.InboxSnapshot
import com.lfnj.sport_app.features.notifications.model.NotificationIds
import com.lfnj.sport_app.features.notifications.model.NotificationPreferences
import com.lfnj.sport_app.features.notifications.model.NotificationsOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.time.Clock

/** Resultado de sincronizar la bandeja. Ninguna variante de falla contiene notificaciones. */
sealed interface InboxRefresh {
    /** El BFF confirmó la página y la bandeja local quedó actualizada. */
    data object Updated : InboxRefresh
    data object NoConnection : InboxRefresh
    /** `503`/`504`, tiempo agotado o renovación de sesión no disponible: reintentar luego. */
    data class Unavailable(val correlationId: String?) : InboxRefresh
    /** `403 permission_required`: la cuenta no es de deportista. */
    data object PermissionRequired : InboxRefresh
    /** `422 validation_error` (`limit` o `cursor`). */
    data class InvalidRequest(val issues: List<String>) : InboxRefresh
    data object InvalidResponse : InboxRefresh
    data object SignedOut : InboxRefresh
    /** La bandeja llegó, pero no pudo guardarse en el dispositivo. */
    data object StorageFailed : InboxRefresh
}

/** Resultado de `POST /v1/me/notifications/{id}/read`. */
sealed interface MarkReadResult {
    data class Marked(val notification: InboxNotification) : MarkReadResult
    /** `404 notification_not_found`: inexistente, ajena o aún no disponible en la bandeja. */
    data object NotFound : MarkReadResult
    data object NoConnection : MarkReadResult
    data class Unavailable(val correlationId: String?) : MarkReadResult
    data object PermissionRequired : MarkReadResult
    data object InvalidResponse : MarkReadResult
    data object SignedOut : MarkReadResult
}

/** Resultado de consultar las preferencias. */
sealed interface PreferencesResult {
    data class Loaded(val preferences: NotificationPreferences) : PreferencesResult
    data object NoConnection : PreferencesResult
    data class Unavailable(val correlationId: String?) : PreferencesResult
    data object PermissionRequired : PreferencesResult
    data object InvalidResponse : PreferencesResult
    data object SignedOut : PreferencesResult
}

/** Resultado de reemplazar las preferencias con `expected_version`. */
sealed interface SavePreferencesResult {
    data class Saved(val preferences: NotificationPreferences) : SavePreferencesResult
    /** `409 version_conflict`: otra actualización ganó; se deben releer antes de guardar. */
    data object Conflict : SavePreferencesResult
    /** `422 validation_error` con las causas estables del contrato. */
    data class Invalid(val issues: List<String>) : SavePreferencesResult
    data object NoConnection : SavePreferencesResult
    data class Unavailable(val correlationId: String?) : SavePreferencesResult
    data object PermissionRequired : SavePreferencesResult
    data object InvalidResponse : SavePreferencesResult
    data object SignedOut : SavePreferencesResult
}

/** Publica en el sistema las notificaciones nuevas de la bandeja (canal según la prioridad). */
interface NotificationAnnouncer {
    fun announce(items: List<InboxNotification>)
    /** Retira la notificación del sistema de un elemento ya leído. */
    fun dismiss(notificationId: String)
}

/** Operaciones de la bandeja. */
interface InboxGateway {
    /** Última bandeja confirmada del deportista, o `null` si no existe o es ilegible. */
    fun observe(owner: String): Flow<InboxSnapshot?>
    /** Primera página confirmada; reemplaza la bandeja y anuncia los elementos nuevos. */
    suspend fun refresh(owner: String): InboxRefresh
    /** Página siguiente del último cursor guardado, agregada a la bandeja sin anunciarla. */
    suspend fun loadMore(owner: String): InboxRefresh
    suspend fun markRead(owner: String, notificationId: String): MarkReadResult
}

/** Operaciones de la pantalla de preferencias. */
interface PreferencesGateway {
    suspend fun preferences(): PreferencesResult
    suspend fun savePreferences(desired: NotificationPreferences): SavePreferencesResult
}

/** Limpieza de la bandeja local (cierre de sesión o cambio de deportista). */
interface NotificationsCacheStore {
    suspend fun clear(owner: String)
    suspend fun clearAllExcept(owner: String)
}

/**
 * HU039 (ADR-045 §1 y §11): bandeja offline first desde la caché cifrada del deportista,
 * reemplazada con cada primera página confirmada por `bff-mobile`. La lectura y las preferencias
 * requieren conectividad: no hay cola offline de comandos. Las sincronizaciones del proceso (al
 * abrir y periódica) se serializan, por lo que un elemento se anuncia una sola vez.
 */
class NotificationsRepository(
    private val dao: NotificationsCacheDao,
    private val codec: NotificationsCodec,
    private val api: NotificationsApi?,
    private val sessionManager: SessionManager,
    private val json: Json,
    private val announcer: NotificationAnnouncer,
    private val clock: Clock = Clock.systemUTC(),
) : InboxGateway, PreferencesGateway, NotificationsCacheStore {
    private val mutex = Mutex()

    override fun observe(owner: String): Flow<InboxSnapshot?> =
        dao.observe(owner).map { entity -> entity?.let(codec::decode) }.distinctUntilChanged()

    override suspend fun refresh(owner: String): InboxRefresh = mutex.withLock {
        val (outcome, page) = fetch(cursor = null)
        page ?: return@withLock outcome
        val previous = readCache(owner)
        val (snapshot, fresh) = InboxSnapshot.firstPage(previous, page.items, page.nextCursor, clock.instant())
        val stored = store(owner, snapshot)
        // Solo se anuncia lo que quedó registrado como anunciado: así no se repite en la próxima corrida.
        if (stored == InboxRefresh.Updated && fresh.isNotEmpty()) announceSafely(fresh)
        stored
    }

    override suspend fun loadMore(owner: String): InboxRefresh {
        val retryFromStart = mutex.withLock {
            val current = readCache(owner) ?: return@withLock true
            val cursor = current.nextCursor ?: return InboxRefresh.Updated
            val (outcome, page) = fetch(cursor)
            // Un cursor que el BFF ya no acepta se reemplaza consultando desde el principio.
            if (outcome is InboxRefresh.InvalidRequest) return@withLock true
            page ?: return outcome
            return store(owner, current.append(page.items, page.nextCursor))
        }
        return if (retryFromStart) refresh(owner) else InboxRefresh.Updated
    }

    override suspend fun markRead(owner: String, notificationId: String): MarkReadResult {
        if (!NotificationIds.isValid(notificationId)) return MarkReadResult.NotFound
        val api = api ?: return MarkReadResult.Unavailable(null)
        val result = when (val outcome = sessionManager.authorized { apiCall(json) { api.markRead(it, notificationId) } }) {
            is AuthorizedResult.Completed -> outcome.result
            AuthorizedResult.SignedOut -> return MarkReadResult.SignedOut
            AuthorizedResult.RenewalUnavailable -> return MarkReadResult.Unavailable(null)
        }
        return when (result) {
            is ApiResult.Success -> {
                val confirmed = try {
                    result.value.toNotification()
                } catch (_: InvalidNotificationResponse) {
                    null
                }
                val read = confirmed?.takeIf { it.notificationId == notificationId && !it.unread } ?: return MarkReadResult.InvalidResponse
                mutex.withLock { readCache(owner)?.let { store(owner, it.withRead(read)) } }
                runCatching { announcer.dismiss(notificationId) }
                MarkReadResult.Marked(read)
            }
            is ApiResult.HttpFailure -> when (result.status) {
                403 -> MarkReadResult.PermissionRequired
                404 -> MarkReadResult.NotFound
                else -> MarkReadResult.Unavailable(result.correlationId)
            }
            ApiResult.NoConnection -> MarkReadResult.NoConnection
            ApiResult.Timeout, ApiResult.NotConfigured -> MarkReadResult.Unavailable(null)
            ApiResult.InvalidResponse -> MarkReadResult.InvalidResponse
        }
    }

    override suspend fun preferences(): PreferencesResult {
        val api = api ?: return PreferencesResult.Unavailable(null)
        val result = when (val outcome = sessionManager.authorized { apiCall(json) { api.preferences(it) } }) {
            is AuthorizedResult.Completed -> outcome.result
            AuthorizedResult.SignedOut -> return PreferencesResult.SignedOut
            AuthorizedResult.RenewalUnavailable -> return PreferencesResult.Unavailable(null)
        }
        return when (result) {
            is ApiResult.Success -> PreferencesResult.Loaded(result.value.toPreferences())
            is ApiResult.HttpFailure -> if (result.status == 403) PreferencesResult.PermissionRequired else PreferencesResult.Unavailable(result.correlationId)
            ApiResult.NoConnection -> PreferencesResult.NoConnection
            ApiResult.Timeout, ApiResult.NotConfigured -> PreferencesResult.Unavailable(null)
            ApiResult.InvalidResponse -> PreferencesResult.InvalidResponse
        }
    }

    /** Envía el reemplazo completo con `expected_version = desired.version`; un `409` nunca se sobrescribe. */
    override suspend fun savePreferences(desired: NotificationPreferences): SavePreferencesResult {
        val api = api ?: return SavePreferencesResult.Unavailable(null)
        val body = NotificationPreferencesRequestDto(desired.eventMatches, desired.eventReminders, desired.pushEnabled, desired.version)
        val result = when (val outcome = sessionManager.authorized { apiCall(json) { api.replacePreferences(it, body) } }) {
            is AuthorizedResult.Completed -> outcome.result
            AuthorizedResult.SignedOut -> return SavePreferencesResult.SignedOut
            AuthorizedResult.RenewalUnavailable -> return SavePreferencesResult.Unavailable(null)
        }
        return when (result) {
            is ApiResult.Success -> SavePreferencesResult.Saved(result.value.toPreferences())
            is ApiResult.HttpFailure -> when (result.status) {
                409 -> SavePreferencesResult.Conflict
                403 -> SavePreferencesResult.PermissionRequired
                422 -> SavePreferencesResult.Invalid(result.issues)
                else -> SavePreferencesResult.Unavailable(result.correlationId)
            }
            ApiResult.NoConnection -> SavePreferencesResult.NoConnection
            ApiResult.Timeout, ApiResult.NotConfigured -> SavePreferencesResult.Unavailable(null)
            ApiResult.InvalidResponse -> SavePreferencesResult.InvalidResponse
        }
    }

    private class Page(val items: List<InboxNotification>, val nextCursor: String?)

    private suspend fun fetch(cursor: String?): Pair<InboxRefresh, Page?> {
        val api = api ?: return InboxRefresh.Unavailable(null) to null
        val result = when (val outcome = sessionManager.authorized { apiCall(json) { api.inbox(it, PAGE_SIZE, cursor) } }) {
            is AuthorizedResult.Completed -> outcome.result
            AuthorizedResult.SignedOut -> return InboxRefresh.SignedOut to null
            AuthorizedResult.RenewalUnavailable -> return InboxRefresh.Unavailable(null) to null
        }
        val failure = when (result) {
            is ApiResult.Success -> return try {
                InboxRefresh.Updated to Page(result.value.items.map { it.toNotification() }, result.value.nextCursor)
            } catch (_: InvalidNotificationResponse) {
                InboxRefresh.InvalidResponse to null
            }
            is ApiResult.HttpFailure -> when (result.status) {
                403 -> InboxRefresh.PermissionRequired
                422 -> InboxRefresh.InvalidRequest(result.issues)
                else -> InboxRefresh.Unavailable(result.correlationId)
            }
            ApiResult.NoConnection -> InboxRefresh.NoConnection
            ApiResult.Timeout, ApiResult.NotConfigured -> InboxRefresh.Unavailable(null)
            ApiResult.InvalidResponse -> InboxRefresh.InvalidResponse
        }
        return failure to null
    }

    private suspend fun readCache(owner: String): InboxSnapshot? = try {
        dao.read(owner)?.let(codec::decode)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun store(owner: String, snapshot: InboxSnapshot): InboxRefresh = try {
        dao.replace(NotificationsCacheEntity(owner, codec.encode(snapshot)))
        InboxRefresh.Updated
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        InboxRefresh.StorageFailed
    }

    /** Una falla del sistema de notificaciones no afecta la bandeja (ADR-045 §10). */
    private fun announceSafely(items: List<InboxNotification>) {
        try {
            announcer.announce(items)
        } catch (_: RuntimeException) {
            // La bandeja ya quedó actualizada; el elemento sigue visible en la aplicación.
        }
    }

    override suspend fun clear(owner: String) = dao.delete(owner)

    override suspend fun clearAllExcept(owner: String) = dao.deleteAllExcept(owner)

    companion object {
        /** Tamaño de página de la bandeja: el valor por defecto del contrato (1 a 50). */
        const val PAGE_SIZE = 20
    }
}

/**
 * Elimina la bandeja cuando termina la sesión de un deportista (cierre, vencimiento o cambio de
 * cuenta) y, al autenticarse uno, la de cualquier otro que hubiera quedado en el dispositivo
 * (ADR-026 §9, como HU041).
 */
class NotificationsCacheJanitor(
    private val currentUser: StateFlow<SessionUser?>,
    private val store: NotificationsCacheStore,
) {
    fun start(scope: CoroutineScope) = scope.launch {
        var previous: String? = null
        currentUser
            .map { user -> user?.userId?.let(NotificationsOwner::keyOf) }
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
