package com.lfnj.sport_app.features.training.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lfnj.sport_app.R
import com.lfnj.sport_app.features.training.dashboard.model.CustomRangeError
import com.lfnj.sport_app.features.training.dashboard.model.DashboardBar
import com.lfnj.sport_app.features.training.dashboard.model.DashboardBars
import com.lfnj.sport_app.features.training.dashboard.model.DashboardRange
import com.lfnj.sport_app.features.training.dashboard.model.DashboardSnapshot
import com.lfnj.sport_app.features.training.dashboard.model.LatestMetric
import com.lfnj.sport_app.features.training.metrics.MetricLabels
import com.lfnj.sport_app.features.training.metrics.model.MetricFormat
import com.lfnj.sport_app.features.training.session.model.TrainingSport
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Tablero de entrenamientos del deportista (HU033, `/entrenamientos/tablero`): totales, barras por
 * semana y por deporte con su alternativa textual, origen de los datos y últimos indicadores del
 * periodo elegido, con la antigüedad del último tablero confirmado y un estado vacío honesto.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingDashboardScreen(
    state: TrainingDashboardUiState,
    onSelectRange: (DashboardRange) -> Unit,
    onSelectCustomRange: (LocalDate, LocalDate) -> Unit,
    onDismissCustomError: () -> Unit,
    onRetry: () -> Unit,
    onRegisterTraining: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dashboard_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("dashboard_back")) {
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
                .testTag("dashboard_screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RangeSelector(state.range) { range ->
                onSelectRange(range)
                if (range == DashboardRange.CUSTOM) pickerOpen = true
            }
            if (state.range == DashboardRange.CUSTOM) {
                OutlinedButton(onClick = { pickerOpen = true }, modifier = Modifier.testTag("dashboard_choose_dates")) {
                    Text(stringResource(R.string.dashboard_choose_dates))
                }
            }
            PeriodLabel(state)
            val snapshot = state.snapshot
            when {
                state.awaitingCustomRange -> Text(stringResource(R.string.dashboard_custom_prompt), Modifier.testTag("dashboard_custom_prompt"))
                state.loading -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.testTag("dashboard_loading"))
                    Text(stringResource(R.string.dashboard_loading))
                }
                snapshot == null -> WithoutSnapshot(state, onRetry)
                else -> {
                    if (state.refreshing) {
                        val refreshing = stringResource(R.string.dashboard_refreshing)
                        LinearProgressIndicator(
                            Modifier
                                .fillMaxWidth()
                                .testTag("dashboard_refreshing")
                                .semantics { contentDescription = refreshing },
                        )
                    }
                    SnapshotStatus(state, snapshot, onRetry)
                    Dashboard(snapshot, state.zone, onRegisterTraining)
                }
            }
        }
    }
    if (pickerOpen) {
        CustomRangeDialog(state, onDismiss = { pickerOpen = false }) { start, end ->
            pickerOpen = false
            onSelectCustomRange(start, end)
        }
    }
    state.customError?.let { error ->
        AlertDialog(
            onDismissRequest = onDismissCustomError,
            confirmButton = { TextButton(onClick = onDismissCustomError) { Text(stringResource(R.string.action_accept)) } },
            text = {
                Text(
                    stringResource(
                        when (error) {
                            CustomRangeError.END_BEFORE_START -> R.string.dashboard_custom_error_end_before_start
                            CustomRangeError.TOO_LONG -> R.string.dashboard_custom_error_too_long
                        }
                    ),
                    modifier = Modifier.testTag("dashboard_custom_error"),
                )
            },
        )
    }
}

@Composable
private fun RangeSelector(selected: DashboardRange, onSelect: (DashboardRange) -> Unit) {
    Text(stringResource(R.string.dashboard_range_label), style = MaterialTheme.typography.labelLarge)
    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            DashboardRange.LAST_7 to R.string.dashboard_range_7,
            DashboardRange.LAST_30 to R.string.dashboard_range_30,
            DashboardRange.LAST_90 to R.string.dashboard_range_90,
            DashboardRange.CUSTOM to R.string.dashboard_range_custom,
        ).forEach { (range, label) ->
            FilterChip(
                selected = selected == range,
                onClick = { onSelect(range) },
                label = { Text(stringResource(label)) },
                modifier = Modifier.testTag("dashboard_range_${range.name.lowercase()}"),
            )
        }
    }
}

/** Fechas del tablero mostrado; si aún no hay uno, las del periodo consultado. */
@Composable
private fun PeriodLabel(state: TrainingDashboardUiState) {
    val locale = currentLocale()
    val (first, last) = state.snapshot?.let { it.firstDay(state.zone) to it.lastDay(state.zone) }
        ?: state.period?.let { it.from.toLocalDate() to it.to.toLocalDate().minusDays(1) }
        ?: return
    Text(
        stringResource(R.string.dashboard_period, DashboardFormat.date(first, locale), DashboardFormat.date(last, locale)),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .testTag("dashboard_period")
            .semantics { heading(); liveRegion = LiveRegionMode.Polite },
    )
}

/** Sin tablero confirmado para el periodo: sin red, error o acceso rechazado. */
@Composable
private fun WithoutSnapshot(state: TrainingDashboardUiState, onRetry: () -> Unit) {
    if (state.neverSynced) {
        Notice(R.string.dashboard_never_synced, "dashboard_never_synced")
    } else {
        Notice(errorMessage(state.problem), "dashboard_error", MaterialTheme.colorScheme.error)
    }
    Button(onClick = onRetry, enabled = !state.refreshing, modifier = Modifier.testTag("dashboard_retry")) {
        Text(stringResource(R.string.action_retry))
    }
}

private fun errorMessage(problem: DashboardProblem?): Int = when (problem) {
    DashboardProblem.CAPABILITY_REQUIRED -> R.string.dashboard_error_capability_required
    DashboardProblem.PERMISSION_REQUIRED -> R.string.dashboard_error_permission_required
    DashboardProblem.INVALID_PERIOD -> R.string.dashboard_error_invalid_period
    DashboardProblem.INVALID_RESPONSE -> R.string.dashboard_error_invalid_response
    DashboardProblem.STORAGE -> R.string.dashboard_error_storage
    DashboardProblem.NO_CONNECTION, DashboardProblem.UNAVAILABLE, null -> R.string.dashboard_error_unavailable
}

/** Antigüedad, conectividad y consultas internas incompletas del tablero mostrado. */
@Composable
private fun SnapshotStatus(state: TrainingDashboardUiState, snapshot: DashboardSnapshot, onRetry: () -> Unit) {
    Text(
        stringResource(R.string.dashboard_synced_at, DashboardFormat.dateTime(snapshot.syncedAt, state.zone, currentLocale())),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("dashboard_synced_at"),
    )
    if (state.stale) Notice(R.string.dashboard_stale, "dashboard_stale", MaterialTheme.colorScheme.error)
    when (state.problem) {
        null -> Unit
        DashboardProblem.NO_CONNECTION -> Notice(R.string.dashboard_offline_with_cache, "dashboard_problem")
        else -> {
            Notice(R.string.dashboard_unavailable_with_cache, "dashboard_problem")
            OutlinedButton(onClick = onRetry, enabled = !state.refreshing, modifier = Modifier.testTag("dashboard_retry")) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
    snapshot.incompleteSources.forEach { name ->
        Text(
            stringResource(
                when (name) {
                    DashboardSnapshot.SOURCE_SESSIONS -> R.string.dashboard_incomplete_sessions
                    DashboardSnapshot.SOURCE_METRICS -> R.string.dashboard_incomplete_metrics
                    else -> R.string.dashboard_incomplete_other
                }
            ),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .testTag("dashboard_incomplete_$name")
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
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

// ------------------------------------------------------------------ contenido

@Composable
private fun Dashboard(snapshot: DashboardSnapshot, zone: ZoneId, onRegisterTraining: () -> Unit) {
    if (snapshot.confirmedEmpty) {
        // CA2: estado vacío explícito, sin gráficos ni valores inventados.
        Column(Modifier.testTag("dashboard_empty"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dashboard_empty))
            Button(onClick = onRegisterTraining, modifier = Modifier.testTag("dashboard_register_training")) {
                Text(stringResource(R.string.dashboard_register_training))
            }
        }
        return
    }
    // Lo que depende de una consulta que no respondió no se muestra: nunca se presentan ceros como confirmados.
    val incomplete = snapshot.incompleteSources
    if (DashboardSnapshot.SOURCE_SESSIONS !in incomplete) {
        Totals(snapshot)
        WeekChart(snapshot)
        SportChart(snapshot)
        Sources(snapshot)
    }
    if (DashboardSnapshot.SOURCE_METRICS !in incomplete) Metrics(snapshot.latestMetrics, zone)
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
}

@Composable
private fun Totals(snapshot: DashboardSnapshot) {
    val locale = currentLocale()
    val totals = snapshot.totals
    Card(Modifier.fillMaxWidth().testTag("dashboard_totals")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.dashboard_totals_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.dashboard_total_sessions, DashboardFormat.number(totals.sessionsCount, locale)), Modifier.testTag("dashboard_total_sessions"))
            Text(stringResource(R.string.dashboard_total_duration, DashboardFormat.number(totals.totalDurationMinutes, locale)), Modifier.testTag("dashboard_total_duration"))
            Text(stringResource(R.string.dashboard_avg_duration, DashboardFormat.number(totals.avgDurationMinutes, locale)), Modifier.testTag("dashboard_avg_duration"))
            val distance = totals.distanceMeters
            if (distance == null) {
                Text(stringResource(R.string.dashboard_distance_unavailable), Modifier.testTag("dashboard_distance_unavailable"))
            } else {
                Text(stringResource(R.string.dashboard_total_distance, DashboardFormat.number(distance, locale)), Modifier.testTag("dashboard_total_distance"))
                Text(stringResource(R.string.dashboard_distance_note), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun WeekChart(snapshot: DashboardSnapshot) {
    val locale = currentLocale()
    SectionTitle(stringResource(R.string.dashboard_by_week_title))
    val bars = DashboardBars.weeks(snapshot.byWeek)
    if (bars.isEmpty()) {
        Text(stringResource(R.string.dashboard_no_series), Modifier.testTag("dashboard_weeks_empty"))
        return
    }
    Column(Modifier.testTag("dashboard_weeks"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        bars.forEach { bar ->
            val week = bar.item
            BarRow(
                bar = bar,
                label = stringResource(R.string.dashboard_week_label, DashboardFormat.date(week.weekStart, locale)),
                sessions = week.sessionsCount,
                tag = "dashboard_week_${week.weekStart}",
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SportChart(snapshot: DashboardSnapshot) {
    val locale = currentLocale()
    SectionTitle(stringResource(R.string.dashboard_by_sport_title))
    val bars = DashboardBars.sports(snapshot.bySport)
    if (bars.isEmpty()) {
        Text(stringResource(R.string.dashboard_no_series), Modifier.testTag("dashboard_sports_empty"))
        return
    }
    Column(Modifier.testTag("dashboard_sports"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        bars.forEach { bar ->
            val sport = bar.item
            val label = TrainingSport.fromWire(sport.sport)?.let { stringResource(it.label) } ?: sport.sport
            BarRow(
                bar = bar,
                label = label,
                sessions = sport.sessionsCount,
                tag = "dashboard_sport_${sport.sport}",
                color = MaterialTheme.colorScheme.tertiary,
                extra = sport.distanceMeters?.let { stringResource(R.string.dashboard_sport_distance, DashboardFormat.number(it, locale)) },
            )
        }
    }
}

/**
 * Fila de un gráfico de barras. La barra es decorativa: lectores de pantalla y la propia fila
 * reciben el valor como texto (alternativa textual accesible, ADR-043 §5).
 */
@Composable
private fun <T> BarRow(bar: DashboardBar<T>, label: String, sessions: Long, tag: String, color: Color, extra: String? = null) {
    val locale = currentLocale()
    val minutes = DashboardFormat.number(bar.value, locale)
    val sessionsText = pluralStringResource(R.plurals.dashboard_sessions, sessions.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), DashboardFormat.number(sessions, locale))
    val value = stringResource(R.string.dashboard_bar_value, minutes, sessionsText)
    val description = stringResource(R.string.dashboard_bar_description, label, minutes, sessionsText) + (extra?.let { ", $it" } ?: "")
    Column(
        Modifier
            .fillMaxWidth()
            .testTag(tag)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(listOfNotNull(value, extra).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(12.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
        ) {
            if (bar.fraction > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(bar.fraction)
                        .fillMaxHeight()
                        .background(color, RoundedCornerShape(6.dp))
                )
            }
        }
    }
}

@Composable
private fun Sources(snapshot: DashboardSnapshot) {
    val locale = currentLocale()
    SectionTitle(stringResource(R.string.dashboard_by_source_title))
    Column(Modifier.testTag("dashboard_sources"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            Triple(R.string.dashboard_source_sportapp, snapshot.bySource.sportApp, "dashboard_source_sportapp"),
            Triple(R.string.dashboard_source_strava, snapshot.bySource.strava, "dashboard_source_strava"),
        ).forEach { (label, total, tag) ->
            val sessions = pluralStringResource(
                R.plurals.dashboard_sessions,
                total.sessionsCount.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                DashboardFormat.number(total.sessionsCount, locale),
            )
            Text(
                "${stringResource(label)}: ${stringResource(R.string.dashboard_bar_value, DashboardFormat.number(total.totalDurationMinutes, locale), sessions)}",
                modifier = Modifier.testTag(tag),
            )
        }
    }
}

@Composable
private fun Metrics(metrics: List<LatestMetric>, zone: ZoneId) {
    SectionTitle(stringResource(R.string.dashboard_metrics_title))
    if (metrics.isEmpty()) {
        Text(stringResource(R.string.dashboard_metrics_empty), Modifier.testTag("dashboard_metrics_empty"))
        return
    }
    val locale = currentLocale()
    metrics.forEach { metric ->
        Card(
            Modifier
                .fillMaxWidth()
                .testTag("dashboard_metric_${metric.indicator}")
                .semantics(mergeDescendants = true) { },
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(MetricLabels.text("training_metric.${metric.indicator.lowercase(Locale.ROOT)}", metric.indicator), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.dashboard_metric_value, MetricFormat.value(metric.value, 0, locale), metric.unit),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    stringResource(
                        R.string.dashboard_metric_method,
                        MetricLabels.text("training_metric.method.${metric.methodCode.lowercase(Locale.ROOT)}", metric.methodCode),
                        metric.methodVersion,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(stringResource(R.string.dashboard_metric_measured_at, DashboardFormat.dateTime(metric.measuredAt, zone, locale)), style = MaterialTheme.typography.bodySmall)
                Text(MetricLabels.text(metric.disclaimerKey, metric.disclaimerKey), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ------------------------------------------------------------------ rango personalizado

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomRangeDialog(state: TrainingDashboardUiState, onDismiss: () -> Unit, onConfirm: (LocalDate, LocalDate) -> Unit) {
    val today = state.today
    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = state.customStart?.let(DashboardFormat::utcMillis),
        initialSelectedEndDateMillis = state.customEnd?.let(DashboardFormat::utcMillis),
        selectableDates = remember(today) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = !DashboardFormat.utcDate(utcTimeMillis).isAfter(today)
                override fun isSelectableYear(year: Int): Boolean = year <= today.year
            }
        },
    )
    val start = pickerState.selectedStartDateMillis
    val end = pickerState.selectedEndDateMillis
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { if (start != null && end != null) onConfirm(DashboardFormat.utcDate(start), DashboardFormat.utcDate(end)) },
                enabled = start != null && end != null,
                modifier = Modifier.testTag("dashboard_custom_apply"),
            ) { Text(stringResource(R.string.dashboard_custom_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        DateRangePicker(state = pickerState, modifier = Modifier.weight(1f).testTag("dashboard_custom_picker"))
    }
}

/** Idioma vigente de la configuración: observable, a diferencia de `Locale.getDefault()`. */
@Composable
@ReadOnlyComposable
private fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

/** Formatos regionales que no alteran el dato fuente (CA3); fechas y horas en la zona del dispositivo. */
internal object DashboardFormat {
    /** Entero con la agrupación de [locale], sin redondear ni cambiar de unidad. */
    fun number(value: Long, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(value)

    fun date(date: LocalDate, locale: Locale): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)

    fun dateTime(instant: Instant, zone: ZoneId, locale: Locale): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zone).format(instant)

    /** El selector de fechas trabaja con la medianoche UTC de cada día. */
    fun utcMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    fun utcDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
}
