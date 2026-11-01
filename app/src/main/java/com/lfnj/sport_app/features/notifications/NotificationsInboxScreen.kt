package com.lfnj.sport_app.features.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lfnj.sport_app.R
import com.lfnj.sport_app.features.events.EventFormat
import com.lfnj.sport_app.features.notifications.model.InboxNotification
import com.lfnj.sport_app.features.notifications.model.NotificationType
import com.lfnj.sport_app.features.notifications.model.SystemNotificationsStatus

/**
 * Bandeja de notificaciones de eventos (HU039): más recientes primero, leídas y no leídas, con la
 * hora de la última sincronización. Sin red muestra la última bandeja confirmada en el dispositivo.
 * Si las notificaciones del sistema están deshabilitadas lo declara y ofrece activarlas.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsInboxScreen(
    state: NotificationsInboxUiState,
    onOpen: (InboxNotification) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onDismissMarkReadProblem: () -> Unit,
    onEnableSystemNotifications: () -> Unit,
    onOpenPreferences: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notifications_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("notifications_back")) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    TextButton(onClick = onOpenPreferences, modifier = Modifier.testTag("notifications_open_preferences")) {
                        Text(stringResource(R.string.notifications_preferences_action))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .testTag("notifications_screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SystemNotificationsNotice(state.systemNotifications, onEnableSystemNotifications)
            state.markReadProblem?.let { problem ->
                Notice(markReadMessage(problem), "notifications_mark_read_problem", MaterialTheme.colorScheme.error)
                TextButton(onClick = onDismissMarkReadProblem, modifier = Modifier.testTag("notifications_mark_read_dismiss")) {
                    Text(stringResource(R.string.action_accept))
                }
            }
            val snapshot = state.snapshot
            when {
                state.loading -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.testTag("notifications_loading"))
                    Text(stringResource(R.string.notifications_loading))
                }
                snapshot == null -> {
                    if (state.neverSynced) {
                        Notice(R.string.notifications_never_synced, "notifications_never_synced")
                    } else {
                        Notice(problemMessage(state.problem), "notifications_error", MaterialTheme.colorScheme.error)
                    }
                    if (state.problem != InboxProblem.PERMISSION_REQUIRED) {
                        Button(onClick = onRetry, enabled = !state.refreshing, modifier = Modifier.testTag("notifications_retry")) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
                else -> {
                    if (state.refreshing) {
                        val refreshing = stringResource(R.string.notifications_refreshing)
                        LinearProgressIndicator(
                            Modifier
                                .fillMaxWidth()
                                .testTag("notifications_refreshing")
                                .semantics { contentDescription = refreshing },
                        )
                    }
                    Text(
                        stringResource(
                            R.string.notifications_synced_at,
                            EventFormat.dateTime(snapshot.syncedAt, state.zone, EventFormat.currentLocale()),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("notifications_synced_at"),
                    )
                    when (state.problem) {
                        null -> Unit
                        InboxProblem.NO_CONNECTION -> Notice(R.string.notifications_offline_with_cache, "notifications_problem")
                        else -> {
                            Notice(R.string.notifications_unavailable_with_cache, "notifications_problem")
                            OutlinedButton(onClick = onRetry, enabled = !state.refreshing, modifier = Modifier.testTag("notifications_retry")) {
                                Text(stringResource(R.string.action_retry))
                            }
                        }
                    }
                    if (state.empty) {
                        Text(stringResource(R.string.notifications_empty), Modifier.testTag("notifications_empty"))
                    } else {
                        Text(
                            pluralStringResource(R.plurals.notifications_unread_count, state.unreadCount, state.unreadCount),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.testTag("notifications_unread_count"),
                        )
                        state.items.forEach { NotificationCard(it, state, onOpen) }
                    }
                    if (snapshot.nextCursor != null) {
                        OutlinedButton(onClick = onLoadMore, enabled = state.canLoadMore, modifier = Modifier.testTag("notifications_load_more")) {
                            Text(stringResource(if (state.loadingMore) R.string.notifications_loading_more else R.string.notifications_load_more))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SystemNotificationsNotice(status: SystemNotificationsStatus, onEnable: () -> Unit) {
    if (status == SystemNotificationsStatus.ENABLED) return
    Card(Modifier.fillMaxWidth().testTag("notifications_system_disabled")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.notifications_system_disabled),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            OutlinedButton(onClick = onEnable, modifier = Modifier.testTag("notifications_enable_system")) {
                Text(
                    stringResource(
                        if (status == SystemNotificationsStatus.PERMISSION_REQUIRED) R.string.notifications_allow
                        else R.string.notifications_open_settings
                    )
                )
            }
        }
    }
}

private fun problemMessage(problem: InboxProblem?): Int = when (problem) {
    InboxProblem.PERMISSION_REQUIRED -> R.string.notifications_error_permission_required
    InboxProblem.INVALID_RESPONSE -> R.string.event_error_invalid_response
    InboxProblem.STORAGE -> R.string.notifications_error_storage
    InboxProblem.NO_CONNECTION, InboxProblem.UNAVAILABLE, null -> R.string.notifications_error_unavailable
}

private fun markReadMessage(problem: MarkReadProblem): Int = when (problem) {
    MarkReadProblem.NO_CONNECTION -> R.string.notifications_mark_read_offline
    MarkReadProblem.NOT_FOUND -> R.string.notifications_mark_read_not_found
    MarkReadProblem.UNAVAILABLE -> R.string.notifications_mark_read_unavailable
}

@Composable
private fun NotificationCard(item: InboxNotification, state: NotificationsInboxUiState, onOpen: (InboxNotification) -> Unit) {
    val readState = stringResource(if (item.unread) R.string.notifications_state_unread else R.string.notifications_state_read)
    val kind = stringResource(
        if (item.type == NotificationType.EVENT_REMINDER_24H) R.string.notifications_type_reminder else R.string.notifications_type_match
    )
    Card(
        colors = if (item.unread) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors(),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("notification_${item.notificationId}")
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.notifications_open_event)) { onOpen(item) }
            .semantics(mergeDescendants = true) { stateDescription = readState },
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .size(10.dp)
                    .background(if (item.unread) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                    .testTag(if (item.unread) "notification_unread_${item.notificationId}" else "notification_read_${item.notificationId}"),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(kind, style = MaterialTheme.typography.labelMedium)
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (item.unread) FontWeight.Bold else FontWeight.Normal,
                )
                Text(item.body)
                Text(
                    EventFormat.dateTime(item.createdAt, state.zone, EventFormat.currentLocale()),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (item.notificationId in state.marking) {
                    Text(stringResource(R.string.notifications_marking), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun Notice(message: Int, tag: String, color: Color = Color.Unspecified) {
    Text(
        stringResource(message),
        color = color,
        modifier = Modifier
            .testTag(tag)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}
