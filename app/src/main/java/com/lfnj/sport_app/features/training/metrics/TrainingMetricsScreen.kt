package com.lfnj.sport_app.features.training.metrics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lfnj.sport_app.R
import com.lfnj.sport_app.features.training.metrics.model.InputError
import com.lfnj.sport_app.features.training.metrics.model.MeasuredAtError
import com.lfnj.sport_app.features.training.metrics.model.MetricCatalog
import com.lfnj.sport_app.features.training.metrics.model.MetricFormat
import com.lfnj.sport_app.features.training.metrics.model.MetricInputDefinition
import com.lfnj.sport_app.features.training.metrics.model.MetricMethod
import com.lfnj.sport_app.features.training.metrics.model.MetricResult
import com.lfnj.sport_app.features.training.metrics.model.MetricSyncFailure
import com.lfnj.sport_app.features.training.metrics.model.MetricSyncState
import com.lfnj.sport_app.features.training.metrics.model.PendingCalculation
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Indicadores FTP y VO₂max (HU034, `/entrenamientos/metricas`). Formulario y tarjetas se construyen
 * con los metadatos del catálogo confirmado; todo resultado muestra método, versión, entrada,
 * unidad, fechas y el aviso de estimación no diagnóstica (CA6).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingMetricsScreen(
    state: TrainingMetricsUiState,
    onSelectMethod: (String) -> Unit,
    onInputChange: (String, String) -> Unit,
    onMeasuredAtChange: (Instant) -> Unit,
    onSubmit: () -> Unit,
    onConfirmSubmit: () -> Unit,
    onCancelSubmit: () -> Unit,
    onDismiss: (String) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.metrics_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("metrics_back")) {
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
                .testTag("training_metrics"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.loading) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.testTag("metrics_loading"))
                    Text(stringResource(R.string.metrics_loading))
                }
                return@Column
            }
            Notices(state, onRetry)
            CalculationCard(state, onSelectMethod, onInputChange, onMeasuredAtChange, onSubmit, zone)
            Pending(state, onDismiss, zone)
            Results(state, onLoadMore, zone)
        }
    }
    val disclaimerKey = state.selectedMethod?.disclaimerKey
    if (state.confirming && disclaimerKey != null) {
        AlertDialog(
            onDismissRequest = onCancelSubmit,
            title = { Text(stringResource(R.string.metrics_confirm_title)) },
            text = { Text(MetricLabels.text(disclaimerKey, disclaimerKey), modifier = Modifier.testTag("metrics_confirm_disclaimer")) },
            confirmButton = {
                TextButton(onClick = onConfirmSubmit, modifier = Modifier.testTag("metrics_confirm")) {
                    Text(stringResource(R.string.metrics_confirm_send))
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelSubmit, modifier = Modifier.testTag("metrics_confirm_cancel")) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun Notices(state: TrainingMetricsUiState, onRetry: () -> Unit) {
    val notice = when (state.notice) {
        MetricsNotice.NO_CONNECTION -> R.string.metrics_notice_no_connection
        MetricsNotice.UNAVAILABLE -> R.string.metrics_notice_unavailable
        MetricsNotice.CAPABILITY_REQUIRED -> R.string.metrics_notice_capability_required
        MetricsNotice.PERMISSION_REQUIRED -> R.string.metrics_notice_permission_required
        MetricsNotice.INVALID_RESPONSE -> R.string.metrics_notice_invalid_response
        MetricsNotice.STORAGE -> R.string.metrics_notice_storage
        null -> null
    }
    notice?.let {
        Notice(stringResource(it), tag = "metrics_notice")
        OutlinedButton(onClick = onRetry, modifier = Modifier.testTag("metrics_retry")) { Text(stringResource(R.string.action_retry)) }
    }
    val submit = when (state.submitNotice) {
        SubmitNotice.QUEUED -> R.string.metrics_submit_queued
        SubmitNotice.STORAGE -> R.string.metrics_submit_storage
        SubmitNotice.METHOD_UNAVAILABLE -> R.string.metrics_submit_method_unavailable
        null -> null
    }
    submit?.let { Notice(stringResource(it), tag = "metrics_submit_notice", isError = state.submitNotice != SubmitNotice.QUEUED) }
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
private fun CalculationCard(
    state: TrainingMetricsUiState,
    onSelectMethod: (String) -> Unit,
    onInputChange: (String, String) -> Unit,
    onMeasuredAtChange: (Instant) -> Unit,
    onSubmit: () -> Unit,
    zone: ZoneId,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.metrics_new_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            val catalog = state.catalog
            if (catalog == null) {
                // Sin catálogo confirmado no se ofrece ningún método ni se inventa un resultado (ADR-030 offline §2).
                Text(stringResource(R.string.metrics_catalog_missing), modifier = Modifier.testTag("metrics_catalog_missing"))
                return@Column
            }
            CatalogFreshness(catalog, state.catalogStale, zone)
            if (state.methods.isEmpty()) {
                Text(stringResource(R.string.metrics_catalog_empty), modifier = Modifier.testTag("metrics_catalog_empty"))
                return@Column
            }
            Text(stringResource(R.string.metrics_method_label))
            Column(Modifier.selectableGroup()) {
                state.methods.forEach { method ->
                    val selected = state.selectedMethodId == method.id
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = selected, enabled = !state.busy, role = Role.RadioButton, onClick = { onSelectMethod(method.id) })
                            .padding(vertical = 4.dp)
                            .testTag("metrics_method_${method.methodCode}"),
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(MetricLabels.text(method.labelKey, method.metricCode), style = MaterialTheme.typography.bodyLarge)
                            Text(methodText(method.methodLabelKey, method.methodCode, method.methodVersion), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            state.selectedMethod?.let { method ->
                method.inputs.forEachIndexed { index, definition ->
                    InputField(definition, state.inputs[definition.code].orEmpty(), state.inputErrors[definition.code], index == method.inputs.lastIndex, !state.busy) {
                        onInputChange(definition.code, it)
                    }
                }
                MeasuredAtField(state.measuredAt, state.measuredAtError, zone, enabled = !state.busy, onMeasuredAtChange)
                Button(onClick = onSubmit, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("metrics_submit")) {
                    Text(stringResource(if (state.busy) R.string.metrics_submitting else R.string.metrics_submit))
                }
            }
        }
    }
}

@Composable
private fun CatalogFreshness(catalog: MetricCatalog, stale: Boolean, zone: ZoneId) {
    val text = stringResource(
        if (stale) R.string.metrics_catalog_stale else R.string.metrics_catalog_synced,
        formatInstant(catalog.syncedAt, zone),
    )
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (stale) MaterialTheme.colorScheme.error else Color.Unspecified,
        modifier = Modifier.testTag(if (stale) "metrics_catalog_stale" else "metrics_catalog_synced"),
    )
}

/** Campo numérico: etiqueta y unidad se anuncian juntas; el rango del catálogo guía la captura. */
@Composable
private fun InputField(
    definition: MetricInputDefinition,
    value: String,
    error: InputError?,
    last: Boolean,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    val label = MetricLabels.text(definition.labelKey, definition.code)
    val labelWithUnit = stringResource(R.string.metrics_input_label, label, definition.unit)
    val range = stringResource(R.string.metrics_input_range, definition.minimum, definition.maximum, definition.unit)
    val errorText = when (error) {
        InputError.REQUIRED -> stringResource(R.string.metrics_input_required)
        InputError.NOT_INTEGER -> stringResource(R.string.metrics_input_not_integer)
        InputError.OUT_OF_RANGE -> stringResource(R.string.metrics_input_out_of_range, range)
        null -> null
    }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(labelWithUnit) },
        supportingText = { Text(errorText ?: range) },
        isError = error != null,
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = if (last) ImeAction.Done else ImeAction.Next),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("metrics_input_${definition.code}")
            .semantics { contentDescription = labelWithUnit },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeasuredAtField(measuredAt: Instant, error: MeasuredAtError?, zone: ZoneId, enabled: Boolean, onChange: (Instant) -> Unit) {
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }
    val local = measuredAt.atZone(zone)
    Text(stringResource(R.string.metrics_measured_at, formatInstant(measuredAt, zone)), modifier = Modifier.testTag("metrics_measured_at"))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { pickDate = true }, enabled = enabled, modifier = Modifier.testTag("metrics_pick_date")) {
            Text(stringResource(R.string.metrics_change_date))
        }
        OutlinedButton(onClick = { pickTime = true }, enabled = enabled, modifier = Modifier.testTag("metrics_pick_time")) {
            Text(stringResource(R.string.metrics_change_time))
        }
    }
    error?.let {
        val text = stringResource(if (it == MeasuredAtError.IN_FUTURE) R.string.metrics_measured_at_future else R.string.metrics_measured_at_too_old)
        Text(text, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("metrics_measured_at_error").semantics { liveRegion = LiveRegionMode.Polite })
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
                    val date: LocalDate = local.toLocalDate()
                    onChange(date.atTime(LocalTime.of(timeState.hour, timeState.minute)).atZone(zone).toInstant())
                    pickTime = false
                }) { Text(stringResource(R.string.action_accept)) }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text(stringResource(R.string.action_cancel)) } },
            text = { TimePicker(state = timeState) },
        )
    }
}

@Composable
private fun Pending(state: TrainingMetricsUiState, onDismiss: (String) -> Unit, zone: ZoneId) {
    if (state.pending.isEmpty()) return
    Text(stringResource(R.string.metrics_pending_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    state.pending.forEach { pending -> PendingCard(pending, state.catalog, state.busy, onDismiss, zone) }
}

@Composable
private fun PendingCard(pending: PendingCalculation, catalog: MetricCatalog?, busy: Boolean, onDismiss: (String) -> Unit, zone: ZoneId) {
    val request = pending.request
    val method = request?.let { r -> catalog?.methods?.firstOrNull { it.methodCode == r.methodCode && it.methodVersion == r.methodVersion } }
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .padding(16.dp)
                .testTag("metrics_pending_${pending.calculationId}"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (request == null) {
                Text(stringResource(R.string.metrics_pending_unreadable), style = MaterialTheme.typography.titleSmall)
            } else {
                Text(MetricLabels.text(method?.labelKey.orEmpty(), request.metricCode), style = MaterialTheme.typography.titleSmall)
                Text(methodText(method?.methodLabelKey.orEmpty(), request.methodCode, request.methodVersion), style = MaterialTheme.typography.bodySmall)
                request.inputs.forEach { input ->
                    val definition = method?.inputs?.firstOrNull { it.code == input.code }
                    Text(inputText(definition?.labelKey.orEmpty(), input.code, input.value, input.unit))
                }
                Text(stringResource(R.string.metrics_measured_at, formatInstant(request.measuredAt, zone)))
            }
            // Nunca se muestra un valor estimado: el resultado solo lo calcula el backend (CA8).
            val (label, color) = when (pending.state) {
                MetricSyncState.PENDING_SYNC -> R.string.metrics_state_pending to MaterialTheme.colorScheme.onSurfaceVariant
                MetricSyncState.SYNC_FAILED -> R.string.metrics_state_failed to MaterialTheme.colorScheme.error
            }
            Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                Text(stringResource(label), color = color, style = MaterialTheme.typography.labelLarge)
                if (pending.state == MetricSyncState.SYNC_FAILED) {
                    Text(failureText(pending, method), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("metrics_failure_${pending.calculationId}"))
                }
            }
            if (pending.state == MetricSyncState.SYNC_FAILED) {
                OutlinedButton(onClick = { onDismiss(pending.calculationId) }, enabled = !busy, modifier = Modifier.testTag("metrics_dismiss_${pending.calculationId}")) {
                    Text(stringResource(R.string.metrics_dismiss))
                }
            }
        }
    }
}

@Composable
private fun failureText(pending: PendingCalculation, method: MetricMethod?): String = when (pending.failure) {
    MetricSyncFailure.VALIDATION -> {
        val field = pending.failureField
        val inputIndex = field?.let { INPUT_FIELD.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val input = inputIndex?.let { pending.request?.inputs?.getOrNull(it) }
        val fieldName = when {
            input != null -> MetricLabels.text(method?.inputs?.firstOrNull { it.code == input.code }?.labelKey.orEmpty(), input.code)
            field == "measured_at" -> stringResource(R.string.metrics_field_measured_at)
            field != null -> field
            else -> null
        }
        val cause = issueText(pending.failureIssue)
        if (fieldName != null) stringResource(R.string.metrics_failure_validation_field, fieldName, cause) else cause
    }
    MetricSyncFailure.CAPABILITY_REQUIRED -> stringResource(R.string.metrics_failure_capability_required)
    MetricSyncFailure.PERMISSION_REQUIRED -> stringResource(R.string.metrics_failure_permission_required)
    MetricSyncFailure.CONFLICT -> stringResource(R.string.metrics_failure_conflict)
    MetricSyncFailure.EXPIRED -> stringResource(R.string.metrics_failure_expired)
    MetricSyncFailure.UNREADABLE -> stringResource(R.string.metrics_failure_unreadable)
    MetricSyncFailure.REJECTED, MetricSyncFailure.INVALID_RESPONSE, null -> stringResource(R.string.metrics_failure_rejected)
}

@Composable
private fun issueText(issue: String?): String = stringResource(
    when (issue) {
        "required" -> R.string.metrics_issue_required
        "out_of_range" -> R.string.metrics_issue_out_of_range
        "invalid_unit" -> R.string.metrics_issue_invalid_unit
        "too_far_in_future" -> R.string.metrics_issue_too_far_in_future
        "too_old" -> R.string.metrics_issue_too_old
        "unknown_metric", "unsupported_method_version", "unknown_input", "duplicate_input" -> R.string.metrics_issue_method_changed
        else -> R.string.metrics_issue_other
    }
)

@Composable
private fun Results(state: TrainingMetricsUiState, onLoadMore: () -> Unit, zone: ZoneId) {
    Text(stringResource(R.string.metrics_results_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    state.resultsSyncedAt?.let {
        Text(stringResource(R.string.metrics_results_synced, formatInstant(it, zone)), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("metrics_results_synced"))
    }
    if (state.results.isEmpty()) {
        Text(stringResource(R.string.metrics_results_empty), modifier = Modifier.testTag("metrics_results_empty"))
        return
    }
    state.results.forEach { result -> ResultCard(result, state.catalog, zone) }
    if (state.nextCursor != null) {
        OutlinedButton(onClick = onLoadMore, enabled = !state.loadingMore, modifier = Modifier.testTag("metrics_load_more")) {
            Text(stringResource(if (state.loadingMore) R.string.metrics_loading_more else R.string.metrics_load_more))
        }
    }
}

/** Resultado confirmado: siempre «estimado», con método, versión, entrada, unidad, fechas y aviso (CA6). */
@Composable
private fun ResultCard(result: MetricResult, catalog: MetricCatalog?, zone: ZoneId) {
    val method = catalog?.methods?.firstOrNull { it.methodCode == result.methodCode && it.methodVersion == result.methodVersion }
    val label = MetricLabels.text(method?.labelKey.orEmpty(), result.metricCode)
    val value = stringResource(R.string.metrics_result_value, MetricFormat.value(result.value, result.scale), result.unit)
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .padding(16.dp)
                .testTag("metrics_result_${result.calculationId}"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier
                    .testTag("metrics_result_value_${result.calculationId}")
                    .semantics { contentDescription = "$label: $value" },
            )
            Text(methodText(method?.methodLabelKey.orEmpty(), result.methodCode, result.methodVersion), style = MaterialTheme.typography.bodySmall)
            result.inputs.forEach { input ->
                val definition = method?.inputs?.firstOrNull { it.code == input.code }
                Text(inputText(definition?.labelKey.orEmpty(), input.code, input.value, input.unit))
            }
            Text(stringResource(R.string.metrics_measured_at, formatInstant(result.measuredAt, zone)))
            Text(stringResource(R.string.metrics_calculated_at, formatInstant(result.calculatedAt, zone)), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.metrics_state_synced), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelLarge)
            Text(
                MetricLabels.text(result.disclaimerKey, result.disclaimerKey),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("metrics_disclaimer_${result.calculationId}"),
            )
        }
    }
}

@Composable
private fun methodText(labelKey: String, code: String, version: Int): String =
    stringResource(R.string.metrics_method_version, MetricLabels.text(labelKey, code), version)

@Composable
private fun inputText(labelKey: String, code: String, value: Long, unit: String): String =
    stringResource(R.string.metrics_input_value, MetricLabels.text(labelKey, code), MetricFormat.value(BigDecimal.valueOf(value), 0), unit)

private val INPUT_FIELD = Regex("""^inputs\[(\d+)]""")

/** Fecha y hora en la zona y el formato regional del dispositivo; el dato fuente sigue en UTC. */
private fun formatInstant(instant: Instant, zone: ZoneId): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(zone).format(instant)
