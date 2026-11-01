package com.lfnj.sport_app.features.nutrition.routines

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lfnj.sport_app.R
import com.lfnj.sport_app.features.nutrition.intake.FoodLabels
import com.lfnj.sport_app.features.nutrition.intake.model.MealType
import com.lfnj.sport_app.features.nutrition.routines.model.NutritionRoutineSuggestion
import com.lfnj.sport_app.features.nutrition.routines.model.NutritionRoutinesSnapshot
import com.lfnj.sport_app.features.nutrition.routines.model.RoutineMissingContext
import com.lfnj.sport_app.features.nutrition.routines.model.RoutineReason
import com.lfnj.sport_app.features.recommendations.SuggestionsFormat
import com.lfnj.sport_app.features.training.session.model.TrainingSport
import java.util.Locale

/**
 * «Rutinas de alimentación» (HU030): rutinas publicadas por profesionales con autor, vigencia,
 * comidas y motivo; detalle con el día tipo y los alérgenos que contiene. Indica cuándo no se
 * verificaron restricciones y alergias (CA3), muestra siempre que no reemplaza una valoración
 * médica y la antigüedad de la última lista confirmada. Sin compra, pago ni envío (CA5).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutritionRoutinesScreen(
    state: NutritionRoutinesUiState,
    onOpenDetail: (String) -> Unit,
    onCloseDetail: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val detail = state.selected
    BackHandler(enabled = detail != null, onBack = onCloseDetail)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (detail == null) R.string.nutrition_routines_title else R.string.nutrition_routines_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = if (detail == null) onBack else onCloseDetail, modifier = Modifier.testTag("nutrition_routines_back")) {
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
                .testTag("nutrition_routines_screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val snapshot = state.snapshot
            when {
                state.loading -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.testTag("nutrition_routines_loading"))
                    Text(stringResource(R.string.nutrition_routines_loading))
                }
                snapshot == null -> WithoutSnapshot(state, onRetry)
                detail != null -> {
                    SyncStatus(state, snapshot, onRetry)
                    if (snapshot.restrictionsNotVerified) RestrictionsNotVerified()
                    RoutineDetail(detail, state)
                    MedicalDisclaimer()
                }
                else -> {
                    SyncStatus(state, snapshot, onRetry)
                    Personalization(snapshot)
                    if (state.empty) {
                        Text(
                            stringResource(if (snapshot.personalized) R.string.nutrition_routines_empty_personalized else R.string.nutrition_routines_empty),
                            Modifier.testTag("nutrition_routines_empty"),
                        )
                    } else {
                        snapshot.items.forEach { RoutineCard(it, onOpenDetail) }
                    }
                    MedicalDisclaimer()
                }
            }
        }
    }
}

/** Sin lista confirmada: nunca sincronizada sin red, error o acceso rechazado. */
@Composable
private fun WithoutSnapshot(state: NutritionRoutinesUiState, onRetry: () -> Unit) {
    if (state.neverSynced) {
        Notice(R.string.nutrition_routines_never_synced, "nutrition_routines_never_synced")
    } else {
        Notice(errorMessage(state.problem), "nutrition_routines_error", MaterialTheme.colorScheme.error)
    }
    Button(onClick = onRetry, enabled = !state.refreshing, modifier = Modifier.testTag("nutrition_routines_retry")) {
        Text(stringResource(R.string.action_retry))
    }
}

private fun errorMessage(problem: NutritionRoutinesProblem?): Int = when (problem) {
    NutritionRoutinesProblem.CAPABILITY_REQUIRED -> R.string.nutrition_routines_error_capability_required
    NutritionRoutinesProblem.PERMISSION_REQUIRED -> R.string.nutrition_routines_error_permission_required
    NutritionRoutinesProblem.INVALID_REQUEST -> R.string.nutrition_routines_error_invalid_request
    NutritionRoutinesProblem.INVALID_RESPONSE -> R.string.nutrition_routines_error_invalid_response
    NutritionRoutinesProblem.STORAGE -> R.string.nutrition_routines_error_storage
    NutritionRoutinesProblem.NO_CONNECTION, NutritionRoutinesProblem.UNAVAILABLE, null -> R.string.nutrition_routines_error_unavailable
}

/** Actualización en curso, antigüedad y conectividad de la lista mostrada. */
@Composable
private fun SyncStatus(state: NutritionRoutinesUiState, snapshot: NutritionRoutinesSnapshot, onRetry: () -> Unit) {
    if (state.refreshing) {
        val refreshing = stringResource(R.string.nutrition_routines_refreshing)
        LinearProgressIndicator(
            Modifier
                .fillMaxWidth()
                .testTag("nutrition_routines_refreshing")
                .semantics { contentDescription = refreshing },
        )
    }
    Text(
        stringResource(R.string.nutrition_routines_synced_at, SuggestionsFormat.dateTime(snapshot.syncedAt, state.zone, currentLocale())),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("nutrition_routines_synced_at"),
    )
    if (state.stale) Notice(R.string.nutrition_routines_stale, "nutrition_routines_stale", MaterialTheme.colorScheme.error)
    when (state.problem) {
        null -> Unit
        NutritionRoutinesProblem.NO_CONNECTION -> Notice(R.string.nutrition_routines_offline_with_cache, "nutrition_routines_problem")
        else -> {
            Notice(R.string.nutrition_routines_unavailable_with_cache, "nutrition_routines_problem")
            OutlinedButton(onClick = onRetry, enabled = !state.refreshing, modifier = Modifier.testTag("nutrition_routines_retry")) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

/**
 * Cuándo la lista es compatible con las restricciones del deportista y cuándo no (CA1, CA3): una
 * lista con `personalized: false` nunca se presenta como verificada y se explica cada dato faltante.
 */
@Composable
private fun Personalization(snapshot: NutritionRoutinesSnapshot) {
    if (snapshot.restrictionsNotVerified) RestrictionsNotVerified()
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (snapshot.personalized) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(if (snapshot.personalized) "nutrition_routines_personalized" else "nutrition_routines_not_personalized")
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(if (snapshot.personalized) R.string.nutrition_routines_personalized else R.string.nutrition_routines_not_personalized),
                style = MaterialTheme.typography.titleSmall,
            )
            if (snapshot.contextUnavailable) {
                Text(stringResource(R.string.nutrition_routines_context_unavailable), Modifier.testTag("nutrition_routines_context_unavailable"))
            } else {
                snapshot.missingContext.forEach { code ->
                    val text = when (RoutineMissingContext.fromWire(code)) {
                        RoutineMissingContext.NUTRITION_HABITS -> R.string.nutrition_routines_missing_nutrition_habits
                        RoutineMissingContext.SPORTS -> R.string.nutrition_routines_missing_sports
                        RoutineMissingContext.PRIMARY_GOAL -> R.string.nutrition_routines_missing_primary_goal
                        null -> R.string.nutrition_routines_missing_other
                    }
                    Text(stringResource(text), Modifier.testTag("nutrition_routines_missing_$code"))
                }
            }
        }
    }
}

/** Aviso `RESTRICTIONS_NOT_VERIFIED` (CA3): no se comprobaron alergias ni patrón alimenticio. */
@Composable
private fun RestrictionsNotVerified() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("nutrition_routines_restrictions_not_verified")
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            stringResource(R.string.nutrition_routines_restrictions_not_verified),
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/** Texto fijo `NOT_MEDICAL_ADVICE`: se muestra siempre, con o sin aviso del BFF. */
@Composable
private fun MedicalDisclaimer() {
    Text(
        stringResource(R.string.nutrition_routines_not_medical_advice),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("nutrition_routines_not_medical_advice"),
    )
}

/** Tarjeta de una rutina: título, autor, objetivo, patrón, vigencia, comidas y motivo (CA4). */
@Composable
private fun RoutineCard(item: NutritionRoutineSuggestion, onOpenDetail: (String) -> Unit) {
    val openDescription = stringResource(R.string.nutrition_routines_open_detail, item.title)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = openDescription) { onOpenDetail(item.templateId) }
            .testTag("nutrition_routine_${item.templateId}")
            .semantics(mergeDescendants = true) { },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            Author(item)
            Text(
                stringResource(
                    R.string.nutrition_routines_summary_line,
                    goalLabel(item.goal),
                    patternLabel(item.dietaryPattern),
                    pluralStringResource(R.plurals.nutrition_routines_days, item.durationDays, item.durationDays),
                )
            )
            Text(pluralStringResource(R.plurals.nutrition_routines_meals_per_day, item.dailyMeals.size, item.dailyMeals.size))
            Reasons(item)
        }
    }
}

@Composable
private fun Author(item: NutritionRoutineSuggestion) {
    val author = item.authorName
    Text(
        if (author != null) stringResource(R.string.nutrition_routines_author, author) else stringResource(R.string.nutrition_routines_author_unknown),
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** Motivos de la sugerencia; en la lista no personalizada el BFF no envía ninguno. */
@Composable
private fun Reasons(item: NutritionRoutineSuggestion) {
    item.knownReasons.forEach { reason ->
        Text(
            stringResource(
                when (reason) {
                    RoutineReason.RESTRICTION_COMPATIBLE -> R.string.nutrition_routines_reason_restriction_compatible
                    RoutineReason.GOAL_MATCH -> R.string.nutrition_routines_reason_goal_match
                    RoutineReason.SPORT_MATCH -> R.string.nutrition_routines_reason_sport_match
                }
            ),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.testTag("nutrition_routine_reason_${reason.wire}"),
        )
    }
}

/** Detalle de la rutina con el día tipo; sin acciones de asignar, comprar ni pedir a domicilio. */
@Composable
private fun RoutineDetail(item: NutritionRoutineSuggestion, state: NutritionRoutinesUiState) {
    val locale = currentLocale()
    Column(Modifier.testTag("nutrition_routine_detail"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(item.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Author(item)
        Text(stringResource(R.string.nutrition_routines_field_goal, goalLabel(item.goal)))
        Text(stringResource(R.string.nutrition_routines_field_sport, item.sport?.let { sportLabel(it) } ?: stringResource(R.string.nutrition_routines_sport_any)))
        Text(stringResource(R.string.nutrition_routines_field_pattern, patternLabel(item.dietaryPattern)))
        Text(
            stringResource(
                R.string.nutrition_routines_field_duration,
                pluralStringResource(R.plurals.nutrition_routines_days, item.durationDays, item.durationDays),
            ),
            modifier = Modifier.testTag("nutrition_routine_duration"),
        )
        Text(
            if (item.containsAllergens.isEmpty()) {
                stringResource(R.string.nutrition_routines_allergens_none)
            } else {
                stringResource(R.string.nutrition_routines_field_allergens, item.containsAllergens.map { allergenLabel(it) }.joinToString(", "))
            },
            modifier = Modifier.testTag("nutrition_routine_allergens"),
        )
        Text(
            stringResource(R.string.nutrition_routines_field_published, SuggestionsFormat.date(item.publishedAt, state.zone, locale)),
            style = MaterialTheme.typography.bodySmall,
        )
        if (item.knownReasons.isNotEmpty()) {
            SectionTitle(R.string.nutrition_routines_reasons_title)
            Reasons(item)
        }
        SectionTitle(R.string.nutrition_routines_description_title)
        Text(item.summary, Modifier.testTag("nutrition_routine_description"))
        SectionTitle(R.string.nutrition_routines_meals_title)
        item.dailyMeals.forEachIndexed { index, meal ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("nutrition_routine_meal_$index")
                    .semantics(mergeDescendants = true) { },
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(mealLabel(meal.meal), style = MaterialTheme.typography.titleSmall)
                    meal.items.forEach { food ->
                        Text(
                            stringResource(
                                R.string.nutrition_routines_food_line,
                                FoodLabels.name(state.foodLabelKeys[food.foodCode], food.foodCode),
                                pluralStringResource(R.plurals.nutrition_routines_portions, food.portions, food.portions),
                            )
                        )
                    }
                }
            }
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("nutrition_routine_no_purchase")
                .semantics(mergeDescendants = true) { },
        ) {
            Text(stringResource(R.string.nutrition_routines_no_purchase), Modifier.padding(12.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .padding(top = 8.dp)
            .semantics { heading() },
    )
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

/** Un código que esta versión no conoce se muestra tal cual: nunca se reinterpreta como otro valor. */
@Composable
private fun sportLabel(code: String): String = TrainingSport.fromWire(code)?.let { stringResource(it.label) } ?: code

@Composable
private fun mealLabel(code: String): String = MealType.fromWire(code)?.let { stringResource(it.label) } ?: code

@Composable
private fun goalLabel(code: String): String = when (code) {
    "HEALTH" -> stringResource(R.string.nutrition_routines_goal_health)
    "PERFORMANCE" -> stringResource(R.string.nutrition_routines_goal_performance)
    "WEIGHT_MANAGEMENT" -> stringResource(R.string.nutrition_routines_goal_weight_management)
    "RECREATION" -> stringResource(R.string.nutrition_routines_goal_recreation)
    "COMPETITION" -> stringResource(R.string.nutrition_routines_goal_competition)
    else -> code
}

/** Textos del catálogo de patrones de HU009, iguales a la web (`nutrition.habits.patterns`). */
@Composable
private fun patternLabel(code: String): String = when (code) {
    "OMNIVORE" -> stringResource(R.string.nutrition_routines_pattern_omnivore)
    "VEGETARIAN" -> stringResource(R.string.nutrition_routines_pattern_vegetarian)
    "VEGAN" -> stringResource(R.string.nutrition_routines_pattern_vegan)
    "PESCATARIAN" -> stringResource(R.string.nutrition_routines_pattern_pescatarian)
    "FLEXITARIAN" -> stringResource(R.string.nutrition_routines_pattern_flexitarian)
    else -> code
}

/** Textos del catálogo de alergias de HU009, iguales a la web (`nutrition.habits.allergies`). */
@Composable
private fun allergenLabel(code: String): String = when (code) {
    "PEANUTS" -> stringResource(R.string.nutrition_routines_allergen_peanuts)
    "TREE_NUTS" -> stringResource(R.string.nutrition_routines_allergen_tree_nuts)
    "MILK" -> stringResource(R.string.nutrition_routines_allergen_milk)
    "EGGS" -> stringResource(R.string.nutrition_routines_allergen_eggs)
    "WHEAT" -> stringResource(R.string.nutrition_routines_allergen_wheat)
    "SOY" -> stringResource(R.string.nutrition_routines_allergen_soy)
    "FISH" -> stringResource(R.string.nutrition_routines_allergen_fish)
    "SHELLFISH" -> stringResource(R.string.nutrition_routines_allergen_shellfish)
    "SESAME" -> stringResource(R.string.nutrition_routines_allergen_sesame)
    else -> code
}

/** Idioma vigente de la configuración: observable, a diferencia de `Locale.getDefault()`. */
@Composable
@ReadOnlyComposable
private fun currentLocale(): Locale = LocalConfiguration.current.locales[0]
