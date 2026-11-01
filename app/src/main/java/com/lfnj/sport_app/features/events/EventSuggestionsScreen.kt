package com.lfnj.sport_app.features.events

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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lfnj.sport_app.R
import com.lfnj.sport_app.features.events.model.EventMissingContext
import com.lfnj.sport_app.features.events.model.EventSuggestion
import com.lfnj.sport_app.features.events.model.EventSuggestionReason
import com.lfnj.sport_app.features.events.model.EventSuggestionsSnapshot
import java.time.ZoneId

/**
 * «Eventos sugeridos» (HU027): eventos publicados con motivo, fechas en la zona del dispositivo y
 * zona declarada, lugar, tarifa informativa (o «sin tarifa»), cupo declarado y «Ya inscrito».
 * Indica si la lista no es personalizada y qué dato falta, y la antigüedad de la última lista
 * confirmada. Tocar un evento abre su detalle de HU041 ([onOpenEvent] recibe el `event_id`);
 * esta pantalla no inscribe.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventSuggestionsScreen(
    state: EventSuggestionsUiState,
    onOpenEvent: (String) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.event_suggestions_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("event_suggestions_back")) {
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
                .testTag("event_suggestions_screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val snapshot = state.snapshot
            when {
                state.loading -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.testTag("event_suggestions_loading"))
                    Text(stringResource(R.string.event_suggestions_loading))
                }
                snapshot == null -> WithoutSnapshot(state, onRetry)
                else -> {
                    SyncStatus(state, snapshot, onRetry)
                    Personalization(snapshot)
                    Text(
                        stringResource(R.string.event_suggestions_notice),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("event_suggestions_notice"),
                    )
                    if (state.empty) {
                        Text(
                            stringResource(if (snapshot.personalized) R.string.event_suggestions_empty_personalized else R.string.event_suggestions_empty),
                            Modifier.testTag("event_suggestions_empty"),
                        )
                    } else {
                        snapshot.items.forEach { EventCard(it, state.zone, onOpenEvent) }
                    }
                }
            }
        }
    }
}

/** Sin lista confirmada: nunca sincronizada sin red, error o acceso rechazado. */
@Composable
private fun WithoutSnapshot(state: EventSuggestionsUiState, onRetry: () -> Unit) {
    if (state.neverSynced) {
        Notice(R.string.event_suggestions_never_synced, "event_suggestions_never_synced")
    } else {
        Notice(errorMessage(state.problem), "event_suggestions_error", MaterialTheme.colorScheme.error)
    }
    Button(onClick = onRetry, enabled = !state.refreshing, modifier = Modifier.testTag("event_suggestions_retry")) {
        Text(stringResource(R.string.action_retry))
    }
}

private fun errorMessage(problem: EventSuggestionsProblem?): Int = when (problem) {
    EventSuggestionsProblem.CAPABILITY_REQUIRED -> R.string.event_suggestions_error_capability_required
    EventSuggestionsProblem.PERMISSION_REQUIRED -> R.string.event_suggestions_error_permission_required
    EventSuggestionsProblem.INVALID_REQUEST -> R.string.event_suggestions_error_invalid_request
    EventSuggestionsProblem.INVALID_RESPONSE -> R.string.event_suggestions_error_invalid_response
    EventSuggestionsProblem.STORAGE -> R.string.event_suggestions_error_storage
    EventSuggestionsProblem.NO_CONNECTION, EventSuggestionsProblem.UNAVAILABLE, null -> R.string.event_suggestions_error_unavailable
}

/** Actualización en curso, antigüedad y conectividad de la lista mostrada. */
@Composable
private fun SyncStatus(state: EventSuggestionsUiState, snapshot: EventSuggestionsSnapshot, onRetry: () -> Unit) {
    if (state.refreshing) {
        val refreshing = stringResource(R.string.event_suggestions_refreshing)
        LinearProgressIndicator(
            Modifier
                .fillMaxWidth()
                .testTag("event_suggestions_refreshing")
                .semantics { contentDescription = refreshing },
        )
    }
    Text(
        stringResource(R.string.event_suggestions_synced_at, EventFormat.dateTime(snapshot.syncedAt, state.zone, EventFormat.currentLocale())),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("event_suggestions_synced_at"),
    )
    if (state.stale) Notice(R.string.event_suggestions_stale, "event_suggestions_stale", MaterialTheme.colorScheme.error)
    when (state.problem) {
        null -> Unit
        EventSuggestionsProblem.NO_CONNECTION -> Notice(R.string.event_suggestions_offline_with_cache, "event_suggestions_problem")
        else -> {
            Notice(R.string.event_suggestions_unavailable_with_cache, "event_suggestions_problem")
            OutlinedButton(onClick = onRetry, enabled = !state.refreshing, modifier = Modifier.testTag("event_suggestions_retry")) {
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
private fun Personalization(snapshot: EventSuggestionsSnapshot) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (snapshot.personalized) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(if (snapshot.personalized) "event_suggestions_personalized" else "event_suggestions_not_personalized")
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(if (snapshot.personalized) R.string.event_suggestions_personalized else R.string.event_suggestions_not_personalized),
                style = MaterialTheme.typography.titleSmall,
            )
            if (snapshot.contextUnavailable) {
                Text(stringResource(R.string.event_suggestions_context_unavailable), Modifier.testTag("event_suggestions_context_unavailable"))
            } else {
                snapshot.missingContext.forEach { code ->
                    val text = when (EventMissingContext.fromWire(code)) {
                        EventMissingContext.SPORTS -> R.string.event_suggestions_missing_sports
                        EventMissingContext.CITY -> R.string.event_suggestions_missing_city
                        null -> R.string.event_suggestions_missing_other
                    }
                    Text(stringResource(text), Modifier.testTag("event_suggestions_missing_$code"))
                }
            }
        }
    }
}

/** Tarjeta de un evento: motivo, fechas y zona, lugar, tarifa, cupo e inscripción propia (CA1, CA3, CA4). */
@Composable
private fun EventCard(item: EventSuggestion, zone: ZoneId, onOpenEvent: (String) -> Unit) {
    val locale = EventFormat.currentLocale()
    val openDescription = stringResource(R.string.event_suggestions_open_detail, item.title)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = openDescription) { onOpenEvent(item.eventId) }
            .testTag("event_suggestion_${item.eventId}")
            .semantics(mergeDescendants = true) { },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            if (item.registered) RegisteredBadge(item.eventId)
            Reasons(item)
            val (start, end) = EventFormat.range(item.startsAt, item.endsAt, zone, locale)
            Text(stringResource(R.string.event_suggestions_dates, start, end), Modifier.testTag("event_suggestion_dates_${item.eventId}"))
            Text(
                stringResource(R.string.event_suggestions_time_zone, item.timeZone),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("event_suggestion_time_zone_${item.eventId}"),
            )
            Text(stringResource(R.string.event_suggestions_discipline, item.discipline))
            Text(
                stringResource(R.string.event_suggestions_place, item.location.name, item.location.city, item.location.countryCode),
                Modifier.testTag("event_suggestion_place_${item.eventId}"),
            )
            Fee(item)
            Text(stringResource(R.string.event_suggestions_capacity, item.capacity), Modifier.testTag("event_suggestion_capacity_${item.eventId}"))
        }
    }
}

/** «Ya inscrito» cuando `my_registration` no es nulo: no se invita a inscribirse de nuevo (CA3). */
@Composable
private fun RegisteredBadge(eventId: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.testTag("event_suggestion_registered_$eventId"),
    ) {
        Text(
            stringResource(R.string.event_suggestions_registered),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** Tarifa informativa de HU025 o «sin tarifa»; nunca se presenta como un cobro (CA4). */
@Composable
private fun Fee(item: EventSuggestion) {
    val fee = item.fee
    Column(Modifier.testTag("event_suggestion_fee_${item.eventId}"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        when {
            fee == null -> Text(stringResource(R.string.event_suggestions_no_fee))
            item.free -> Text(stringResource(R.string.event_suggestions_fee_free))
            else -> Text(stringResource(R.string.event_suggestions_fee, fee.amount, fee.currency))
        }
        if (fee != null) Text(stringResource(R.string.event_suggestions_fee_notice), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Reasons(item: EventSuggestion) {
    item.knownReasons.forEach { reason ->
        Text(
            stringResource(
                when (reason) {
                    EventSuggestionReason.SPORT_MATCH -> R.string.event_suggestions_reason_sport_match
                    EventSuggestionReason.CITY_MATCH -> R.string.event_suggestions_reason_city_match
                    EventSuggestionReason.UPCOMING -> R.string.event_suggestions_reason_upcoming
                }
            ),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.testTag("event_suggestion_reason_${item.eventId}_${reason.wire}"),
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
