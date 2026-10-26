package com.lfnj.sport_app.features.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lfnj.sport_app.R

/** Preferencias de las notificaciones de eventos (HU039): coincidencias y recordatorios 24 h antes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationPreferencesScreen(
    state: NotificationPreferencesUiState,
    onEventMatchesChange: (Boolean) -> Unit,
    onEventRemindersChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notifications_preferences_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("preferences_back")) {
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
                .testTag("preferences_screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.problem?.let { Notice(problemMessage(it), "preferences_problem", MaterialTheme.colorScheme.error) }
            when {
                state.loading -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.testTag("preferences_loading"))
                    Text(stringResource(R.string.notifications_preferences_loading))
                }
                state.confirmed == null -> if (state.problem != PreferencesProblem.PERMISSION_REQUIRED) {
                    Button(onClick = onRetry, modifier = Modifier.testTag("preferences_retry")) { Text(stringResource(R.string.action_retry)) }
                }
                else -> {
                    PreferenceSwitch(
                        R.string.notifications_preference_matches,
                        R.string.notifications_preference_matches_description,
                        state.eventMatches,
                        !state.saving,
                        "preferences_event_matches",
                        onEventMatchesChange,
                    )
                    PreferenceSwitch(
                        R.string.notifications_preference_reminders,
                        R.string.notifications_preference_reminders_description,
                        state.eventReminders,
                        !state.saving,
                        "preferences_event_reminders",
                        onEventRemindersChange,
                    )
                    Button(onClick = onSave, enabled = state.canSave, modifier = Modifier.testTag("preferences_save")) {
                        Text(stringResource(if (state.saving) R.string.notifications_preferences_saving else R.string.notifications_preferences_save))
                    }
                    if (state.saved) Notice(R.string.notifications_preferences_saved, "preferences_saved")
                }
            }
        }
    }
}

@Composable
private fun PreferenceSwitch(label: Int, description: Int, checked: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(label), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(description), style = MaterialTheme.typography.bodySmall)
        }
        // El Row recibe el gesto y la semántica de interruptor; el Switch solo lo representa.
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

private fun problemMessage(problem: PreferencesProblem): Int = when (problem) {
    PreferencesProblem.NO_CONNECTION -> R.string.notifications_preferences_offline
    PreferencesProblem.PERMISSION_REQUIRED -> R.string.notifications_error_permission_required
    PreferencesProblem.INVALID -> R.string.notifications_preferences_invalid
    PreferencesProblem.CONFLICT -> R.string.notifications_preferences_conflict
    PreferencesProblem.UNAVAILABLE -> R.string.notifications_preferences_unavailable
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
