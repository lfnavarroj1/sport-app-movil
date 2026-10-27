package com.lfnj.sport_app.features.nutrition.reminders

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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.lfnj.sport_app.R
import com.lfnj.sport_app.features.notifications.model.SystemNotificationsStatus
import com.lfnj.sport_app.features.nutrition.reminders.engine.AndroidReminderNotifier
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderConfig
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderRule
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderSetting
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderType
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * «Recordatorios durante sesión» (HU038): hidratación y alimentación con los rangos del ruleset,
 * mensajes y aviso aprobados sin modificar, estado de las notificaciones del sistema y de la última
 * configuración confirmada. Sin ruleset descargado declara «información insuficiente» (CA5).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionRemindersScreen(
    state: SessionRemindersUiState,
    onToggle: (ReminderType, Boolean) -> Unit,
    onChangeInterval: (ReminderType, Int) -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onRetry: () -> Unit,
    onEnableSystemNotifications: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.reminders_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("reminders_back")) {
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
                .testTag("reminders_screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val config = state.config
            val forbidden = state.problem == RemindersProblem.CAPABILITY_REQUIRED || state.problem == RemindersProblem.PERMISSION_REQUIRED
            when {
                state.loading -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.testTag("reminders_loading"))
                    Text(stringResource(R.string.reminders_loading))
                }
                forbidden -> {
                    Notice(
                        if (state.problem == RemindersProblem.CAPABILITY_REQUIRED) R.string.reminders_error_capability else R.string.reminders_error_permission,
                        "reminders_forbidden",
                        MaterialTheme.colorScheme.error,
                    )
                }
                config == null -> {
                    Notice(R.string.reminders_insufficient_information, "reminders_insufficient_information", MaterialTheme.colorScheme.error)
                    state.problem?.let { Notice(problemMessage(it), "reminders_problem") }
                    Button(onClick = onRetry, enabled = !state.refreshing, modifier = Modifier.testTag("reminders_retry")) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
                else -> {
                    SyncStatus(state, config, onRetry)
                    SystemNotificationsNotice(state.systemNotifications, onEnableSystemNotifications)
                    ReminderType.entries.forEach { type ->
                        config.ruleset.rule(type)?.let { rule ->
                            state.setting(type)?.let { setting -> ReminderCard(rule, setting, state.saving, onToggle, onChangeInterval) }
                        }
                    }
                    config.ruleset.rules.map { it.disclaimer }.distinct().forEach { disclaimer ->
                        Text(disclaimer, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("reminders_disclaimer"))
                    }
                    SaveSection(state, onSave, onDiscard)
                }
            }
        }
    }
}

@Composable
private fun SyncStatus(state: SessionRemindersUiState, config: ReminderConfig, onRetry: () -> Unit) {
    if (state.refreshing) {
        val refreshing = stringResource(R.string.reminders_refreshing)
        LinearProgressIndicator(
            Modifier
                .fillMaxWidth()
                .testTag("reminders_refreshing")
                .semantics { contentDescription = refreshing },
        )
    }
    Text(
        stringResource(R.string.reminders_synced_at, syncedAt(config, state.zone)),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("reminders_synced_at"),
    )
    state.problem?.let { problem ->
        Notice(
            if (problem == RemindersProblem.NO_CONNECTION) R.string.reminders_offline_with_cache else R.string.reminders_unavailable_with_cache,
            "reminders_problem",
        )
        if (problem != RemindersProblem.NO_CONNECTION) {
            OutlinedButton(onClick = onRetry, enabled = !state.refreshing, modifier = Modifier.testTag("reminders_retry")) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

/** Notificaciones del sistema desactivadas: los recordatorios llegarán solo como banner (CA4). */
@Composable
private fun SystemNotificationsNotice(status: SystemNotificationsStatus, onEnable: () -> Unit) {
    if (status == SystemNotificationsStatus.ENABLED) return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("reminders_system_disabled")
            .semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.reminders_system_disabled))
            OutlinedButton(onClick = onEnable, modifier = Modifier.testTag("reminders_enable_system")) {
                Text(
                    stringResource(
                        if (status == SystemNotificationsStatus.PERMISSION_REQUIRED) R.string.notifications_allow else R.string.notifications_open_settings
                    )
                )
            }
        }
    }
}

@Composable
private fun ReminderCard(
    rule: ReminderRule,
    setting: ReminderSetting,
    saving: Boolean,
    onToggle: (ReminderType, Boolean) -> Unit,
    onChangeInterval: (ReminderType, Int) -> Unit,
) {
    val tag = rule.type.name.lowercase()
    val title = stringResource(AndroidReminderNotifier.titleOf(rule.type))
    Card(Modifier.fillMaxWidth().testTag("reminders_card_$tag")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
                val stateLabel = stringResource(if (setting.enabled) R.string.reminders_enabled else R.string.reminders_disabled)
                Switch(
                    checked = setting.enabled,
                    onCheckedChange = { onToggle(rule.type, it) },
                    enabled = !saving,
                    modifier = Modifier
                        .testTag("reminders_toggle_$tag")
                        .semantics {
                            contentDescription = title
                            stateDescription = stateLabel
                            role = Role.Switch
                        },
                )
            }
            Text(rule.message, modifier = Modifier.testTag("reminders_message_$tag"))
            if (rule.appliesAfterMinutes > 0) {
                Text(stringResource(R.string.reminders_applies_after, rule.appliesAfterMinutes), style = MaterialTheme.typography.bodySmall)
            }
            val step = intervalStep(rule)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onChangeInterval(rule.type, -step) },
                    enabled = setting.enabled && !saving && setting.intervalMinutes > rule.minIntervalMinutes,
                    modifier = Modifier.testTag("reminders_decrease_$tag"),
                ) { Text(stringResource(R.string.reminders_decrease, step)) }
                Text(
                    stringResource(R.string.reminders_interval, setting.intervalMinutes),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("reminders_interval_$tag")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                OutlinedButton(
                    onClick = { onChangeInterval(rule.type, step) },
                    enabled = setting.enabled && !saving && setting.intervalMinutes < rule.maxIntervalMinutes,
                    modifier = Modifier.testTag("reminders_increase_$tag"),
                ) { Text(stringResource(R.string.reminders_increase, step)) }
            }
            Text(
                stringResource(R.string.reminders_range, rule.minIntervalMinutes, rule.maxIntervalMinutes, rule.defaultIntervalMinutes),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SaveSection(state: SessionRemindersUiState, onSave: () -> Unit, onDiscard: () -> Unit) {
    if (state.saving) {
        val saving = stringResource(R.string.reminders_saving)
        LinearProgressIndicator(Modifier.fillMaxWidth().testTag("reminders_saving").semantics { contentDescription = saving })
    }
    state.saveOutcome?.let { outcome ->
        Notice(
            saveMessage(outcome),
            "reminders_save_outcome",
            if (outcome == RemindersSaveOutcome.SAVED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onSave, enabled = state.canSave, modifier = Modifier.testTag("reminders_save")) {
            Text(stringResource(R.string.reminders_save))
        }
        if (state.dirty) {
            TextButton(onClick = onDiscard, enabled = !state.saving, modifier = Modifier.testTag("reminders_discard")) {
                Text(stringResource(R.string.reminders_discard))
            }
        }
    }
}

@Composable
private fun Notice(text: Int, tag: String, color: Color = Color.Unspecified) {
    Text(
        stringResource(text),
        color = color,
        modifier = Modifier
            .testTag(tag)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Paso de los botones: 5 minutos, o 1 si el rango de la regla no es múltiplo de 5. */
internal fun intervalStep(rule: ReminderRule): Int =
    if (rule.minIntervalMinutes % 5 == 0 && rule.maxIntervalMinutes % 5 == 0) 5 else 1

private fun problemMessage(problem: RemindersProblem): Int = when (problem) {
    RemindersProblem.NO_CONNECTION -> R.string.reminders_offline_without_cache
    RemindersProblem.CAPABILITY_REQUIRED -> R.string.reminders_error_capability
    RemindersProblem.PERMISSION_REQUIRED -> R.string.reminders_error_permission
    RemindersProblem.INVALID_RESPONSE, RemindersProblem.UNAVAILABLE, RemindersProblem.STORAGE -> R.string.reminders_error_unavailable
}

private fun saveMessage(outcome: RemindersSaveOutcome): Int = when (outcome) {
    RemindersSaveOutcome.SAVED -> R.string.reminders_saved
    RemindersSaveOutcome.VERSION_CONFLICT -> R.string.reminders_save_conflict
    RemindersSaveOutcome.INVALID -> R.string.reminders_save_invalid
    RemindersSaveOutcome.NO_CONNECTION -> R.string.reminders_save_offline
    RemindersSaveOutcome.CAPABILITY_REQUIRED -> R.string.reminders_error_capability
    RemindersSaveOutcome.PERMISSION_REQUIRED -> R.string.reminders_error_permission
    RemindersSaveOutcome.UNAVAILABLE, RemindersSaveOutcome.STORAGE -> R.string.reminders_save_unavailable
}

private fun syncedAt(config: ReminderConfig, zone: ZoneId): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(zone).format(config.syncedAt)
