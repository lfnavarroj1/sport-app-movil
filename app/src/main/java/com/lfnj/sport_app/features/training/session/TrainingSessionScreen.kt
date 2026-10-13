package com.lfnj.sport_app.features.training.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lfnj.sport_app.R
import com.lfnj.sport_app.features.training.session.data.RemoteCheck
import com.lfnj.sport_app.features.training.session.data.SystemTrainingClock
import com.lfnj.sport_app.features.training.session.data.TrainingClock
import com.lfnj.sport_app.features.training.session.model.LocalTrainingSession
import com.lfnj.sport_app.features.training.session.model.SyncFailure
import com.lfnj.sport_app.features.training.session.model.SyncState
import com.lfnj.sport_app.features.training.session.model.TrainingSessionDetails
import com.lfnj.sport_app.features.training.session.model.TrainingSport
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Registro de una sesión de entrenamiento (HU032, `/entrenamientos/sesion`): selección de deporte,
 * inicio, cronómetro, finalización y estado de sincronización de cada registro del dispositivo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingSessionScreen(
    state: TrainingSessionUiState,
    onSelectSport: (TrainingSport) -> Unit,
    onStart: () -> Unit,
    onFinish: () -> Unit,
    onDismiss: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    clock: TrainingClock = SystemTrainingClock,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.training_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("training_back")) {
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
                .padding(24.dp)
                .testTag("training_session"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.loading) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.testTag("training_loading"))
                    Text(stringResource(R.string.training_loading))
                }
                return@Column
            }
            Notices(state)
            val current = state.current
            val details = current?.details
            if (current != null && details != null) {
                ActiveSessionCard(current, details, state.monotonicStartMillis, state.busy, onFinish, clock)
            } else {
                NewSessionCard(state, onSelectSport, onStart)
            }
            Records(state.records, state.busy, onDismiss)
        }
    }
}

@Composable
private fun Notices(state: TrainingSessionUiState) {
    val error = when (state.actionError) {
        TrainingActionError.STORAGE -> R.string.training_error_storage
        TrainingActionError.ALREADY_OPEN -> R.string.training_error_already_open
        null -> null
    }
    // Sin sesión abierta, informa si no se pudo verificar una sesión activa en otro dispositivo.
    val remote = when (state.remoteCheck) {
        RemoteCheck.NO_CONNECTION -> R.string.training_remote_check_no_connection
        RemoteCheck.UNAVAILABLE -> R.string.training_remote_check_unavailable
        else -> null
    }?.takeIf { state.current == null }
    error?.let { Notice(it, MaterialTheme.colorScheme.error) }
    remote?.let { Notice(it, Color.Unspecified) }
}

@Composable
private fun Notice(message: Int, color: Color) {
    Text(
        stringResource(message),
        color = color,
        modifier = Modifier
            .testTag("training_notice")
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun NewSessionCard(state: TrainingSessionUiState, onSelectSport: (TrainingSport) -> Unit, onStart: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.training_new_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.training_sport_label))
            Column(Modifier.selectableGroup()) {
                TrainingSport.entries.forEach { sport ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = state.selectedSport == sport,
                                enabled = !state.busy,
                                role = Role.RadioButton,
                                onClick = { onSelectSport(sport) },
                            )
                            .padding(vertical = 4.dp)
                            .testTag("sport_${sport.wire}"),
                    ) {
                        RadioButton(selected = state.selectedSport == sport, onClick = null)
                        Text(stringResource(sport.label), modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
            if (state.sportMissing) {
                Text(
                    stringResource(R.string.training_sport_required),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .testTag("training_sport_required")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (!state.canStart) {
                Text(stringResource(R.string.training_cannot_start), modifier = Modifier.testTag("training_cannot_start"))
            }
            Button(
                onClick = onStart,
                enabled = state.canStart && !state.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("training_start"),
            ) {
                Text(stringResource(if (state.busy) R.string.training_starting else R.string.training_start))
            }
        }
    }
}

@Composable
private fun ActiveSessionCard(
    session: LocalTrainingSession,
    details: TrainingSessionDetails,
    monotonicStartMillis: Long?,
    busy: Boolean,
    onFinish: () -> Unit,
    clock: TrainingClock,
) {
    val elapsed = rememberElapsedSeconds(details.startedAt, monotonicStartMillis, clock)
    val elapsedDescription = durationText(elapsed)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.training_active_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(details.sport.label), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.training_record_started, formatInstant(details.startedAt)))
            val chronometerDescription = stringResource(R.string.training_elapsed_description, elapsedDescription)
            Text(
                text = clockText(elapsed),
                style = MaterialTheme.typography.displayMedium,
                modifier = Modifier
                    .testTag("training_chronometer")
                    .semantics { contentDescription = chronometerDescription },
            )
            SyncStatus(session)
            Text(stringResource(R.string.training_chronometer_note), style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = onFinish,
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("training_finish"),
            ) {
                Text(stringResource(if (busy) R.string.training_finishing else R.string.training_finish))
            }
        }
    }
}

@Composable
private fun Records(records: List<LocalTrainingSession>, busy: Boolean, onDismiss: (String) -> Unit) {
    Text(
        stringResource(R.string.training_records_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    if (records.isEmpty()) {
        Text(stringResource(R.string.training_records_empty), modifier = Modifier.testTag("training_records_empty"))
        return
    }
    records.forEach { record ->
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .padding(16.dp)
                    .testTag("training_record_${record.sessionId}"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val details = record.details
                if (details == null) {
                    Text(stringResource(R.string.training_record_unreadable), style = MaterialTheme.typography.titleSmall)
                } else {
                    Text(stringResource(details.sport.label), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.training_record_started, formatInstant(details.startedAt)))
                    val official = details.officialDurationSeconds
                    val local = details.localDurationSeconds
                    when {
                        record.syncState == SyncState.SYNCED && official != null ->
                            Text(stringResource(R.string.training_duration_official, durationText(official)))
                        local != null -> Text(stringResource(R.string.training_duration_local, durationText(local)))
                        else -> Text(stringResource(R.string.training_record_not_finished))
                    }
                }
                SyncStatus(record)
                if (record.dismissible) {
                    val sportName = record.details?.let { stringResource(it.sport.label) } ?: stringResource(R.string.training_record_unreadable)
                    val dismissDescription = stringResource(R.string.training_dismiss_description, sportName)
                    OutlinedButton(
                        onClick = { onDismiss(record.sessionId) },
                        enabled = !busy,
                        modifier = Modifier
                            .testTag("training_dismiss_${record.sessionId}")
                            .semantics { contentDescription = dismissDescription },
                    ) {
                        Text(stringResource(R.string.training_dismiss))
                    }
                }
            }
        }
    }
}

/** Estado de sincronización visible en todo registro: nunca muestra un pendiente como confirmado. */
@Composable
private fun SyncStatus(session: LocalTrainingSession) {
    val (label, color) = when (session.syncState) {
        SyncState.LOCAL -> R.string.sync_local to MaterialTheme.colorScheme.onSurfaceVariant
        SyncState.PENDING_SYNC -> R.string.sync_pending to MaterialTheme.colorScheme.onSurfaceVariant
        SyncState.SYNCED -> R.string.sync_synced to MaterialTheme.colorScheme.tertiary
        SyncState.SYNC_FAILED -> R.string.sync_failed to MaterialTheme.colorScheme.error
    }
    Column(
        Modifier
            .testTag("sync_state_${session.sessionId}")
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(stringResource(label), color = color, style = MaterialTheme.typography.labelLarge)
        session.failure?.takeIf { session.syncState == SyncState.SYNC_FAILED }?.let {
            Text(stringResource(it.message()), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun SyncFailure.message(): Int = when (this) {
    SyncFailure.ACTIVE_SESSION_EXISTS -> R.string.training_failure_active_session_exists
    SyncFailure.CAPABILITY_REQUIRED -> R.string.training_failure_capability_required
    SyncFailure.PERMISSION_REQUIRED -> R.string.training_failure_permission_required
    SyncFailure.PLAN_NOT_FOUND -> R.string.training_failure_plan_not_found
    SyncFailure.SESSION_NOT_FOUND -> R.string.training_failure_session_not_found
    SyncFailure.SESSION_NOT_ACTIVE -> R.string.training_failure_session_not_active
    SyncFailure.CONFLICT -> R.string.training_failure_conflict
    SyncFailure.DEVICE_CLOCK -> R.string.training_failure_device_clock
    SyncFailure.EXPIRED -> R.string.training_failure_expired
    SyncFailure.REJECTED, SyncFailure.INVALID_RESPONSE -> R.string.training_failure_rejected
    SyncFailure.UNREADABLE -> R.string.training_failure_unreadable
}

/**
 * Segundos transcurridos: con reloj monotónico si el inicio ocurrió en este proceso; tras un
 * reinicio se reconstruye desde `started_at` (ADR-029 offline §8). Solo es informativo.
 */
@Composable
private fun rememberElapsedSeconds(startedAt: Instant, monotonicStartMillis: Long?, clock: TrainingClock): Long {
    fun compute(): Long = (
        monotonicStartMillis?.let { (clock.elapsedRealtimeMillis() - it) / 1000 }
            ?: (clock.now().epochSecond - startedAt.epochSecond)
        ).coerceAtLeast(0)

    var elapsed by remember(startedAt, monotonicStartMillis) { mutableLongStateOf(compute()) }
    LaunchedEffect(startedAt, monotonicStartMillis) {
        while (true) {
            elapsed = compute()
            delay(TICK_MILLIS)
        }
    }
    return elapsed
}

private const val TICK_MILLIS = 1_000L

private fun clockText(seconds: Long): String =
    String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)

@Composable
private fun durationText(seconds: Long): String =
    stringResource(R.string.training_duration_value, seconds / 3600, (seconds % 3600) / 60, seconds % 60)

/** Fecha y hora en la zona y el formato regional del dispositivo; el dato fuente sigue en UTC. */
private fun formatInstant(instant: Instant): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
        .format(instant)
