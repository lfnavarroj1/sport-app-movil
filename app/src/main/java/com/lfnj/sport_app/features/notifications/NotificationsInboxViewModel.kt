package com.lfnj.sport_app.features.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lfnj.sport_app.features.notifications.data.InboxGateway
import com.lfnj.sport_app.features.notifications.data.InboxRefresh
import com.lfnj.sport_app.features.notifications.data.MarkReadResult
import com.lfnj.sport_app.features.notifications.model.InboxNotification
import com.lfnj.sport_app.features.notifications.model.InboxSnapshot
import com.lfnj.sport_app.features.notifications.model.SystemNotificationsStatus
import com.lfnj.sport_app.features.notifications.system.SystemNotificationsProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.ZoneId

/** Motivo por el que la bandeja no pudo confirmarse; nunca describe datos ni detalles técnicos. */
enum class InboxProblem { NO_CONNECTION, UNAVAILABLE, PERMISSION_REQUIRED, INVALID_RESPONSE, STORAGE }

/** Motivo por el que no se pudo marcar una notificación como leída. */
enum class MarkReadProblem { NO_CONNECTION, NOT_FOUND, UNAVAILABLE }

data class NotificationsInboxUiState(
    val zone: ZoneId,
    /** Consulta de la primera página en curso. */
    val refreshing: Boolean = false,
    /** Consulta de la página siguiente en curso. */
    val loadingMore: Boolean = false,
    /** Última bandeja confirmada (desde la caché), o `null`. */
    val snapshot: InboxSnapshot? = null,
    /** [snapshot] en el orden del contrato: más recientes primero. */
    val items: List<InboxNotification> = emptyList(),
    val problem: InboxProblem? = null,
    val markReadProblem: MarkReadProblem? = null,
    /** `notification_id` cuya lectura se está enviando. */
    val marking: Set<String> = emptySet(),
    val systemNotifications: SystemNotificationsStatus = SystemNotificationsStatus.ENABLED,
    /** La sesión de la aplicación ya no es válida: volver al acceso. */
    val sessionExpired: Boolean = false,
) {
    /** Sin datos que mostrar y esperando la caché o el BFF. */
    val loading: Boolean get() = snapshot == null && problem == null

    /** Confirmada por el BFF y sin notificaciones: estado vacío, no error. */
    val empty: Boolean get() = snapshot != null && snapshot.items.isEmpty()

    val unreadCount: Int get() = snapshot?.unreadCount ?: 0

    val canLoadMore: Boolean get() = snapshot?.nextCursor != null && !loadingMore && !refreshing

    /** Sin red y sin ninguna bandeja confirmada en el dispositivo. */
    val neverSynced: Boolean get() = snapshot == null && problem == InboxProblem.NO_CONNECTION
}

/**
 * Bandeja de HU039. Observa la caché cifrada del deportista ([owner]) y sincroniza la primera
 * página al abrir; sin red conserva la última bandeja confirmada con su `last_synced_at`. Tocar una
 * notificación abre el detalle del evento (HU041) y envía la lectura; un fallo de la lectura no
 * impide abrir el evento y la notificación sigue como no leída.
 */
class NotificationsInboxViewModel(
    private val owner: String,
    private val gateway: InboxGateway,
    private val probe: SystemNotificationsProbe,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(NotificationsInboxUiState(zone = clock.zone, systemNotifications = probeSafely()))
    val uiState: StateFlow<NotificationsInboxUiState> = _uiState.asStateFlow()
    private var cached: InboxSnapshot? = null
    private var job: Job? = null

    init {
        viewModelScope.launch {
            gateway.observe(owner)
                .catch { emit(null) }
                .collect { snapshot ->
                    cached = snapshot
                    _uiState.update { it.withCache() }
                }
        }
        refresh()
    }

    fun retry() = refresh()

    fun loadMore() {
        if (!_uiState.value.canLoadMore || job?.isActive == true) return
        _uiState.update { it.copy(loadingMore = true, problem = null) }
        job = viewModelScope.launch { finish(attempt { gateway.loadMore(owner) }) }
    }

    /** Envía la lectura de [item] si sigue sin leer; la navegación al evento la hace la pantalla. */
    fun open(item: InboxNotification) {
        if (!item.unread || item.notificationId in _uiState.value.marking) return
        _uiState.update { it.copy(marking = it.marking + item.notificationId, markReadProblem = null) }
        viewModelScope.launch {
            val result = try {
                gateway.markRead(owner, item.notificationId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                MarkReadResult.Unavailable(null)
            }
            _uiState.update {
                it.copy(
                    marking = it.marking - item.notificationId,
                    markReadProblem = result.toProblem(),
                    sessionExpired = it.sessionExpired || result == MarkReadResult.SignedOut,
                )
            }
        }
    }

    fun dismissMarkReadProblem() = _uiState.update { it.copy(markReadProblem = null) }

    /** Se invoca al volver a la pantalla: el usuario pudo cambiar el permiso o los ajustes. */
    fun refreshSystemStatus() = _uiState.update { it.copy(systemNotifications = probeSafely()) }

    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    private fun probeSafely(): SystemNotificationsStatus = try {
        probe.status()
    } catch (_: RuntimeException) {
        SystemNotificationsStatus.DISABLED
    }

    private fun refresh() {
        if (job?.isActive == true) return
        _uiState.update { it.copy(refreshing = true, problem = null) }
        job = viewModelScope.launch { finish(attempt { gateway.refresh(owner) }) }
    }

    private suspend fun attempt(block: suspend () -> InboxRefresh): InboxRefresh = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        InboxRefresh.StorageFailed
    }

    private fun finish(result: InboxRefresh) = _uiState.update { current ->
        current.copy(
            refreshing = false,
            loadingMore = false,
            problem = result.toProblem(),
            sessionExpired = result == InboxRefresh.SignedOut,
        ).withCache()
    }

    /** Sin permiso, el BFF ya rechazó el acceso: no se muestran datos guardados. */
    private fun NotificationsInboxUiState.withCache(): NotificationsInboxUiState {
        val visible = cached?.takeIf { problem != InboxProblem.PERMISSION_REQUIRED }
        return copy(snapshot = visible, items = visible?.ordered.orEmpty())
    }

    private fun InboxRefresh.toProblem(): InboxProblem? = when (this) {
        InboxRefresh.Updated, InboxRefresh.SignedOut -> null
        InboxRefresh.NoConnection -> InboxProblem.NO_CONNECTION
        is InboxRefresh.Unavailable, is InboxRefresh.InvalidRequest -> InboxProblem.UNAVAILABLE
        InboxRefresh.PermissionRequired -> InboxProblem.PERMISSION_REQUIRED
        InboxRefresh.InvalidResponse -> InboxProblem.INVALID_RESPONSE
        InboxRefresh.StorageFailed -> InboxProblem.STORAGE
    }

    private fun MarkReadResult.toProblem(): MarkReadProblem? = when (this) {
        is MarkReadResult.Marked, MarkReadResult.SignedOut -> null
        MarkReadResult.NoConnection -> MarkReadProblem.NO_CONNECTION
        MarkReadResult.NotFound -> MarkReadProblem.NOT_FOUND
        is MarkReadResult.Unavailable, MarkReadResult.PermissionRequired, MarkReadResult.InvalidResponse -> MarkReadProblem.UNAVAILABLE
    }
}
