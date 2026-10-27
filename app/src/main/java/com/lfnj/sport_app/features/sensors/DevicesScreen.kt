package com.lfnj.sport_app.features.sensors

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.lfnj.sport_app.R
import com.lfnj.sport_app.features.sensors.ble.BleAvailability
import com.lfnj.sport_app.features.sensors.ble.BluetoothPermissions
import com.lfnj.sport_app.features.sensors.model.DeviceRules
import com.lfnj.sport_app.features.sensors.model.DeviceStatus
import com.lfnj.sport_app.features.sensors.model.DiscoveredMonitor
import com.lfnj.sport_app.features.sensors.model.IngestionState
import com.lfnj.sport_app.features.sensors.model.LinkedDevice
import com.lfnj.sport_app.features.sensors.model.MonitorConnection

/**
 * «Dispositivos de medición» (HU037): permisos Bluetooth, búsqueda de monitores con el *Heart Rate
 * Service*, vinculación con nombre tras declarar qué se captura, estado de conexión y batería,
 * revocación y el estado de la ingestión de la sesión activa. Nunca muestra la dirección Bluetooth.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    state: DevicesUiState,
    onRequestPermissions: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onSearch: () -> Unit,
    onStopSearch: () -> Unit,
    onSelect: (DiscoveredMonitor) -> Unit,
    onNameChange: (String) -> Unit,
    onConfirmLink: () -> Unit,
    onCancelLink: () -> Unit,
    onAskRevoke: (LinkedDevice) -> Unit,
    onConfirmRevoke: () -> Unit,
    onCancelRevoke: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.devices_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("devices_back")) {
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
                .testTag("devices_screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.simulatedSource) {
                Notice(R.string.devices_simulated_source, "devices_simulated_source", MaterialTheme.colorScheme.tertiary)
            }
            state.notice?.let { notice ->
                when (notice) {
                    is DevicesNotice.Linked -> Notice(R.string.devices_notice_linked, "devices_notice_linked")
                    is DevicesNotice.Revoked -> Notice(R.string.devices_notice_revoked, "devices_notice_revoked")
                }
            }
            ConnectedSection(state)
            DevicesList(state, onAskRevoke, onRetry)
            state.revoking?.let { RevokeSection(it, onConfirmRevoke, onCancelRevoke) }
            val linking = state.linking
            if (linking != null) {
                LinkSection(linking, onNameChange, onConfirmLink, onCancelLink)
            } else if (state.connected == null) {
                SearchSection(state, onRequestPermissions, onOpenBluetoothSettings, onSearch, onStopSearch, onSelect)
            }
        }
    }
}

@Composable
private fun ConnectedSection(state: DevicesUiState) {
    val device = state.connected ?: return
    Card(Modifier.fillMaxWidth().testTag("devices_connected")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(device.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            val monitor = state.monitor
            val connection = monitor?.connection ?: MonitorConnection.CONNECTING
            Text(
                stringResource(connectionMessage(connection)),
                modifier = Modifier.testTag("devices_connection_state").semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(
                monitor?.batteryPercent?.let { stringResource(R.string.devices_battery, it) } ?: stringResource(R.string.devices_battery_unknown),
                modifier = Modifier.testTag("devices_battery"),
            )
            // No se presenta una lectura anterior como actual cuando se perdió la conexión.
            val reading = monitor?.lastReading?.takeIf { connection == MonitorConnection.CONNECTED }
            Text(
                reading?.let { stringResource(R.string.devices_heart_rate, it.bpm) } ?: stringResource(R.string.devices_heart_rate_none),
                modifier = Modifier.testTag("devices_heart_rate"),
            )
            IngestionLine(state)
        }
    }
}

@Composable
private fun IngestionLine(state: DevicesUiState) {
    val ingestion = state.ingestion
    val text = when (ingestion.state) {
        IngestionState.IDLE -> stringResource(R.string.devices_ingestion_idle)
        IngestionState.SENDING -> ingestion.lastBatch?.let {
            stringResource(R.string.devices_ingestion_last_batch, it.accepted, it.duplicates, it.rejected)
        } ?: stringResource(R.string.devices_ingestion_sending)
        IngestionState.PENDING_RETRY -> stringResource(R.string.devices_ingestion_pending, ingestion.pendingSamples)
        IngestionState.DEVICE_REVOKED -> stringResource(R.string.devices_ingestion_revoked)
        IngestionState.SESSION_FINISHED -> stringResource(R.string.devices_ingestion_session_finished)
        IngestionState.CAPABILITY_REQUIRED -> stringResource(R.string.devices_ingestion_capability)
        IngestionState.REJECTED -> stringResource(R.string.devices_ingestion_rejected)
    }
    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("devices_ingestion").semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun DevicesList(state: DevicesUiState, onAskRevoke: (LinkedDevice) -> Unit, onRetry: () -> Unit) {
    Text(stringResource(R.string.devices_list_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    if (state.refreshing) {
        val refreshing = stringResource(R.string.devices_refreshing)
        LinearProgressIndicator(Modifier.fillMaxWidth().testTag("devices_refreshing").semantics { contentDescription = refreshing })
    }
    state.listProblem?.let { problem ->
        Notice(listProblemMessage(problem), "devices_list_problem", MaterialTheme.colorScheme.error)
        if (problem != ListProblem.CAPABILITY_REQUIRED) {
            OutlinedButton(onClick = onRetry, enabled = !state.refreshing, modifier = Modifier.testTag("devices_retry")) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
    val devices = state.devices
    when {
        state.loading -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.testTag("devices_loading"))
            Text(stringResource(R.string.devices_loading))
        }
        devices.isNullOrEmpty() -> Notice(R.string.devices_empty, "devices_empty")
        else -> devices.forEach { device -> DeviceRow(device, state.revoking == null, onAskRevoke) }
    }
}

@Composable
private fun DeviceRow(device: LinkedDevice, canRevoke: Boolean, onAskRevoke: (LinkedDevice) -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("devices_item")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(device.displayName, style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(if (device.status == DeviceStatus.ACTIVE) R.string.devices_status_active else R.string.devices_status_revoked),
                modifier = Modifier.testTag("devices_item_status"),
            )
            if (device.active && !device.hasLocalAddress) {
                Text(stringResource(R.string.devices_linked_elsewhere), style = MaterialTheme.typography.bodySmall)
            }
            if (device.active) {
                TextButton(onClick = { onAskRevoke(device) }, enabled = canRevoke, modifier = Modifier.testTag("devices_revoke")) {
                    Text(stringResource(R.string.devices_revoke))
                }
            }
        }
    }
}

@Composable
private fun RevokeSection(draft: RevokeDraft, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("devices_revoke_section")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.devices_revoke_confirmation, draft.device.displayName))
            draft.problem?.let { Notice(revokeProblemMessage(it), "devices_revoke_problem", MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConfirm, enabled = !draft.sending, modifier = Modifier.testTag("devices_revoke_confirm")) {
                    Text(stringResource(R.string.devices_revoke_confirm))
                }
                OutlinedButton(onClick = onCancel, enabled = !draft.sending, modifier = Modifier.testTag("devices_revoke_cancel")) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    }
}

@Composable
private fun SearchSection(
    state: DevicesUiState,
    onRequestPermissions: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onSearch: () -> Unit,
    onStopSearch: () -> Unit,
    onSelect: (DiscoveredMonitor) -> Unit,
) {
    Text(stringResource(R.string.devices_add_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    Notice(R.string.devices_transparency, "devices_transparency")
    when (state.availability) {
        BleAvailability.UNSUPPORTED_ANDROID_VERSION -> Notice(R.string.devices_requires_android_12, "devices_unsupported_android", MaterialTheme.colorScheme.error)
        BleAvailability.NO_BLUETOOTH_LE -> Notice(R.string.devices_no_bluetooth_le, "devices_no_ble", MaterialTheme.colorScheme.error)
        BleAvailability.PERMISSION_REQUIRED -> {
            Notice(R.string.devices_permission_required, "devices_permission_required", MaterialTheme.colorScheme.error)
            Button(onClick = onRequestPermissions, modifier = Modifier.testTag("devices_grant_permissions")) {
                Text(stringResource(R.string.devices_grant_permissions))
            }
        }
        BleAvailability.BLUETOOTH_OFF -> {
            Notice(R.string.devices_bluetooth_off, "devices_bluetooth_off", MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onOpenBluetoothSettings, modifier = Modifier.testTag("devices_open_bluetooth")) {
                Text(stringResource(R.string.devices_open_bluetooth))
            }
        }
        BleAvailability.READY -> when (val scan = state.scan) {
            ScanState.Idle -> Button(onClick = onSearch, enabled = state.canSearch, modifier = Modifier.testTag("devices_search")) {
                Text(stringResource(R.string.devices_search))
            }
            is ScanState.Scanning -> {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.testTag("devices_scanning"))
                    Text(stringResource(R.string.devices_scanning), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                scan.found.forEach { FoundMonitor(it, onSelect) }
                OutlinedButton(onClick = onStopSearch, modifier = Modifier.testTag("devices_stop_search")) {
                    Text(stringResource(R.string.devices_stop_search))
                }
            }
            is ScanState.Finished -> {
                if (scan.found.isEmpty()) {
                    Notice(R.string.devices_none_compatible, "devices_none_compatible", MaterialTheme.colorScheme.error)
                } else {
                    scan.found.forEach { FoundMonitor(it, onSelect) }
                }
                Button(onClick = onSearch, enabled = state.canSearch, modifier = Modifier.testTag("devices_search")) {
                    Text(stringResource(R.string.devices_search_again))
                }
            }
        }
    }
}

@Composable
private fun FoundMonitor(monitor: DiscoveredMonitor, onSelect: (DiscoveredMonitor) -> Unit) {
    val name = monitor.advertisedName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.devices_unnamed_monitor)
    OutlinedButton(onClick = { onSelect(monitor) }, modifier = Modifier.fillMaxWidth().testTag("devices_found")) {
        Text(stringResource(R.string.devices_found_monitor, name))
    }
}

@Composable
private fun LinkSection(draft: LinkDraft, onNameChange: (String) -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val busy = draft.phase != LinkPhase.EDITING
    Card(Modifier.fillMaxWidth().testTag("devices_link_section")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.devices_link_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Notice(R.string.devices_transparency, "devices_link_transparency")
            OutlinedTextField(
                value = draft.name,
                onValueChange = onNameChange,
                label = { Text(stringResource(R.string.devices_name_label)) },
                supportingText = { Text(stringResource(R.string.devices_name_counter, draft.name.length, DeviceRules.MAX_NAME_LENGTH)) },
                singleLine = true,
                enabled = !busy,
                isError = draft.problem == LinkProblem.INVALID_NAME,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().testTag("devices_name_input"),
            )
            when (draft.phase) {
                LinkPhase.VERIFYING -> Notice(R.string.devices_verifying, "devices_verifying")
                LinkPhase.SENDING -> Notice(R.string.devices_linking, "devices_linking")
                LinkPhase.EDITING -> Unit
            }
            draft.problem?.let { Notice(linkProblemMessage(it), "devices_link_problem", MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConfirm, enabled = !busy, modifier = Modifier.testTag("devices_link_confirm")) {
                    Text(stringResource(R.string.devices_link_confirm))
                }
                OutlinedButton(onClick = onCancel, enabled = !busy, modifier = Modifier.testTag("devices_link_cancel")) {
                    Text(stringResource(R.string.action_cancel))
                }
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

private fun connectionMessage(connection: MonitorConnection): Int = when (connection) {
    MonitorConnection.CONNECTING -> R.string.devices_connection_connecting
    MonitorConnection.CONNECTED -> R.string.devices_connection_connected
    MonitorConnection.DISCONNECTED -> R.string.devices_connection_disconnected
    MonitorConnection.MISSING_HEART_RATE_SERVICE -> R.string.devices_link_missing_service
    MonitorConnection.PERMISSION_REQUIRED -> R.string.devices_permission_required
    MonitorConnection.UNAVAILABLE -> R.string.devices_connection_unavailable
}

private fun listProblemMessage(problem: ListProblem): Int = when (problem) {
    ListProblem.OFFLINE -> R.string.devices_offline
    ListProblem.UNAVAILABLE -> R.string.devices_error_unavailable
    ListProblem.CAPABILITY_REQUIRED -> R.string.devices_error_capability
    ListProblem.INVALID -> R.string.devices_error_unavailable
}

private fun linkProblemMessage(problem: LinkProblem): Int = when (problem) {
    LinkProblem.INVALID_NAME -> R.string.devices_link_invalid_name
    LinkProblem.MISSING_HEART_RATE_SERVICE -> R.string.devices_link_missing_service
    LinkProblem.PERMISSION_REQUIRED -> R.string.devices_permission_required
    LinkProblem.NOT_REACHABLE -> R.string.devices_link_not_reachable
    LinkProblem.OFFLINE -> R.string.devices_link_offline
    LinkProblem.UNKNOWN_OUTCOME -> R.string.devices_link_unknown_outcome
    LinkProblem.ALREADY_REGISTERED -> R.string.devices_link_already_registered
    LinkProblem.CAPABILITY_REQUIRED -> R.string.devices_error_capability
    LinkProblem.KEY_RENEWED -> R.string.devices_link_key_renewed
    LinkProblem.INVALID -> R.string.devices_error_unavailable
}

private fun revokeProblemMessage(problem: RevokeProblem): Int = when (problem) {
    RevokeProblem.OFFLINE -> R.string.devices_revoke_offline
    RevokeProblem.UNKNOWN_OUTCOME -> R.string.devices_revoke_unknown_outcome
    RevokeProblem.NOT_FOUND -> R.string.devices_revoke_not_found
    RevokeProblem.CAPABILITY_REQUIRED -> R.string.devices_error_capability
    RevokeProblem.INVALID -> R.string.devices_error_unavailable
}

/**
 * Solicita `BLUETOOTH_SCAN` y `BLUETOOTH_CONNECT` en el momento de buscar (03-agente-mobile §5). Si
 * ya se pidieron y siguen denegados, abre los ajustes de la aplicación; [onResult] vuelve a leer la
 * disponibilidad.
 */
@Composable
fun rememberBluetoothPermissionRequester(onResult: () -> Unit): () -> Unit {
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onResult() }
    return remember(context, launcher, asked) {
        {
            if (!BluetoothPermissions.supported) {
                onResult()
            } else if (!asked) {
                asked = true
                launcher.launch(BluetoothPermissions.required)
            } else {
                openSettings(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            }
        }
    }
}

/** Ajustes de Bluetooth del sistema para encenderlo. */
fun openBluetoothSettings(context: Context) = openSettings(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

private fun openSettings(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Sin pantalla de ajustes: la causa sigue explicada en la pantalla.
    }
}
