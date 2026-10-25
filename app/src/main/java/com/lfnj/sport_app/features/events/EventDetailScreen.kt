package com.lfnj.sport_app.features.events

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lfnj.sport_app.R
import com.lfnj.sport_app.features.events.model.EventDetail
import com.lfnj.sport_app.features.events.model.EventRegistration

/**
 * Detalle de un evento publicado (HU041): fecha y hora en la zona del dispositivo, ubicación,
 * cupo declarado y tarifa informativa. «Inscribirme» pide confirmación explícita y solo muestra la
 * inscripción con la confirmación del backend; se deshabilita, con explicación, si ya estás
 * inscrito, el evento inició o no hay conexión.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailScreen(
    state: EventDetailUiState,
    onRegister: () -> Unit,
    onConfirmRegister: () -> Unit,
    onCancelRegister: () -> Unit,
    onDismissFailure: () -> Unit,
    onRetry: () -> Unit,
    onOpenRegistrations: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.event_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("event_back")) {
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
                .testTag("event_detail_screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val detail = state.detail
            when {
                detail == null && state.loading -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.testTag("event_loading"))
                    Text(stringResource(R.string.event_loading))
                }
                detail == null -> WithoutDetail(state, onRetry, onOpenRegistrations)
                else -> {
                    if (state.problem != null) Notice(problemMessage(state.problem), "event_problem")
                    DetailContent(state, detail)
                    RegistrationSection(state, onRegister, onDismissFailure, onOpenRegistrations)
                }
            }
        }
    }

    if (state.confirming && state.detail != null) {
        AlertDialog(
            onDismissRequest = onCancelRegister,
            title = { Text(stringResource(R.string.event_confirm_title)) },
            text = {
                Text(
                    stringResource(R.string.event_confirm_message, state.detail.name),
                    modifier = Modifier.testTag("event_confirm_message"),
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmRegister, modifier = Modifier.testTag("event_confirm")) {
                    Text(stringResource(R.string.event_confirm_send))
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelRegister, modifier = Modifier.testTag("event_confirm_cancel")) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun WithoutDetail(state: EventDetailUiState, onRetry: () -> Unit, onOpenRegistrations: () -> Unit) {
    val problem = state.problem
    Notice(problemMessage(problem), "event_error", MaterialTheme.colorScheme.error)
    if (problem == EventDetailProblem.NO_CONNECTION) {
        // CA10: sin red no hay detalle que confirmar y la inscripción queda deshabilitada.
        Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().testTag("event_register")) {
            Text(stringResource(R.string.event_register))
        }
        Notice(R.string.event_block_offline, "event_register_block")
    }
    if (problem != EventDetailProblem.NOT_FOUND && problem != EventDetailProblem.PERMISSION_REQUIRED) {
        OutlinedButton(onClick = onRetry, enabled = !state.loading, modifier = Modifier.testTag("event_retry")) {
            Text(stringResource(R.string.action_retry))
        }
    }
    if (problem != EventDetailProblem.PERMISSION_REQUIRED) {
        TextButton(onClick = onOpenRegistrations, modifier = Modifier.testTag("event_open_registrations")) {
            Text(stringResource(R.string.event_open_registrations))
        }
    }
}

private fun problemMessage(problem: EventDetailProblem?): Int = when (problem) {
    EventDetailProblem.NOT_FOUND -> R.string.event_error_not_found
    EventDetailProblem.NO_CONNECTION -> R.string.event_error_no_connection
    EventDetailProblem.PERMISSION_REQUIRED -> R.string.event_error_permission_required
    EventDetailProblem.INVALID_RESPONSE -> R.string.event_error_invalid_response
    EventDetailProblem.UNAVAILABLE, null -> R.string.event_error_unavailable
}

@Composable
private fun DetailContent(state: EventDetailUiState, detail: EventDetail) {
    val locale = EventFormat.currentLocale()
    Text(
        detail.name,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier
            .testTag("event_name")
            .semantics { heading() },
    )
    Text(detail.discipline, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("event_discipline"))
    Text(
        stringResource(R.string.event_when, *EventFormat.range(detail.startsAt, detail.endsAt, state.zone, locale)),
        modifier = Modifier.testTag("event_when"),
    )
    Text(stringResource(R.string.event_time_zone, detail.timeZone), style = MaterialTheme.typography.bodySmall)
    Text(
        stringResource(R.string.event_location, detail.location.name, detail.location.city, detail.location.countryCode),
        modifier = Modifier.testTag("event_location"),
    )
    Text(stringResource(R.string.event_capacity, detail.capacity), modifier = Modifier.testTag("event_capacity"))
    detail.fee?.let { fee ->
        Column(Modifier.testTag("event_fee"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.event_fee, fee.amount, fee.currency))
            Text(stringResource(R.string.event_fee_notice), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("event_fee_notice"))
        }
    }
    if (detail.description.isNotBlank()) Text(detail.description, modifier = Modifier.testTag("event_description"))
}

@Composable
private fun RegistrationSection(
    state: EventDetailUiState,
    onRegister: () -> Unit,
    onDismissFailure: () -> Unit,
    onOpenRegistrations: () -> Unit,
) {
    val confirmed = state.confirmed
    val existing = state.detail?.myRegistration
    when {
        confirmed != null -> ConfirmedCard(state, confirmed)
        existing != null -> Notice(R.string.event_already_registered, "event_registered")
    }
    if (state.submitting) {
        val sending = stringResource(R.string.event_registering)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.testTag("event_submitting").semantics { contentDescription = sending })
            Text(sending)
        }
    }
    if (!state.registered) {
        Button(onClick = onRegister, enabled = state.canRegister, modifier = Modifier.fillMaxWidth().testTag("event_register")) {
            Text(stringResource(R.string.event_register))
        }
        when (state.block) {
            RegisterBlock.STARTED -> Notice(R.string.event_block_started, "event_register_block")
            RegisterBlock.OFFLINE -> Notice(R.string.event_block_offline, "event_register_block")
            RegisterBlock.ALREADY_REGISTERED, null -> Unit
        }
    }
    state.failure?.let { failure ->
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth().testTag("event_failure_${failure.name.lowercase()}"),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(failureMessage(failure)),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
                TextButton(onClick = onDismissFailure, modifier = Modifier.testTag("event_failure_dismiss")) {
                    Text(stringResource(R.string.action_accept))
                }
            }
        }
    }
    if (state.registered || state.failure == RegistrationFailure.ALREADY_REGISTERED) {
        OutlinedButton(onClick = onOpenRegistrations, modifier = Modifier.fillMaxWidth().testTag("event_open_registrations")) {
            Text(stringResource(R.string.event_open_registrations))
        }
    } else {
        TextButton(onClick = onOpenRegistrations, modifier = Modifier.testTag("event_open_registrations")) {
            Text(stringResource(R.string.event_open_registrations))
        }
    }
}

@Composable
private fun ConfirmedCard(state: EventDetailUiState, registration: EventRegistration) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth().testTag("event_registration_confirmed"),
    ) {
        Column(Modifier.padding(12.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
            Text(stringResource(R.string.event_registered_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(
                    R.string.event_registered_at,
                    EventFormat.dateTime(registration.registeredAt, state.zone, EventFormat.currentLocale()),
                )
            )
            registration.appliedFee?.let {
                Text(stringResource(R.string.event_fee, it.amount, it.currency))
                Text(stringResource(R.string.event_fee_notice), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun failureMessage(failure: RegistrationFailure): Int = when (failure) {
    RegistrationFailure.ALREADY_REGISTERED -> R.string.event_failure_already_registered
    RegistrationFailure.FULL -> R.string.event_failure_full
    RegistrationFailure.CLOSED -> R.string.event_failure_closed
    RegistrationFailure.KEY_REUSED -> R.string.event_failure_key_reused
    RegistrationFailure.NOT_FOUND -> R.string.event_error_not_found
    RegistrationFailure.NO_CONNECTION -> R.string.event_failure_no_connection
    RegistrationFailure.UNAVAILABLE -> R.string.event_failure_unavailable
    RegistrationFailure.PERMISSION_REQUIRED -> R.string.event_error_permission_required
    RegistrationFailure.INVALID -> R.string.event_failure_invalid
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
