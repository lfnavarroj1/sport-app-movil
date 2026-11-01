package com.lfnj.sport_app.features.recommendations

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
import com.lfnj.sport_app.features.recommendations.model.MissingContext
import com.lfnj.sport_app.features.recommendations.model.PlanSuggestionsSnapshot
import com.lfnj.sport_app.features.recommendations.model.PlanTemplateSuggestion
import com.lfnj.sport_app.features.recommendations.model.SuggestionReason
import com.lfnj.sport_app.features.training.session.model.TrainingSport
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
 * «Entrenamientos sugeridos» (HU028): plantillas de plan publicadas por profesionales con autor,
 * nivel, duración, deporte y motivo; detalle con las sesiones semanales. Indica si la lista no es
 * personalizada y qué dato falta, y la antigüedad de la última lista confirmada. No asigna ni
 * inicia planes: «Autorizar a este profesional» solo remite a la web (HU010).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanSuggestionsScreen(
    state: PlanSuggestionsUiState,
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
                title = { Text(stringResource(if (detail == null) R.string.plan_suggestions_title else R.string.plan_suggestions_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = if (detail == null) onBack else onCloseDetail, modifier = Modifier.testTag("plan_suggestions_back")) {
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
                .testTag("plan_suggestions_screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val snapshot = state.snapshot
            when {
                state.loading -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.testTag("plan_suggestions_loading"))
                    Text(stringResource(R.string.plan_suggestions_loading))
                }
                snapshot == null -> WithoutSnapshot(state, onRetry)
                detail != null -> {
                    SyncStatus(state, snapshot, onRetry)
                    TemplateDetail(detail, state.zone)
                }
                else -> {
                    SyncStatus(state, snapshot, onRetry)
                    Personalization(snapshot)
                    if (state.empty) {
                        Text(
                            stringResource(if (snapshot.personalized) R.string.plan_suggestions_empty_personalized else R.string.plan_suggestions_empty),
                            Modifier.testTag("plan_suggestions_empty"),
                        )
                    } else {
                        snapshot.items.forEach { TemplateCard(it, onOpenDetail) }
                    }
                }
            }
        }
    }
}

/** Sin lista confirmada: nunca sincronizada sin red, error o acceso rechazado. */
@Composable
private fun WithoutSnapshot(state: PlanSuggestionsUiState, onRetry: () -> Unit) {
    if (state.neverSynced) {
        Notice(R.string.plan_suggestions_never_synced, "plan_suggestions_never_synced")
    } else {
        Notice(errorMessage(state.problem), "plan_suggestions_error", MaterialTheme.colorScheme.error)
    }
    Button(onClick = onRetry, enabled = !state.refreshing, modifier = Modifier.testTag("plan_suggestions_retry")) {
        Text(stringResource(R.string.action_retry))
    }
}

private fun errorMessage(problem: PlanSuggestionsProblem?): Int = when (problem) {
    PlanSuggestionsProblem.CAPABILITY_REQUIRED -> R.string.plan_suggestions_error_capability_required
    PlanSuggestionsProblem.PERMISSION_REQUIRED -> R.string.plan_suggestions_error_permission_required
    PlanSuggestionsProblem.INVALID_REQUEST -> R.string.plan_suggestions_error_invalid_request
    PlanSuggestionsProblem.INVALID_RESPONSE -> R.string.plan_suggestions_error_invalid_response
    PlanSuggestionsProblem.STORAGE -> R.string.plan_suggestions_error_storage
    PlanSuggestionsProblem.NO_CONNECTION, PlanSuggestionsProblem.UNAVAILABLE, null -> R.string.plan_suggestions_error_unavailable
}

/** Actualización en curso, antigüedad y conectividad de la lista mostrada. */
@Composable
private fun SyncStatus(state: PlanSuggestionsUiState, snapshot: PlanSuggestionsSnapshot, onRetry: () -> Unit) {
    if (state.refreshing) {
        val refreshing = stringResource(R.string.plan_suggestions_refreshing)
        LinearProgressIndicator(
            Modifier
                .fillMaxWidth()
                .testTag("plan_suggestions_refreshing")
                .semantics { contentDescription = refreshing },
        )
    }
    Text(
        stringResource(R.string.plan_suggestions_synced_at, SuggestionsFormat.dateTime(snapshot.syncedAt, state.zone, currentLocale())),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("plan_suggestions_synced_at"),
    )
    if (state.stale) Notice(R.string.plan_suggestions_stale, "plan_suggestions_stale", MaterialTheme.colorScheme.error)
    when (state.problem) {
        null -> Unit
        PlanSuggestionsProblem.NO_CONNECTION -> Notice(R.string.plan_suggestions_offline_with_cache, "plan_suggestions_problem")
        else -> {
            Notice(R.string.plan_suggestions_unavailable_with_cache, "plan_suggestions_problem")
            OutlinedButton(onClick = onRetry, enabled = !state.refreshing, modifier = Modifier.testTag("plan_suggestions_retry")) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

/**
 * Cuándo la lista es «para ti» y cuándo no (CA2): nunca se presenta como personalizada una lista
 * con `personalized: false`, y se explica cada dato faltante.
 */
@Composable
private fun Personalization(snapshot: PlanSuggestionsSnapshot) {
    val missing = snapshot.missingContext
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (snapshot.personalized) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(if (snapshot.personalized) "plan_suggestions_personalized" else "plan_suggestions_not_personalized")
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val headline = when {
                !snapshot.personalized -> R.string.plan_suggestions_not_personalized
                MissingContext.PRIMARY_GOAL.wire in missing -> R.string.plan_suggestions_personalized_sport_only
                else -> R.string.plan_suggestions_personalized_full
            }
            Text(stringResource(headline), style = MaterialTheme.typography.titleSmall)
            if (snapshot.contextUnavailable) {
                Text(stringResource(R.string.plan_suggestions_context_unavailable), Modifier.testTag("plan_suggestions_context_unavailable"))
            } else {
                missing.forEach { code ->
                    val text = when (MissingContext.fromWire(code)) {
                        MissingContext.SPORTS -> R.string.plan_suggestions_missing_sports
                        MissingContext.PRIMARY_GOAL -> R.string.plan_suggestions_missing_primary_goal
                        null -> R.string.plan_suggestions_missing_other
                    }
                    Text(stringResource(text), Modifier.testTag("plan_suggestions_missing_$code"))
                }
            }
        }
    }
}

/** Tarjeta de una plantilla: título, autor, deporte, nivel, duración, sesiones por semana y motivo (CA4). */
@Composable
private fun TemplateCard(item: PlanTemplateSuggestion, onOpenDetail: (String) -> Unit) {
    val openDescription = stringResource(R.string.plan_suggestions_open_detail, item.title)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = openDescription) { onOpenDetail(item.templateId) }
            .testTag("plan_suggestion_${item.templateId}")
            .semantics(mergeDescendants = true) { },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            Author(item)
            Text(
                stringResource(
                    R.string.plan_suggestions_summary_line,
                    sportLabel(item.sport),
                    levelLabel(item.level),
                    pluralStringResource(R.plurals.plan_suggestions_weeks, item.durationWeeks, item.durationWeeks),
                )
            )
            Text(pluralStringResource(R.plurals.plan_suggestions_sessions_per_week, item.weeklySessions.size, item.weeklySessions.size))
            Reasons(item)
        }
    }
}

@Composable
private fun Author(item: PlanTemplateSuggestion) {
    val author = item.authorName
    Text(
        if (author != null) stringResource(R.string.plan_suggestions_author, author) else stringResource(R.string.plan_suggestions_author_unknown),
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** Motivos de la sugerencia; en la lista no personalizada el BFF no envía ninguno. */
@Composable
private fun Reasons(item: PlanTemplateSuggestion) {
    item.knownReasons.forEach { reason ->
        Text(
            stringResource(
                when (reason) {
                    SuggestionReason.SPORT_MATCH -> R.string.plan_suggestions_reason_sport_match
                    SuggestionReason.GOAL_MATCH -> R.string.plan_suggestions_reason_goal_match
                }
            ),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.testTag("plan_suggestion_reason_${reason.wire}"),
        )
    }
}

/** Detalle de la plantilla con sus sesiones semanales; sin acciones de asignar ni iniciar. */
@Composable
private fun TemplateDetail(item: PlanTemplateSuggestion, zone: ZoneId) {
    val locale = currentLocale()
    Column(Modifier.testTag("plan_suggestion_detail"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(item.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Author(item)
        Text(stringResource(R.string.plan_suggestions_field_sport, sportLabel(item.sport)))
        Text(stringResource(R.string.plan_suggestions_field_goal, goalLabel(item.goal)))
        Text(stringResource(R.string.plan_suggestions_field_level, levelLabel(item.level)))
        Text(
            stringResource(
                R.string.plan_suggestions_field_duration,
                pluralStringResource(R.plurals.plan_suggestions_weeks, item.durationWeeks, item.durationWeeks),
            ),
            modifier = Modifier.testTag("plan_suggestion_duration"),
        )
        Text(
            stringResource(R.string.plan_suggestions_field_published, SuggestionsFormat.date(item.publishedAt, zone, locale)),
            style = MaterialTheme.typography.bodySmall,
        )
        if (item.knownReasons.isNotEmpty()) {
            SectionTitle(R.string.plan_suggestions_reasons_title)
            Reasons(item)
        }
        SectionTitle(R.string.plan_suggestions_objectives_title)
        Text(item.summary, Modifier.testTag("plan_suggestion_objectives"))
        SectionTitle(R.string.plan_suggestions_sessions_title)
        item.weeklySessions.forEachIndexed { index, session ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("plan_suggestion_session_$index")
                    .semantics(mergeDescendants = true) { },
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(
                            R.string.plan_suggestions_session_header,
                            SuggestionsFormat.day(session.day, locale),
                            pluralStringResource(R.plurals.plan_suggestions_minutes, session.durationMinutes, session.durationMinutes),
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(session.description)
                }
            }
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("plan_suggestion_authorize_info")
                .semantics(mergeDescendants = true) { },
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.plan_suggestions_authorize_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.plan_suggestions_authorize_body))
            }
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
private fun levelLabel(code: String): String = when (code) {
    "BEGINNER" -> stringResource(R.string.plan_suggestions_level_beginner)
    "INTERMEDIATE" -> stringResource(R.string.plan_suggestions_level_intermediate)
    "ADVANCED" -> stringResource(R.string.plan_suggestions_level_advanced)
    else -> code
}

@Composable
private fun goalLabel(code: String): String = when (code) {
    "HEALTH" -> stringResource(R.string.plan_suggestions_goal_health)
    "PERFORMANCE" -> stringResource(R.string.plan_suggestions_goal_performance)
    "WEIGHT_MANAGEMENT" -> stringResource(R.string.plan_suggestions_goal_weight_management)
    "RECREATION" -> stringResource(R.string.plan_suggestions_goal_recreation)
    "COMPETITION" -> stringResource(R.string.plan_suggestions_goal_competition)
    else -> code
}

/** Idioma vigente de la configuración: observable, a diferencia de `Locale.getDefault()`. */
@Composable
@ReadOnlyComposable
private fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

/** Formatos localizados en la zona del dispositivo; no alteran el dato fuente. */
internal object SuggestionsFormat {
    fun dateTime(instant: Instant, zone: ZoneId, locale: Locale): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zone).format(instant)

    fun date(instant: Instant, zone: ZoneId, locale: Locale): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).withZone(zone).format(instant)

    fun day(day: DayOfWeek, locale: Locale): String =
        day.getDisplayName(TextStyle.FULL, locale).replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
}
