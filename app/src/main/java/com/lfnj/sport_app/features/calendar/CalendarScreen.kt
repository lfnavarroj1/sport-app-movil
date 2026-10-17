package com.lfnj.sport_app.features.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lfnj.sport_app.R
import com.lfnj.sport_app.features.calendar.model.CalendarDay
import com.lfnj.sport_app.features.calendar.model.CalendarItem
import com.lfnj.sport_app.features.calendar.model.CalendarSnapshot
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Calendario deportivo del deportista (HU035, `/calendario`): eventos publicados y sesiones
 * planificadas del mes visible, por mes o en lista, en la zona del dispositivo. Indica la
 * antigüedad del último periodo confirmado, las fuentes incompletas y los resultados truncados.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    state: CalendarUiState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onCurrentMonth: () -> Unit,
    onModeChange: (CalendarMode) -> Unit,
    onSelectDay: (LocalDate) -> Unit,
    onRetry: () -> Unit,
    onShowSyncedPeriod: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.calendar_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("calendar_back")) {
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
                .testTag("calendar_screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MonthHeader(state, onPreviousMonth, onNextMonth, onCurrentMonth)
            ModeSelector(state.mode, onModeChange)
            val snapshot = state.snapshot
            when {
                state.loading -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.testTag("calendar_loading"))
                    Text(stringResource(R.string.calendar_loading))
                }
                snapshot == null -> WithoutSnapshot(state, onRetry, onShowSyncedPeriod)
                else -> {
                    if (state.refreshing) {
                        val refreshing = stringResource(R.string.calendar_refreshing)
                        LinearProgressIndicator(
                            Modifier
                                .fillMaxWidth()
                                .testTag("calendar_refreshing")
                                .semantics { contentDescription = refreshing },
                        )
                    }
                    SnapshotStatus(state, snapshot, onRetry)
                    when {
                        snapshot.confirmedEmpty -> Text(stringResource(R.string.calendar_empty), Modifier.testTag("calendar_empty"))
                        state.mode == CalendarMode.MONTH -> MonthView(state, onSelectDay)
                        else -> ListView(state)
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthHeader(state: CalendarUiState, onPrevious: () -> Unit, onNext: () -> Unit, onCurrent: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious, modifier = Modifier.testTag("calendar_previous")) {
            Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.calendar_previous_month))
        }
        Text(
            CalendarFormat.month(state.month, currentLocale()),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .testTag("calendar_month_title")
                .semantics { heading(); liveRegion = LiveRegionMode.Polite },
        )
        IconButton(onClick = onNext, modifier = Modifier.testTag("calendar_next")) {
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = stringResource(R.string.calendar_next_month))
        }
    }
    if (state.month != YearMonth.from(state.today)) {
        TextButton(onClick = onCurrent, modifier = Modifier.testTag("calendar_current")) {
            Text(stringResource(R.string.calendar_current_month))
        }
    }
}

@Composable
private fun ModeSelector(mode: CalendarMode, onModeChange: (CalendarMode) -> Unit) {
    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(CalendarMode.MONTH to R.string.calendar_mode_month, CalendarMode.LIST to R.string.calendar_mode_list).forEach { (option, label) ->
            FilterChip(
                selected = mode == option,
                onClick = { onModeChange(option) },
                label = { Text(stringResource(label)) },
                modifier = Modifier.testTag("calendar_mode_${option.name.lowercase()}"),
            )
        }
    }
}

/** Sin periodo confirmado para el mes visible: sin red, error o acceso rechazado. */
@Composable
private fun WithoutSnapshot(state: CalendarUiState, onRetry: () -> Unit, onShowSyncedPeriod: () -> Unit) {
    val syncedMonth = state.syncedMonth
    when {
        state.neverSynced -> Notice(R.string.calendar_never_synced, "calendar_never_synced")
        state.problem == CalendarProblem.NO_CONNECTION && syncedMonth != null -> {
            Notice(R.string.calendar_offline_other_period, "calendar_offline_other_period")
            OutlinedButton(onClick = onShowSyncedPeriod, modifier = Modifier.testTag("calendar_show_synced")) {
                Text(stringResource(R.string.calendar_show_synced_period, CalendarFormat.month(syncedMonth, currentLocale())))
            }
        }
        else -> Notice(errorMessage(state.problem), "calendar_error", MaterialTheme.colorScheme.error)
    }
    Button(onClick = onRetry, enabled = !state.refreshing, modifier = Modifier.testTag("calendar_retry")) {
        Text(stringResource(R.string.action_retry))
    }
}

private fun errorMessage(problem: CalendarProblem?): Int = when (problem) {
    CalendarProblem.CAPABILITY_REQUIRED -> R.string.calendar_error_capability_required
    CalendarProblem.PERMISSION_REQUIRED -> R.string.calendar_error_permission_required
    CalendarProblem.INVALID_PERIOD -> R.string.calendar_error_invalid_period
    CalendarProblem.INVALID_RESPONSE -> R.string.calendar_error_invalid_response
    CalendarProblem.STORAGE -> R.string.calendar_error_storage
    CalendarProblem.NO_CONNECTION, CalendarProblem.UNAVAILABLE, null -> R.string.calendar_error_unavailable
}

/** Antigüedad, conectividad, fuentes incompletas y resultados truncados del periodo mostrado. */
@Composable
private fun SnapshotStatus(state: CalendarUiState, snapshot: CalendarSnapshot, onRetry: () -> Unit) {
    Text(
        stringResource(R.string.calendar_synced_at, CalendarFormat.dateTime(snapshot.syncedAt, state.zone, currentLocale())),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("calendar_synced_at"),
    )
    if (state.stale) Notice(R.string.calendar_stale, "calendar_stale", MaterialTheme.colorScheme.error)
    when (state.problem) {
        null -> Unit
        CalendarProblem.NO_CONNECTION -> Notice(R.string.calendar_offline_with_cache, "calendar_problem")
        else -> {
            Notice(R.string.calendar_unavailable_with_cache, "calendar_problem")
            OutlinedButton(onClick = onRetry, enabled = !state.refreshing, modifier = Modifier.testTag("calendar_retry")) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
    snapshot.incompleteSources.forEach { name ->
        val source = stringResource(
            when (name) {
                CalendarSnapshot.SOURCE_EVENTS -> R.string.calendar_source_events
                CalendarSnapshot.SOURCE_TRAINING -> R.string.calendar_source_training
                else -> R.string.calendar_source_other
            }
        )
        Text(
            stringResource(R.string.calendar_incomplete, source),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .testTag("calendar_incomplete_$name")
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    snapshot.truncatedSources.forEach { name ->
        val kind = stringResource(
            when (name) {
                CalendarSnapshot.SOURCE_EVENTS -> R.string.calendar_truncated_events
                CalendarSnapshot.SOURCE_TRAINING -> R.string.calendar_truncated_training
                else -> R.string.calendar_truncated_other
            }
        )
        Text(stringResource(R.string.calendar_truncated, kind), modifier = Modifier.testTag("calendar_truncated_$name"))
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

// ------------------------------------------------------------------ vista de mes

@Composable
private fun MonthView(state: CalendarUiState, onSelectDay: (LocalDate) -> Unit) {
    val byDate = state.days.associateBy { it.date }
    val locale = currentLocale()
    val firstDayOfWeek = WeekFields.of(locale).firstDayOfWeek
    val first = state.month.atDay(1)
    val leading = (first.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
    val cells: List<LocalDate?> = List(leading) { null } + (1..state.month.lengthOfMonth()).map(state.month::atDay)
    val shown = state.selectedDay
        ?: state.today.takeIf { YearMonth.from(it) == state.month }
        ?: state.days.firstOrNull()?.date

    Column(Modifier.testTag("calendar_month_grid")) {
        Row(Modifier.fillMaxWidth().clearAndSetSemantics { }) {
            (0 until 7).map { firstDayOfWeek.plus(it.toLong()) }.forEach { day: DayOfWeek ->
                Text(
                    day.getDisplayName(TextStyle.NARROW, locale),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    if (date == null) {
                        Box(Modifier.weight(1f))
                    } else {
                        DayCell(date, byDate[date], selected = date == shown, today = date == state.today, onSelectDay, Modifier.weight(1f))
                    }
                }
                repeat(7 - week.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
    if (shown == null) {
        Text(stringResource(R.string.calendar_select_day))
    } else {
        Text(
            CalendarFormat.day(shown, locale),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        val items = byDate[shown]?.items.orEmpty()
        if (items.isEmpty()) {
            Text(stringResource(R.string.calendar_day_no_items), Modifier.testTag("calendar_day_empty"))
        } else {
            items.forEach { ItemCard(it, state.zone) }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    day: CalendarDay?,
    selected: Boolean,
    today: Boolean,
    onSelectDay: (LocalDate) -> Unit,
    modifier: Modifier,
) {
    val items = day?.items.orEmpty()
    val events = items.count { it is CalendarItem.Event }
    val sessions = items.count { it is CalendarItem.PlannedSession }
    val others = items.count { it is CalendarItem.Other }
    val summary = listOfNotNull(
        events.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.calendar_day_events, it, it) },
        sessions.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.calendar_day_sessions, it, it) },
        others.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.calendar_day_others, it, it) },
    ).ifEmpty { listOf(stringResource(R.string.calendar_day_empty_description)) }.joinToString(", ")
    val description = stringResource(R.string.calendar_day_description, CalendarFormat.day(date, currentLocale()), summary)
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .heightIn(min = 48.dp)
            .padding(2.dp)
            .background(if (selected) colors.primaryContainer else Color.Transparent, RoundedCornerShape(8.dp))
            .then(if (today) Modifier.border(1.dp, colors.primary, RoundedCornerShape(8.dp)) else Modifier)
            .selectable(selected = selected, role = Role.Button, onClick = { onSelectDay(date) })
            .semantics { contentDescription = description }
            .testTag("calendar_day_$date")
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // El número ya está en la descripción del día: no se lee dos veces.
        Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clearAndSetSemantics { })
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (events > 0) Dot(colors.primary)
            if (sessions > 0) Dot(colors.tertiary)
            if (others > 0) Dot(colors.outline)
        }
    }
}

@Composable
private fun Dot(color: Color) = Box(Modifier.size(6.dp).background(color, CircleShape))

// ------------------------------------------------------------------ vista de lista

@Composable
private fun ListView(state: CalendarUiState) {
    Column(Modifier.testTag("calendar_list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.days.forEach { day ->
            Text(
                CalendarFormat.day(day.date, currentLocale()),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .semantics { heading() },
            )
            day.items.forEach { ItemCard(it, state.zone) }
        }
    }
}

/** Tarjeta de un elemento; la fuente se distingue con etiqueta y color (no solo con color). */
@Composable
private fun ItemCard(item: CalendarItem, zone: ZoneId) {
    val colors = MaterialTheme.colorScheme
    val (badge, container) = when (item) {
        is CalendarItem.Event -> R.string.calendar_badge_event to colors.primaryContainer
        is CalendarItem.PlannedSession -> R.string.calendar_badge_session to colors.tertiaryContainer
        is CalendarItem.Other -> R.string.calendar_badge_other to colors.surfaceVariant
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("calendar_item_${item.itemId}")
            .semantics(mergeDescendants = true) { },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(badge), style = MaterialTheme.typography.labelMedium)
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            when (item) {
                is CalendarItem.Event -> {
                    Text(item.sport)
                    Text(stringResource(R.string.calendar_event_time, *CalendarFormat.eventRange(item, zone, currentLocale())))
                    Text(stringResource(R.string.calendar_event_location, item.location.name, item.location.city, item.location.countryCode))
                    Text(stringResource(R.string.calendar_event_time_zone, item.timeZone), style = MaterialTheme.typography.bodySmall)
                }
                is CalendarItem.PlannedSession -> {
                    Text(item.sport)
                    Text(stringResource(R.string.calendar_session_date_only), style = MaterialTheme.typography.bodySmall)
                    item.durationMinutes?.let { Text(stringResource(R.string.calendar_session_duration, it)) }
                    item.rpe?.let { Text(stringResource(R.string.calendar_session_rpe, it)) }
                }
                is CalendarItem.Other -> Unit
            }
        }
    }
}

/** Idioma vigente de la configuración: observable, a diferencia de `Locale.getDefault()`. */
@Composable
@ReadOnlyComposable
private fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

/** Formatos localizados; fechas y horas siempre en la zona del dispositivo (CA4). */
internal object CalendarFormat {
    fun month(month: YearMonth, locale: Locale): String =
        DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(month).capitalized(locale)

    fun day(date: LocalDate, locale: Locale): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(date).capitalized(locale)

    fun dateTime(instant: Instant, zone: ZoneId, locale: Locale): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zone).format(instant)

    /** Inicio y fin del evento en [zone]; si terminan el mismo día, el fin muestra solo la hora. */
    fun eventRange(event: CalendarItem.Event, zone: ZoneId, locale: Locale): Array<String> {
        val start = event.startsAt.atZone(zone)
        val end = event.endsAt.atZone(zone)
        val endText = if (start.toLocalDate() == end.toLocalDate()) {
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(end)
        } else {
            dateTime(event.endsAt, zone, locale)
        }
        return arrayOf(dateTime(event.startsAt, zone, locale), endText)
    }

    private fun String.capitalized(locale: Locale) = replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
}
