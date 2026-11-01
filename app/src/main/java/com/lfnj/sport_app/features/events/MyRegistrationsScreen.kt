package com.lfnj.sport_app.features.events

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lfnj.sport_app.R
import com.lfnj.sport_app.features.events.model.EventRegistration

/**
 * «Mis inscripciones» (HU041): inscripciones confirmadas por el backend, ordenadas por fecha del
 * evento, con su antigüedad. Sin red muestra la última lista sincronizada en el dispositivo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyRegistrationsScreen(
    state: MyRegistrationsUiState,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenEvent: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.registrations_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("registrations_back")) {
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
                .testTag("registrations_screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val snapshot = state.snapshot
            when {
                state.loading -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.testTag("registrations_loading"))
                    Text(stringResource(R.string.registrations_loading))
                }
                snapshot == null -> {
                    if (state.neverSynced) {
                        Notice(R.string.registrations_never_synced, "registrations_never_synced")
                    } else {
                        Notice(problemMessage(state.problem), "registrations_error", MaterialTheme.colorScheme.error)
                    }
                    if (state.problem != RegistrationsProblem.PERMISSION_REQUIRED) {
                        Button(onClick = onRetry, enabled = !state.refreshing, modifier = Modifier.testTag("registrations_retry")) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
                else -> {
                    if (state.refreshing) {
                        val refreshing = stringResource(R.string.registrations_refreshing)
                        LinearProgressIndicator(
                            Modifier
                                .fillMaxWidth()
                                .testTag("registrations_refreshing")
                                .semantics { contentDescription = refreshing },
                        )
                    }
                    Text(
                        stringResource(
                            R.string.registrations_synced_at,
                            EventFormat.dateTime(snapshot.syncedAt, state.zone, EventFormat.currentLocale()),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("registrations_synced_at"),
                    )
                    if (state.stale) Notice(R.string.registrations_stale, "registrations_stale", MaterialTheme.colorScheme.error)
                    when (state.problem) {
                        null -> Unit
                        RegistrationsProblem.NO_CONNECTION -> Notice(R.string.registrations_offline_with_cache, "registrations_problem")
                        else -> {
                            Notice(R.string.registrations_unavailable_with_cache, "registrations_problem")
                            OutlinedButton(onClick = onRetry, enabled = !state.refreshing, modifier = Modifier.testTag("registrations_retry")) {
                                Text(stringResource(R.string.action_retry))
                            }
                        }
                    }
                    if (state.empty) {
                        Text(stringResource(R.string.registrations_empty), Modifier.testTag("registrations_empty"))
                    } else {
                        state.items.forEach { RegistrationCard(it, state, onOpenEvent) }
                    }
                    if (snapshot.nextCursor != null) {
                        OutlinedButton(onClick = onLoadMore, enabled = state.canLoadMore, modifier = Modifier.testTag("registrations_load_more")) {
                            Text(stringResource(if (state.loadingMore) R.string.registrations_loading_more else R.string.registrations_load_more))
                        }
                    }
                }
            }
        }
    }
}

private fun problemMessage(problem: RegistrationsProblem?): Int = when (problem) {
    RegistrationsProblem.PERMISSION_REQUIRED -> R.string.event_error_permission_required
    RegistrationsProblem.INVALID_RESPONSE -> R.string.event_error_invalid_response
    RegistrationsProblem.STORAGE -> R.string.registrations_error_storage
    RegistrationsProblem.NO_CONNECTION, RegistrationsProblem.UNAVAILABLE, RegistrationsProblem.INVALID_REQUEST, null ->
        R.string.registrations_error_unavailable
}

@Composable
private fun RegistrationCard(registration: EventRegistration, state: MyRegistrationsUiState, onOpenEvent: (String) -> Unit) {
    val locale = EventFormat.currentLocale()
    val event = registration.event
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("registration_${registration.registrationId}")
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.registrations_open_event)) { onOpenEvent(registration.eventId) }
            .semantics(mergeDescendants = true) { },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(event.name, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.event_when, *EventFormat.range(event.startsAt, event.endsAt, state.zone, locale)))
            Text(stringResource(R.string.event_location, event.location.name, event.location.city, event.location.countryCode))
            Text(stringResource(R.string.registrations_status_registered), style = MaterialTheme.typography.labelMedium)
            Text(
                stringResource(R.string.event_registered_at, EventFormat.dateTime(registration.registeredAt, state.zone, locale)),
                style = MaterialTheme.typography.bodySmall,
            )
            registration.appliedFee?.let {
                Text(stringResource(R.string.event_fee, it.amount, it.currency))
                Text(stringResource(R.string.event_fee_notice), style = MaterialTheme.typography.bodySmall)
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
