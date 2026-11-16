package com.lfnj.sport_app.features.integrations

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lfnj.sport_app.R
import com.lfnj.sport_app.features.integrations.model.StravaIntegration
import com.lfnj.sport_app.features.integrations.model.StravaLastSync
import com.lfnj.sport_app.features.integrations.model.StravaLinkStatus
import com.lfnj.sport_app.features.integrations.model.StravaScopes
import com.lfnj.sport_app.features.integrations.model.StravaSyncError
import com.lfnj.sport_app.features.integrations.model.StravaSyncStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * «Strava» (HU031, `/integraciones/strava`): consentimiento antes de abrir la página oficial de Strava,
 * estado de vinculación y, por separado, el de la última sincronización (vinculado ≠ sincronizado),
 * «Sincronizar ahora» y «Desvincular» con la explicación de sus efectos. Fechas en la zona del dispositivo.
 *
 * [onOpenAuthorization] abre la URL en Custom Tabs y devuelve si pudo hacerlo; la URL nunca se muestra.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StravaScreen(
    state: StravaUiState,
    onRequestLink: () -> Unit,
    onOpenAuthorization: (String) -> Boolean,
    onAuthorizationOpened: (Boolean) -> Unit,
    onSync: () -> Unit,
    onUnlink: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onDismissNotice: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    var consentOpen by rememberSaveable { mutableStateOf(false) }
    var unlinkOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.authorizationUrl) {
        state.authorizationUrl?.let { url -> onAuthorizationOpened(onOpenAuthorization(url)) }
    }
    val locale = LocalConfiguration.current.locales[0]
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.strava_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("strava_back")) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
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
                .testTag("strava_screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.strava_intro))
            if (state.refreshing && state.integration != null) {
                val refreshing = stringResource(R.string.strava_refreshing)
                LinearProgressIndicator(
                    Modifier
                        .fillMaxWidth()
                        .testTag("strava_refreshing")
                        .semantics { contentDescription = refreshing },
                )
            }
            state.notice?.let { NoticeCard(it, onDismissNotice) }
            state.problem?.let { ProblemCard(it, onRetry) }
            val integration = state.integration
            when {
                state.loading -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.testTag("strava_loading"))
                    Text(stringResource(R.string.strava_loading))
                }
                state.neverSynced -> Text(stringResource(R.string.strava_never_synced), Modifier.testTag("strava_never_synced"))
                integration != null -> {
                    Text(
                        stringResource(R.string.strava_synced_at, dateTime(integration.syncedAt, zone, locale)),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("strava_synced_at"),
                    )
                    if (state.stale) {
                        Text(
                            stringResource(R.string.strava_stale),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("strava_stale"),
                        )
                    }
                    LinkCard(state, integration, zone, locale, onLink = { consentOpen = true }, onUnlink = { unlinkOpen = true })
                    if (integration.status == StravaLinkStatus.ACTIVE || integration.lastSync != null) {
                        SyncCard(state, integration, zone, locale, onSync)
                    }
                    TextButton(onClick = onRefresh, enabled = !state.refreshing, modifier = Modifier.testTag("strava_refresh")) {
                        Text(stringResource(R.string.strava_action_refresh))
                    }
                }
            }
        }
    }
    if (consentOpen) {
        AlertDialog(
            onDismissRequest = { consentOpen = false },
            modifier = Modifier.testTag("strava_consent"),
            title = { Text(stringResource(R.string.strava_consent_title)) },
            text = {
                Text(
                    stringResource(R.string.strava_consent_body),
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .testTag("strava_consent_body"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        consentOpen = false
                        onRequestLink()
                    },
                    modifier = Modifier.testTag("strava_consent_accept"),
                ) { Text(stringResource(R.string.strava_consent_accept)) }
            },
            dismissButton = {
                TextButton(onClick = { consentOpen = false }, modifier = Modifier.testTag("strava_consent_cancel")) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    if (unlinkOpen) {
        AlertDialog(
            onDismissRequest = { unlinkOpen = false },
            modifier = Modifier.testTag("strava_unlink_dialog"),
            title = { Text(stringResource(R.string.strava_unlink_title)) },
            text = { Text(stringResource(R.string.strava_unlink_body), Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(
                    onClick = {
                        unlinkOpen = false
                        onUnlink()
                    },
                    modifier = Modifier.testTag("strava_unlink_confirm"),
                ) { Text(stringResource(R.string.strava_unlink_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { unlinkOpen = false }, modifier = Modifier.testTag("strava_unlink_cancel")) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun LinkCard(
    state: StravaUiState,
    integration: StravaIntegration,
    zone: ZoneId,
    locale: Locale,
    onLink: () -> Unit,
    onUnlink: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().testTag("strava_link_card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(R.string.strava_link_section)
            val (label, detail, tag) = when {
                state.inLinking -> Triple(R.string.strava_status_linking, stringResource(R.string.strava_status_linking_detail), "linking")
                integration.status == StravaLinkStatus.NOT_LINKED ->
                    Triple(R.string.strava_status_not_linked, stringResource(R.string.strava_status_not_linked_detail), "not_linked")
                integration.status == StravaLinkStatus.ACTIVE -> Triple(
                    R.string.strava_status_active,
                    integration.linkedAt?.let { stringResource(R.string.strava_status_active_since, date(it, zone, locale)) },
                    "active",
                )
                integration.status == StravaLinkStatus.EXPIRED ->
                    Triple(R.string.strava_status_expired, stringResource(R.string.strava_status_expired_detail), "expired")
                else -> Triple(R.string.strava_status_revoked, stringResource(R.string.strava_status_revoked_detail), "revoked")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.inLinking) CircularProgressIndicator(Modifier.size(20.dp))
                Text(
                    stringResource(label),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .testTag("strava_status_$tag")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            detail?.let { Text(it) }
            if (integration.status == StravaLinkStatus.ACTIVE && integration.scopes.isNotEmpty()) {
                Text(stringResource(R.string.strava_scopes_label), style = MaterialTheme.typography.labelLarge)
                integration.scopes.forEach { scope ->
                    Text("• ${scopeLabel(scope)}", modifier = Modifier.testTag("strava_scope_$scope"))
                }
            }
            if (integration.status != StravaLinkStatus.ACTIVE) {
                Button(onClick = onLink, enabled = state.canLink, modifier = Modifier.testTag("strava_link")) {
                    Text(
                        stringResource(
                            if (integration.status == StravaLinkStatus.NOT_LINKED) R.string.strava_action_link else R.string.strava_action_relink
                        )
                    )
                }
            }
            if (integration.status == StravaLinkStatus.ACTIVE || integration.status == StravaLinkStatus.EXPIRED) {
                OutlinedButton(onClick = onUnlink, enabled = state.canUnlink, modifier = Modifier.testTag("strava_unlink")) {
                    if (state.busy == StravaAction.UNLINK) CircularProgressIndicator(Modifier.size(18.dp).padding(end = 4.dp))
                    Text(stringResource(R.string.strava_action_unlink))
                }
            }
        }
    }
}

@Composable
private fun SyncCard(state: StravaUiState, integration: StravaIntegration, zone: ZoneId, locale: Locale, onSync: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("strava_sync_card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(R.string.strava_sync_section)
            val sync = integration.lastSync
            val syncTag = when {
                sync == null -> "strava_sync_never"
                sync.status.inProgress -> "strava_sync_in_progress"
                sync.status == StravaSyncStatus.SUCCEEDED -> "strava_sync_succeeded"
                else -> "strava_sync_failed"
            }
            // Un solo nodo para TalkBack: el estado de la corrida se anuncia completo al cambiar.
            Column(Modifier.testTag(syncTag).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                when {
                    sync == null -> Text(stringResource(R.string.strava_sync_never))
                    sync.status.inProgress -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Text(
                            stringResource(if (sync.status == StravaSyncStatus.QUEUED) R.string.strava_sync_queued else R.string.strava_sync_running),
                        )
                    }
                    sync.status == StravaSyncStatus.SUCCEEDED -> Column {
                        Text(stringResource(R.string.strava_sync_succeeded, dateTime(sync.finishedAt ?: sync.requestedAt, zone, locale)))
                        ImportedCount(sync)
                    }
                    else -> Column {
                        Text(failureText(sync), color = MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.strava_sync_requested_at, dateTime(sync.requestedAt, zone, locale)), style = MaterialTheme.typography.bodySmall)
                        if (sync.importedCount > 0) ImportedCount(sync)
                    }
                }
            }
            if (integration.status == StravaLinkStatus.ACTIVE) {
                Button(onClick = onSync, enabled = state.canSync, modifier = Modifier.testTag("strava_sync")) {
                    if (state.busy == StravaAction.SYNC) CircularProgressIndicator(Modifier.size(18.dp).padding(end = 4.dp))
                    Text(stringResource(R.string.strava_action_sync))
                }
            }
        }
    }
}

@Composable
private fun ImportedCount(sync: StravaLastSync) {
    val count = sync.importedCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    Text(pluralStringResource(R.plurals.strava_sync_imported, count, count))
}

@Composable
private fun failureText(sync: StravaLastSync): String {
    val error = sync.error
    return when {
        error != null -> stringResource(R.string.strava_sync_failed, stringResource(errorLabel(error)))
        sync.status == StravaSyncStatus.RATE_LIMITED -> stringResource(R.string.strava_sync_rate_limited)
        else -> stringResource(R.string.strava_sync_failed_generic)
    }
}

@StringRes
private fun errorLabel(error: StravaSyncError): Int = when (error) {
    StravaSyncError.RATE_LIMITED -> R.string.strava_error_rate_limited
    StravaSyncError.PROVIDER_UNAVAILABLE -> R.string.strava_error_provider_unavailable
    StravaSyncError.CIRCUIT_OPEN -> R.string.strava_error_circuit_open
    StravaSyncError.TOKEN_EXPIRED -> R.string.strava_error_token_expired
    StravaSyncError.INSUFFICIENT_SCOPE -> R.string.strava_error_insufficient_scope
    StravaSyncError.PROVIDER_REJECTED -> R.string.strava_error_provider_rejected
    StravaSyncError.INVALID_RESPONSE -> R.string.strava_error_invalid_response
    StravaSyncError.INVALID_ACTIVITY -> R.string.strava_error_invalid_activity
    StravaSyncError.INTEGRATION_REVOKED -> R.string.strava_error_integration_revoked
}

@Composable
private fun scopeLabel(scope: String): String = when (scope) {
    StravaScopes.READ -> stringResource(R.string.strava_scope_read)
    StravaScopes.ACTIVITY_READ -> stringResource(R.string.strava_scope_activity_read)
    else -> scope
}

@Composable
private fun SectionTitle(@StringRes label: Int) {
    Text(stringResource(label), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
}

@Composable
private fun NoticeCard(notice: StravaNotice, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(noticeLabel(notice)),
                modifier = Modifier
                    .testTag("strava_notice_${notice.name.lowercase()}")
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("strava_notice_dismiss")) {
                Text(stringResource(R.string.action_accept))
            }
        }
    }
}

@StringRes
private fun noticeLabel(notice: StravaNotice): Int = when (notice) {
    StravaNotice.LINKED -> R.string.strava_notice_linked
    StravaNotice.LINK_DENIED -> R.string.strava_notice_denied
    StravaNotice.LINK_INVALID_STATE -> R.string.strava_notice_invalid_state
    StravaNotice.LINK_ERROR -> R.string.strava_notice_error
    StravaNotice.LINK_NOT_COMPLETED -> R.string.strava_notice_not_completed
    StravaNotice.SYNC_REQUESTED -> R.string.strava_notice_sync_requested
    StravaNotice.SYNC_ALREADY_RUNNING -> R.string.strava_notice_sync_running
    StravaNotice.SYNC_NOT_LINKED -> R.string.strava_notice_sync_not_linked
    StravaNotice.UNLINKED -> R.string.strava_notice_unlinked
}

@Composable
private fun ProblemCard(problem: StravaProblem, onRetry: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().testTag("strava_problem"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(problemLabel(problem)),
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .testTag("strava_problem_${problem.name.lowercase()}")
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (problem != StravaProblem.CAPABILITY_REQUIRED && problem != StravaProblem.PERMISSION_REQUIRED) {
                OutlinedButton(onClick = onRetry, modifier = Modifier.testTag("strava_retry")) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
}

@StringRes
private fun problemLabel(problem: StravaProblem): Int = when (problem) {
    StravaProblem.NO_CONNECTION -> R.string.strava_problem_no_connection
    StravaProblem.UNAVAILABLE -> R.string.strava_problem_unavailable
    StravaProblem.CAPABILITY_REQUIRED -> R.string.strava_problem_capability
    StravaProblem.PERMISSION_REQUIRED -> R.string.strava_problem_permission
    StravaProblem.INVALID_RESPONSE -> R.string.strava_problem_invalid
    StravaProblem.STORAGE -> R.string.strava_problem_storage
    StravaProblem.BROWSER_UNAVAILABLE -> R.string.strava_problem_browser
}

private fun dateTime(instant: Instant, zone: ZoneId, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zone).format(instant)

private fun date(instant: Instant, zone: ZoneId, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).withZone(zone).format(instant)
