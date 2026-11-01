package com.lfnj.sport_app.features.nutrition.intake

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lfnj.sport_app.R
import com.lfnj.sport_app.features.nutrition.intake.model.ConsumedAtError
import com.lfnj.sport_app.features.nutrition.intake.model.FoodCatalogItem
import com.lfnj.sport_app.features.nutrition.intake.model.FoodIntakeValidation
import com.lfnj.sport_app.features.nutrition.intake.model.FoodSyncFailure
import com.lfnj.sport_app.features.nutrition.intake.model.FoodSyncState
import com.lfnj.sport_app.features.nutrition.intake.model.FoodUnit
import com.lfnj.sport_app.features.nutrition.intake.model.LocalFoodIntake
import com.lfnj.sport_app.features.nutrition.intake.model.MealType
import com.lfnj.sport_app.features.nutrition.intake.model.QuantityError
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * «Mi alimentación» (HU043, destino `nutrition`): formulario de consumo con alimentos, cantidades
 * y unidades del catálogo en caché, y estado de sincronización de los consumos del día. Sin
 * calorías, nutrientes ni juicios nutricionales.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodIntakeScreen(
    state: FoodIntakeUiState,
    onSelectMeal: (MealType) -> Unit,
    onConsumedAtChange: (Instant) -> Unit,
    onQueryChange: (String) -> Unit,
    onAddFood: (String) -> Unit,
    onRemoveLine: (Int) -> Unit,
    onQuantityChange: (Int, String) -> Unit,
    onSelectUnit: (Int, FoodUnit) -> Unit,
    onSubmit: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.food_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("food_back")) {
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
                .testTag("food_intake"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.loading) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.testTag("food_loading"))
                    Text(stringResource(R.string.food_loading))
                }
                return@Column
            }
            Text(stringResource(R.string.food_intro), style = MaterialTheme.typography.bodyMedium)
            if (!state.online) Notice(stringResource(R.string.food_offline), tag = "food_offline")
            if (state.storageProblem) Notice(stringResource(R.string.food_storage_problem), tag = "food_storage_problem", isError = true)
            CatalogStatus(state, zone, onRetry)
            SubmitNotice(state)
            if (state.catalog != null) IntakeForm(state, onSelectMeal, onConsumedAtChange, onQueryChange, onAddFood, onRemoveLine, onQuantityChange, onSelectUnit, onSubmit, zone)
            Intakes(state, onDismiss, zone)
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

/** Sin catálogo en caché se pide conexión y no se ofrece ningún alimento (CA5); con más de 24 h, `STALE` (CA6). */
@Composable
private fun CatalogStatus(state: FoodIntakeUiState, zone: ZoneId, onRetry: () -> Unit) {
    val catalog = state.catalog
    val noticeText = when (state.notice) {
        FoodCatalogNotice.NO_CONNECTION -> R.string.food_notice_no_connection
        FoodCatalogNotice.UNAVAILABLE -> R.string.food_notice_unavailable
        FoodCatalogNotice.PERMISSION_REQUIRED -> R.string.food_notice_permission
        FoodCatalogNotice.INVALID_RESPONSE -> R.string.food_notice_invalid
        FoodCatalogNotice.STORAGE -> R.string.food_notice_storage
        null -> null
    }
    if (catalog == null) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp).testTag("food_catalog_required"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Notice(stringResource(R.string.food_catalog_required), tag = "food_catalog_required_text", isError = true)
                noticeText?.let { Notice(stringResource(it), tag = "food_catalog_notice") }
                RetryButton(state.refreshing, onRetry)
            }
        }
        return
    }
    if (state.catalogStale) {
        Notice(stringResource(R.string.food_catalog_stale, formatInstant(catalog.syncedAt, zone)), tag = "food_catalog_stale", isError = true)
    }
    noticeText?.let {
        Notice(stringResource(it), tag = "food_catalog_notice")
        RetryButton(state.refreshing, onRetry)
    }
}

@Composable
private fun RetryButton(refreshing: Boolean, onRetry: () -> Unit) {
    OutlinedButton(onClick = onRetry, enabled = !refreshing, modifier = Modifier.testTag("food_retry")) {
        Text(stringResource(if (refreshing) R.string.food_retrying else R.string.food_retry))
    }
}

@Composable
private fun SubmitNotice(state: FoodIntakeUiState) {
    val text = when (state.submitNotice) {
        // Encolado no es confirmado: el estado real lo muestra la tarjeta del consumo (CA5).
        FoodSubmitNotice.QUEUED -> R.string.food_submit_queued
        FoodSubmitNotice.STORAGE -> R.string.food_submit_storage
        FoodSubmitNotice.CATALOG_UNAVAILABLE -> R.string.food_submit_catalog_unavailable
        null -> null
    }
    text?.let { Notice(stringResource(it), tag = "food_submit_notice", isError = state.submitNotice != FoodSubmitNotice.QUEUED) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IntakeForm(
    state: FoodIntakeUiState,
    onSelectMeal: (MealType) -> Unit,
    onConsumedAtChange: (Instant) -> Unit,
    onQueryChange: (String) -> Unit,
    onAddFood: (String) -> Unit,
    onRemoveLine: (Int) -> Unit,
    onQuantityChange: (Int, String) -> Unit,
    onSelectUnit: (Int, FoodUnit) -> Unit,
    onSubmit: () -> Unit,
    zone: ZoneId,
) {
    val enabled = !state.busy
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.food_new_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })

            Text(stringResource(R.string.food_meal_label), style = MaterialTheme.typography.titleSmall)
            if (state.mealMissing) Notice(stringResource(R.string.food_meal_required), tag = "food_meal_missing", isError = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MealType.entries.forEach { meal ->
                    FilterChip(
                        selected = state.mealType == meal,
                        onClick = { onSelectMeal(meal) },
                        enabled = enabled,
                        label = { Text(stringResource(meal.label)) },
                        modifier = Modifier.testTag("food_meal_${meal.wire}"),
                    )
                }
            }

            ConsumedAtField(state.consumedAt, state.consumedAtError, zone, enabled, onConsumedAtChange)

            Text(stringResource(R.string.food_items_label), style = MaterialTheme.typography.titleSmall)
            state.lines.forEachIndexed { index, line -> DraftLine(index, line, enabled, onRemoveLine, onQuantityChange, onSelectUnit) }
            if (state.itemsMissing) Notice(stringResource(R.string.food_items_required), tag = "food_items_missing", isError = true)

            if (state.canAddMore) {
                FoodSearch(state, enabled, onQueryChange, onAddFood)
            } else {
                Notice(stringResource(R.string.food_items_max, FoodIntakeValidation.MAX_ITEMS), tag = "food_items_max")
            }

            Button(onClick = onSubmit, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("food_submit")) {
                Text(stringResource(if (state.busy) R.string.food_submitting else R.string.food_submit))
            }
        }
    }
}

@Composable
private fun FoodSearch(state: FoodIntakeUiState, enabled: Boolean, onQueryChange: (String) -> Unit, onAddFood: (String) -> Unit) {
    OutlinedTextField(
        value = state.query,
        onValueChange = onQueryChange,
        label = { Text(stringResource(R.string.food_search_label)) },
        supportingText = { Text(stringResource(R.string.food_search_hint)) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("food_search"),
    )
    if (state.query.isBlank()) return
    if (state.results.isEmpty()) {
        // Solo hay alimentos del catálogo en caché: no se admite texto libre (CA6).
        Text(stringResource(R.string.food_search_empty), modifier = Modifier.testTag("food_search_empty"))
        return
    }
    state.results.take(MAX_RESULTS).forEach { food -> SearchResult(food, enabled, onAddFood) }
}

@Composable
private fun SearchResult(food: FoodCatalogItem, enabled: Boolean, onAddFood: (String) -> Unit) {
    val name = FoodLabels.name(food.labelKey, food.foodCode)
    val addLabel = stringResource(R.string.food_add_named, name)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(name)
            food.category?.let { Text(stringResource(it.label), style = MaterialTheme.typography.bodySmall) }
        }
        TextButton(onClick = { onAddFood(food.foodCode) }, enabled = enabled, modifier = Modifier.testTag("food_add_${food.foodCode}")) {
            // El botón «Agregar» se anuncia con el nombre del alimento.
            Text(stringResource(R.string.food_add), modifier = Modifier.semantics { contentDescription = addLabel })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DraftLine(
    index: Int,
    line: FoodDraftLine,
    enabled: Boolean,
    onRemoveLine: (Int) -> Unit,
    onQuantityChange: (Int, String) -> Unit,
    onSelectUnit: (Int, FoodUnit) -> Unit,
) {
    val name = FoodLabels.name(line.labelKey, line.foodCode)
    Column(Modifier.fillMaxWidth().testTag("food_line_$index"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { onRemoveLine(index) }, enabled = enabled, modifier = Modifier.testTag("food_remove_$index")) {
                Text(stringResource(R.string.food_remove))
            }
        }
        val errorText = when (line.error) {
            QuantityError.FORMAT -> stringResource(R.string.food_quantity_format)
            QuantityError.OUT_OF_RANGE -> stringResource(R.string.food_quantity_range)
            null -> null
        }
        OutlinedTextField(
            value = line.quantity,
            onValueChange = { onQuantityChange(index, it) },
            label = { Text(stringResource(R.string.food_quantity_label, name)) },
            supportingText = { Text(errorText ?: stringResource(R.string.food_quantity_hint), modifier = if (errorText != null) Modifier.testTag("food_quantity_error_$index") else Modifier) },
            isError = line.error != null,
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("food_quantity_$index"),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Solo las unidades permitidas para el alimento (`allowed_units`).
            line.allowedUnits.forEach { unit ->
                FilterChip(
                    selected = line.unit == unit,
                    onClick = { onSelectUnit(index, unit) },
                    enabled = enabled,
                    label = { Text(stringResource(unit.label)) },
                    modifier = Modifier.testTag("food_unit_${index}_${unit.wire}"),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsumedAtField(consumedAt: Instant, error: ConsumedAtError?, zone: ZoneId, enabled: Boolean, onChange: (Instant) -> Unit) {
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }
    val local = consumedAt.atZone(zone)
    Text(stringResource(R.string.food_consumed_at, formatInstant(consumedAt, zone)), modifier = Modifier.testTag("food_consumed_at"))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { pickDate = true }, enabled = enabled, modifier = Modifier.testTag("food_pick_date")) {
            Text(stringResource(R.string.food_change_date))
        }
        OutlinedButton(onClick = { pickTime = true }, enabled = enabled, modifier = Modifier.testTag("food_pick_time")) {
            Text(stringResource(R.string.food_change_time))
        }
    }
    error?.let {
        val text = stringResource(if (it == ConsumedAtError.IN_FUTURE) R.string.food_consumed_at_future else R.string.food_consumed_at_too_old)
        Notice(text, tag = "food_consumed_at_error", isError = true)
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
private fun Intakes(state: FoodIntakeUiState, onDismiss: (String) -> Unit, zone: ZoneId) {
    Text(stringResource(R.string.food_intakes_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    Text(stringResource(R.string.food_intakes_local_only), style = MaterialTheme.typography.bodySmall)
    if (state.intakes.isEmpty()) {
        Text(stringResource(R.string.food_intakes_empty), modifier = Modifier.testTag("food_intakes_empty"))
        return
    }
    state.intakes.forEach { intake -> IntakeCard(intake, state.busy, onDismiss, zone) }
}

@Composable
private fun IntakeCard(intake: LocalFoodIntake, busy: Boolean, onDismiss: (String) -> Unit, zone: ZoneId) {
    val details = intake.details
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .padding(16.dp)
                .testTag("food_intake_${intake.intakeId}"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (details == null) {
                Text(stringResource(R.string.food_intake_unreadable), style = MaterialTheme.typography.titleSmall)
            } else {
                Text(stringResource(details.mealType.label), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.food_consumed_at, formatInstant(details.consumedAt, zone)))
                details.items.forEach { line ->
                    Text(stringResource(R.string.food_intake_line, FoodLabels.name(line.labelKey, line.foodCode), line.quantity, stringResource(line.unit.label)))
                }
            }
            val (label, color) = when (intake.state) {
                FoodSyncState.PENDING_SYNC -> R.string.food_state_pending to MaterialTheme.colorScheme.onSurfaceVariant
                FoodSyncState.SYNCED -> R.string.food_state_synced to MaterialTheme.colorScheme.tertiary
                FoodSyncState.SYNC_FAILED -> R.string.food_state_failed to MaterialTheme.colorScheme.error
            }
            // Estado, fecha de registro y causa se anuncian juntos; la etiqueta queda en el nodo fusionado.
            Column(
                Modifier
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                    .testTag("food_state_${intake.intakeId}"),
            ) {
                Text(stringResource(label), color = color, style = MaterialTheme.typography.labelLarge)
                intake.registeredAt?.let { Text(stringResource(R.string.food_registered_at, formatInstant(it, zone)), style = MaterialTheme.typography.bodySmall) }
                if (intake.state == FoodSyncState.SYNC_FAILED) {
                    Text(failureText(intake), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("food_failure_${intake.intakeId}"))
                }
            }
            if (intake.dismissible) {
                OutlinedButton(onClick = { onDismiss(intake.intakeId) }, enabled = !busy, modifier = Modifier.testTag("food_dismiss_${intake.intakeId}")) {
                    Text(stringResource(R.string.food_dismiss))
                }
            }
        }
    }
}

@Composable
private fun failureText(intake: LocalFoodIntake): String = stringResource(
    when (intake.failure) {
        FoodSyncFailure.CATALOG_CHANGED -> R.string.food_failure_catalog
        FoodSyncFailure.VALIDATION -> R.string.food_failure_validation
        FoodSyncFailure.PERMISSION_REQUIRED -> R.string.food_failure_permission
        FoodSyncFailure.CONFLICT -> R.string.food_failure_conflict
        FoodSyncFailure.EXPIRED -> R.string.food_failure_expired
        FoodSyncFailure.UNREADABLE -> R.string.food_failure_unreadable
        FoodSyncFailure.REJECTED, FoodSyncFailure.INVALID_RESPONSE, null -> R.string.food_failure_rejected
    }
)

/** Fecha y hora en la zona y el formato regional del dispositivo; el dato fuente sigue en UTC. */
private fun formatInstant(instant: Instant, zone: ZoneId): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(zone).format(instant)

private const val MAX_RESULTS = 12
