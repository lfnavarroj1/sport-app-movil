package com.lfnj.sport_app.features.training.monitoring

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lfnj.sport_app.R
import com.lfnj.sport_app.features.notifications.model.SystemNotificationsStatus
import com.lfnj.sport_app.features.sensors.model.MonitorConnection
import com.lfnj.sport_app.features.training.monitoring.data.SettingsProblem
import com.lfnj.sport_app.features.training.monitoring.model.LiveReading
import com.lfnj.sport_app.features.training.monitoring.model.RiskAlert
import com.lfnj.sport_app.features.training.monitoring.model.ThresholdSource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun Instant.localTime(): String = TIME.format(atZone(ZoneId.systemDefault()))

/**
 * «Monitoreo en tiempo real» (HU036): frecuencia cardiaca en vivo con su instante y antigüedad, estado
 * de conexión, evaluación remota de cada lote, alertas con el aviso no diagnóstico, umbral
 * configurable y «Detener la práctica». Sin red sigue mostrando los datos locales y lo declara.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitoringScreen(
    state: MonitoringUiState,
    onOpenSession: () -> Unit,
    onOpenDevices: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSaveThreshold: () -> Unit,
    onRetrySettings: () -> Unit,
    onAskStop: () -> Unit,
    onConfirmStop: () -> Unit,
    onCancelStop: () -> Unit,
    onEnableNotifications: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.monitoring_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("monitoring_back")) {
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
                .testTag("monitoring_screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.blockedByPlan) {
                Notice(R.string.monitoring_capability_required, "monitoring_capability_required", MaterialTheme.colorScheme.error)
                return@Column
            }
            if (state.simulatedSource) {
                Notice(R.string.monitoring_simulated_source, "monitoring_simulated_source", MaterialTheme.colorScheme.tertiary)
            }
            if (!state.online) Notice(R.string.monitoring_offline, "monitoring_offline")
            if (state.notifications != SystemNotificationsStatus.ENABLED && state.session != null) {
                Notice(R.string.monitoring_notifications_off, "monitoring_notifications_off")
                OutlinedButton(onClick = onEnableNotifications, modifier = Modifier.testTag("monitoring_notifications_enable")) {
                    Text(stringResource(R.string.monitoring_notifications_enable))
                }
            }
            when {
                state.stop == StopPhase.STOPPED -> Notice(R.string.monitoring_stopped, "monitoring_stopped")
                state.session == null -> {
                    Notice(R.string.monitoring_no_session, "monitoring_no_session")
                    Button(onClick = onOpenSession, modifier = Modifier.testTag("monitoring_open_session")) {
                        Text(stringResource(R.string.monitoring_open_session))
                    }
                }
                state.hasDevice == null -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.testTag("monitoring_loading"))
                    Text(stringResource(R.string.monitoring_loading))
                }
                state.hasDevice == false -> {
                    Notice(R.string.monitoring_no_device, "monitoring_no_device")
                    Button(onClick = onOpenDevices, modifier = Modifier.testTag("monitoring_open_devices")) {
                        Text(stringResource(R.string.monitoring_open_devices))
                    }
                }
                else -> {
                    state.alerts.firstOrNull()?.let { AlertCard(it) }
                    LiveCard(state)
                    RemoteLine(state)
                    StopSection(state, onAskStop, onConfirmStop, onCancelStop)
                }
            }
            ThresholdSection(state.threshold, onDraftChange, onSaveThreshold, onRetrySettings)
        }
    }
}

@Composable
private fun LiveCard(state: MonitoringUiState) {
    Card(Modifier.fillMaxWidth().testTag("monitoring_live")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.monitoring_live_heading), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            when (val live = state.live) {
                is LiveReading.Live -> {
                    val description = stringResource(R.string.monitoring_live_value_description, live.bpm)
                    Text(
                        stringResource(R.string.monitoring_live_value, live.bpm),
                        style = MaterialTheme.typography.displayMedium,
                        modifier = Modifier.testTag("monitoring_live_value").semantics { contentDescription = description },
                    )
                    Text(
                        stringResource(R.string.monitoring_live_measured_at, live.measuredAt.localTime(), live.ageSeconds),
                        modifier = Modifier.testTag("monitoring_live_measured_at"),
                    )
                }
                LiveReading.Waiting -> Text(stringResource(R.string.monitoring_waiting), modifier = Modifier.testTag("monitoring_waiting"))
                is LiveReading.Reconnecting -> Text(
                    stringResource(R.string.monitoring_reconnecting, live.lastMeasuredAt.localTime()),
                    modifier = Modifier.testTag("monitoring_reconnecting").semantics { liveRegion = LiveRegionMode.Polite },
                )
                is LiveReading.NoData -> {
                    Text(
                        stringResource(R.string.monitoring_no_data),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("monitoring_no_data").semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                    live.lastMeasuredAt?.let { Text(stringResource(R.string.monitoring_no_data_last, it.localTime()), style = MaterialTheme.typography.bodySmall) }
                }
                is LiveReading.Unavailable -> Text(
                    stringResource(
                        when (live.connection) {
                            MonitorConnection.MISSING_HEART_RATE_SERVICE -> R.string.monitoring_unavailable_service
                            MonitorConnection.PERMISSION_REQUIRED -> R.string.monitoring_unavailable_permission
                            else -> R.string.monitoring_unavailable_bluetooth
                        }
                    ),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("monitoring_unavailable"),
                )
            }
            val connection = stringResource(connectionLabel(state.monitor?.connection ?: MonitorConnection.CONNECTING))
            Text(
                stringResource(R.string.monitoring_connection, connection),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("monitoring_connection").semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(
                state.monitor?.batteryPercent?.let { stringResource(R.string.devices_battery, it) } ?: stringResource(R.string.devices_battery_unknown),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("monitoring_battery"),
            )
        }
    }
}

@Composable
private fun RemoteLine(state: MonitoringUiState) {
    val at = state.evaluation?.receivedAt?.localTime().orEmpty()
    val (text, color) = when (state.remote) {
        RemoteStatus.WAITING -> stringResource(R.string.monitoring_remote_waiting) to Color.Unspecified
        RemoteStatus.OK -> stringResource(R.string.monitoring_remote_ok, at) to Color.Unspecified
        RemoteStatus.RISK -> stringResource(R.string.monitoring_remote_risk, at) to MaterialTheme.colorScheme.error
        RemoteStatus.NO_DATA -> stringResource(R.string.monitoring_remote_no_data, at) to MaterialTheme.colorScheme.error
        RemoteStatus.UNAVAILABLE -> stringResource(R.string.monitoring_remote_unavailable) to MaterialTheme.colorScheme.tertiary
    }
    Text(text, color = color, modifier = Modifier.testTag("monitoring_remote").semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun AlertCard(alert: RiskAlert) {
    Card(
        Modifier.fillMaxWidth().testTag("monitoring_alert"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Assertive }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.monitoring_alert_heading),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.semantics { heading() },
            )
            Text(alert.message, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.testTag("monitoring_alert_message"))
            Text(alert.instruction, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.testTag("monitoring_alert_instruction"))
            Text(
                stringResource(R.string.monitoring_alert_detected_at, alert.detectedAt.localTime()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun StopSection(state: MonitoringUiState, onAskStop: () -> Unit, onConfirmStop: () -> Unit, onCancelStop: () -> Unit) {
    when (state.stop) {
        StopPhase.CONFIRMING -> Card(Modifier.fillMaxWidth().testTag("monitoring_stop_confirmation")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.monitoring_stop_confirmation))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onConfirmStop,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.testTag("monitoring_stop_confirm"),
                    ) { Text(stringResource(R.string.monitoring_stop_confirm)) }
                    OutlinedButton(onClick = onCancelStop, modifier = Modifier.testTag("monitoring_stop_cancel")) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        }
        StopPhase.STOPPING -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.testTag("monitoring_stopping"))
            Text(stringResource(R.string.monitoring_stopping))
        }
        else -> {
            if (state.stop == StopPhase.FAILED) Notice(R.string.monitoring_stop_failed, "monitoring_stop_failed", MaterialTheme.colorScheme.error)
            Button(
                onClick = onAskStop,
                enabled = state.canStop,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().testTag("monitoring_stop"),
            ) { Text(stringResource(R.string.monitoring_stop)) }
        }
    }
}

@Composable
private fun ThresholdSection(
    threshold: ThresholdState,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().testTag("monitoring_threshold")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.monitoring_threshold_heading), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            val settings = threshold.settings
            when {
                threshold.loading -> Text(stringResource(R.string.monitoring_threshold_loading), modifier = Modifier.testTag("monitoring_threshold_loading"))
                settings != null -> {
                    Text(stringResource(R.string.monitoring_threshold_current, settings.maxHeartRateAlertBpm), modifier = Modifier.testTag("monitoring_threshold_current"))
                    Text(
                        stringResource(
                            when (settings.source) {
                                ThresholdSource.CUSTOM -> R.string.monitoring_threshold_source_custom
                                ThresholdSource.AGE_DEFAULT -> R.string.monitoring_threshold_source_age
                                ThresholdSource.FIXED_DEFAULT -> R.string.monitoring_threshold_source_fixed
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("monitoring_threshold_source"),
                    )
                }
            }
            threshold.problem?.let { problem ->
                Notice(settingsProblemMessage(problem), "monitoring_threshold_problem", MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onRetry, enabled = !threshold.loading, modifier = Modifier.testTag("monitoring_threshold_retry")) {
                    Text(stringResource(R.string.action_retry))
                }
            }
            if (settings != null) {
                OutlinedTextField(
                    value = threshold.draft,
                    onValueChange = onDraftChange,
                    label = { Text(stringResource(R.string.monitoring_threshold_label)) },
                    singleLine = true,
                    isError = threshold.invalidDraft,
                    enabled = !threshold.saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().testTag("monitoring_threshold_input"),
                )
                if (threshold.invalidDraft) Notice(R.string.monitoring_threshold_invalid, "monitoring_threshold_invalid", MaterialTheme.colorScheme.error)
                threshold.saveProblem?.let { Notice(settingsProblemMessage(it), "monitoring_threshold_save_problem", MaterialTheme.colorScheme.error) }
                if (threshold.saved) Notice(R.string.monitoring_threshold_saved, "monitoring_threshold_saved")
                Button(onClick = onSave, enabled = !threshold.saving, modifier = Modifier.testTag("monitoring_threshold_save")) {
                    Text(stringResource(if (threshold.saving) R.string.monitoring_threshold_saving else R.string.monitoring_threshold_save))
                }
            }
            // Aviso R-21 del backend cuando se conoce; el texto local equivalente mientras tanto.
            Text(
                settings?.disclaimer?.takeIf { it.isNotBlank() } ?: stringResource(R.string.monitoring_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("monitoring_disclaimer"),
            )
        }
    }
}

@Composable
private fun Notice(@StringRes text: Int, tag: String, color: Color = Color.Unspecified) {
    Text(stringResource(text), color = color, modifier = Modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Polite })
}

@StringRes
private fun connectionLabel(connection: MonitorConnection): Int = when (connection) {
    MonitorConnection.CONNECTING -> R.string.devices_connection_connecting
    MonitorConnection.CONNECTED -> R.string.devices_connection_connected
    MonitorConnection.DISCONNECTED -> R.string.devices_connection_disconnected
    MonitorConnection.MISSING_HEART_RATE_SERVICE, MonitorConnection.PERMISSION_REQUIRED, MonitorConnection.UNAVAILABLE ->
        R.string.devices_connection_unavailable
}

@StringRes
private fun settingsProblemMessage(problem: SettingsProblem): Int = when (problem) {
    SettingsProblem.OFFLINE -> R.string.monitoring_threshold_offline
    SettingsProblem.OUT_OF_RANGE -> R.string.monitoring_threshold_out_of_range
    SettingsProblem.CAPABILITY_REQUIRED -> R.string.monitoring_capability_required
    SettingsProblem.UNAVAILABLE, SettingsProblem.INVALID, SettingsProblem.SIGNED_OUT -> R.string.monitoring_threshold_unavailable
}
