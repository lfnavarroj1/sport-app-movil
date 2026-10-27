package com.lfnj.sport_app.features.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lfnj.sport_app.R
import com.lfnj.sport_app.features.health.model.BodyArea
import com.lfnj.sport_app.features.health.model.HealthReportType
import com.lfnj.sport_app.features.health.model.HealthSeverity
import com.lfnj.sport_app.features.health.model.HealthSyncFailure
import com.lfnj.sport_app.features.health.model.HealthSyncState
import com.lfnj.sport_app.features.health.model.LocalHealthReport
import com.lfnj.sport_app.features.health.model.OccurredAtError
import com.lfnj.sport_app.features.health.model.RecoveryDaysError
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * «Mi salud» (HU042, destino `health`): formulario con catálogos cerrados, consentimiento y estado
 * de sincronización de cada reporte guardado en el dispositivo. No diagnostica ni recomienda; con
 * severidad alta muestra el texto de consulta profesional (CA6).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthReportScreen(
    state: HealthReportUiState,
    onSelectType: (HealthReportType) -> Unit,
    onSelectBodyArea: (BodyArea) -> Unit,
    onSelectSeverity: (HealthSeverity) -> Unit,
    onOccurredAtChange: (Instant) -> Unit,
    onSelectSession: (String?) -> Unit,
    onRecoveryDaysChange: (String) -> Unit,
    onConsentChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.health_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("health_back")) {
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
                .testTag("health_report"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.loading) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.testTag("health_loading"))
                    Text(stringResource(R.string.health_loading))
                }
                return@Column
            }
            Text(stringResource(R.string.health_intro), style = MaterialTheme.typography.bodyMedium)
            if (!state.online) Notice(stringResource(R.string.health_offline), tag = "health_offline")
            if (state.storageProblem) Notice(stringResource(R.string.health_storage_problem), tag = "health_storage_problem", isError = true)
            SubmitNotice(state)
            ReportForm(state, onSelectType, onSelectBodyArea, onSelectSeverity, onOccurredAtChange, onSelectSession, onRecoveryDaysChange, onConsentChange, onSubmit, zone)
            Reports(state, onDismiss, zone)
        }
    }
}

@Composable
private fun Notice(text: String, tag: String, isError: Boolean = false) {
    Text(
        text,
        color = if (isError) MaterialTheme.colorScheme.error else Color.Unspecified,
        modifier = Modifier
            .testTag(tag)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun SubmitNotice(state: HealthReportUiState) {
    val text = when (state.submitNotice) {
        // Encolado no es confirmado: el estado real lo muestra la tarjeta del reporte (CA5).
        HealthSubmitNotice.QUEUED -> R.string.health_submit_queued
        HealthSubmitNotice.STORAGE -> R.string.health_submit_storage
        HealthSubmitNotice.CONSENT_UNAVAILABLE -> R.string.health_consent_unavailable
        null -> null
    }
    text?.let { Notice(stringResource(it), tag = "health_submit_notice", isError = state.submitNotice != HealthSubmitNotice.QUEUED) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportForm(
    state: HealthReportUiState,
    onSelectType: (HealthReportType) -> Unit,
    onSelectBodyArea: (BodyArea) -> Unit,
    onSelectSeverity: (HealthSeverity) -> Unit,
    onOccurredAtChange: (Instant) -> Unit,
    onSelectSession: (String?) -> Unit,
    onRecoveryDaysChange: (String) -> Unit,
    onConsentChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    zone: ZoneId,
) {
    val enabled = !state.busy && state.consentAvailable
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.health_new_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            if (!state.consentAvailable) {
                // Sin versión de consentimiento configurada no se captura ningún dato de salud.
                Notice(stringResource(R.string.health_consent_unavailable), tag = "health_consent_unavailable", isError = true)
            }

            SectionLabel(R.string.health_type_label, missing = HealthField.TYPE in state.missing, tag = "health_type_missing")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HealthReportType.entries.forEach { type ->
                    Choice(stringResource(type.label), state.type == type, enabled, "health_type_${type.wire}") { onSelectType(type) }
                }
            }

            SectionLabel(R.string.health_area_label, missing = HealthField.BODY_AREA in state.missing, tag = "health_area_missing")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BodyArea.entries.forEach { area ->
                    Choice(stringResource(area.label), state.bodyArea == area, enabled, "health_area_${area.wire}") { onSelectBodyArea(area) }
                }
            }

            SectionLabel(R.string.health_severity_label, missing = HealthField.SEVERITY in state.missing, tag = "health_severity_missing")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HealthSeverity.entries.forEach { severity ->
                    Choice(stringResource(severity.label), state.severity == severity, enabled, "health_severity_${severity.wire}") { onSelectSeverity(severity) }
                }
            }
            if (state.showsProfessionalAdvice) ProfessionalAdvice(tag = "health_professional_advice")

            OccurredAtField(state.occurredAt, state.occurredAtError, zone, enabled, onOccurredAtChange)

            Text(stringResource(R.string.health_session_label))
            if (state.sessions.isEmpty()) {
                Text(stringResource(R.string.health_session_none), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("health_session_none"))
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice(stringResource(R.string.health_session_without), state.sessionId == null, enabled, "health_session_without") { onSelectSession(null) }
                    state.sessions.forEach { session ->
                        Choice(
                            stringResource(R.string.health_session_option, formatInstant(session.startedAt, zone)),
                            state.sessionId == session.sessionId,
                            enabled,
                            "health_session_${session.sessionId}",
                        ) { onSelectSession(session.sessionId) }
                    }
                }
            }

            RecoveryDaysField(state.recoveryDays, state.recoveryDaysError, enabled, onRecoveryDaysChange)

            ConsentField(state, enabled, onConsentChange)

            Button(onClick = onSubmit, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("health_submit")) {
                Text(stringResource(if (state.busy) R.string.health_submitting else R.string.health_submit))
            }
        }
    }
}

@Composable
private fun SectionLabel(label: Int, missing: Boolean, tag: String) {
    Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
    if (missing) Notice(stringResource(R.string.health_field_required), tag = tag, isError = true)
}

@Composable
private fun Choice(label: String, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        modifier = Modifier.testTag(tag),
    )
}

/** Texto de consulta profesional para severidad alta (ADR-037 HU042 §5), aprobado por el responsable el 2026-10-26. */
@Composable
private fun ProfessionalAdvice(tag: String) {
    Text(
        stringResource(R.string.health_high_severity_advice),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .testTag(tag)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OccurredAtField(occurredAt: Instant, error: OccurredAtError?, zone: ZoneId, enabled: Boolean, onChange: (Instant) -> Unit) {
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }
    val local = occurredAt.atZone(zone)
    Text(stringResource(R.string.health_occurred_at, formatInstant(occurredAt, zone)), modifier = Modifier.testTag("health_occurred_at"))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { pickDate = true }, enabled = enabled, modifier = Modifier.testTag("health_pick_date")) {
            Text(stringResource(R.string.health_change_date))
        }
        OutlinedButton(onClick = { pickTime = true }, enabled = enabled, modifier = Modifier.testTag("health_pick_time")) {
            Text(stringResource(R.string.health_change_time))
        }
    }
    error?.let {
        val text = stringResource(if (it == OccurredAtError.IN_FUTURE) R.string.health_occurred_at_future else R.string.health_occurred_at_too_old)
        Notice(text, tag = "health_occurred_at_error", isError = true)
    }
    if (pickDate) {
        // El selector trabaja en milisegundos UTC de la medianoche del día elegido.
        val dateState = rememberDatePickerState(initialSelectedDateMillis = local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        onChange(date.atTime(local.toLocalTime()).atZone(zone).toInstant())
                    }
                    pickDate = false
                }) { Text(stringResource(R.string.action_accept)) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state = dateState) }
    }
    if (pickTime) {
        val timeState = rememberTimePickerState(initialHour = local.hour, initialMinute = local.minute)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = {
                TextButton(onClick = {
                    onChange(local.toLocalDate().atTime(LocalTime.of(timeState.hour, timeState.minute)).atZone(zone).toInstant())
                    pickTime = false
                }) { Text(stringResource(R.string.action_accept)) }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text(stringResource(R.string.action_cancel)) } },
            text = { TimePicker(state = timeState) },
        )
    }
}

@Composable
private fun RecoveryDaysField(value: String, error: RecoveryDaysError?, enabled: Boolean, onChange: (String) -> Unit) {
    val hint = stringResource(R.string.health_recovery_hint)
    val errorText = when (error) {
        RecoveryDaysError.NOT_INTEGER, RecoveryDaysError.OUT_OF_RANGE -> stringResource(R.string.health_recovery_out_of_range)
        null -> null
    }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.health_recovery_label)) },
        supportingText = { Text(errorText ?: hint, modifier = if (errorText != null) Modifier.testTag("health_recovery_error") else Modifier) },
        isError = error != null,
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("health_recovery_days"),
    )
}

@Composable
private fun ConsentField(state: HealthReportUiState, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = state.consentAccepted, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .testTag("health_consent"),
    ) {
        Checkbox(checked = state.consentAccepted, onCheckedChange = null, enabled = enabled)
        Text(stringResource(R.string.health_consent_text), modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
    }
    if (HealthField.CONSENT in state.missing) Notice(stringResource(R.string.health_consent_required), tag = "health_consent_missing", isError = true)
}

@Composable
private fun Reports(state: HealthReportUiState, onDismiss: (String) -> Unit, zone: ZoneId) {
    Text(stringResource(R.string.health_reports_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    Text(stringResource(R.string.health_reports_local_only), style = MaterialTheme.typography.bodySmall)
    if (state.reports.isEmpty()) {
        Text(stringResource(R.string.health_reports_empty), modifier = Modifier.testTag("health_reports_empty"))
        return
    }
    state.reports.forEach { report -> ReportCard(report, state.busy, onDismiss, zone) }
}

@Composable
private fun ReportCard(report: LocalHealthReport, busy: Boolean, onDismiss: (String) -> Unit, zone: ZoneId) {
    val details = report.details
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .padding(16.dp)
                .testTag("health_report_${report.reportId}"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (details == null) {
                Text(stringResource(R.string.health_report_unreadable), style = MaterialTheme.typography.titleSmall)
            } else {
                Text(
                    stringResource(R.string.health_report_summary, stringResource(details.type.label), stringResource(details.bodyArea.label)),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(stringResource(R.string.health_report_severity, stringResource(details.severity.label)))
                Text(stringResource(R.string.health_occurred_at, formatInstant(details.occurredAt, zone)))
                details.expectedRecoveryDays?.let { Text(stringResource(R.string.health_report_recovery, it)) }
                if (details.sessionId != null) Text(stringResource(R.string.health_report_with_session), style = MaterialTheme.typography.bodySmall)
                if (details.severity.suggestsProfessional) ProfessionalAdvice(tag = "health_report_advice_${report.reportId}")
            }
            val (label, color) = when (report.state) {
                HealthSyncState.PENDING_SYNC -> R.string.health_state_pending to MaterialTheme.colorScheme.onSurfaceVariant
                HealthSyncState.SYNCED -> R.string.health_state_synced to MaterialTheme.colorScheme.tertiary
                HealthSyncState.SYNC_FAILED -> R.string.health_state_failed to MaterialTheme.colorScheme.error
            }
            Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                Text(stringResource(label), color = color, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("health_state_${report.reportId}"))
                report.reportedAt?.let { Text(stringResource(R.string.health_reported_at, formatInstant(it, zone)), style = MaterialTheme.typography.bodySmall) }
                if (report.state == HealthSyncState.SYNC_FAILED) {
                    Text(failureText(report), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("health_failure_${report.reportId}"))
                }
            }
            if (report.dismissible) {
                OutlinedButton(onClick = { onDismiss(report.reportId) }, enabled = !busy, modifier = Modifier.testTag("health_dismiss_${report.reportId}")) {
                    Text(stringResource(R.string.health_dismiss))
                }
            }
        }
    }
}

@Composable
private fun failureText(report: LocalHealthReport): String = stringResource(
    when (report.failure) {
        HealthSyncFailure.VALIDATION -> R.string.health_failure_validation
        HealthSyncFailure.CONSENT_VERSION -> R.string.health_failure_consent
        HealthSyncFailure.SESSION_NOT_FOUND -> R.string.health_failure_session
        HealthSyncFailure.PERMISSION_REQUIRED -> R.string.health_failure_permission
        HealthSyncFailure.CONFLICT -> R.string.health_failure_conflict
        HealthSyncFailure.EXPIRED -> R.string.health_failure_expired
        HealthSyncFailure.UNREADABLE -> R.string.health_failure_unreadable
        HealthSyncFailure.REJECTED, HealthSyncFailure.INVALID_RESPONSE, null -> R.string.health_failure_rejected
    }
)

/** Fecha y hora en la zona y el formato regional del dispositivo; el dato fuente sigue en UTC. */
private fun formatInstant(instant: Instant, zone: ZoneId): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(zone).format(instant)
