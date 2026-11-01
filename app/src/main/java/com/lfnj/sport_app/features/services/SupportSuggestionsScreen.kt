package com.lfnj.sport_app.features.services

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lfnj.sport_app.R
import com.lfnj.sport_app.features.services.model.BillingUnit
import com.lfnj.sport_app.features.services.model.FeeFormat
import com.lfnj.sport_app.features.services.model.ServiceFee
import com.lfnj.sport_app.features.services.model.SessionTimes
import com.lfnj.sport_app.features.services.model.SupportCategory
import com.lfnj.sport_app.features.services.model.SupportInterval
import com.lfnj.sport_app.features.services.model.SupportModality
import com.lfnj.sport_app.features.services.model.SupportReason
import com.lfnj.sport_app.features.services.model.SupportServiceSuggestion
import com.lfnj.sport_app.features.services.model.SupportSourceStatus
import com.lfnj.sport_app.features.services.model.SupportSuggestionsSnapshot
import java.time.ZoneId
import java.util.Locale

/**
 * «Acompañamiento y logística» (HU029): servicios publicados de entrenamiento, transporte y
 * acompañamiento con proveedor, categoría, modalidad, tarifa informativa, próximo intervalo en la
 * zona del dispositivo y motivo; detalle informativo. Explica la lista vacía, la falta de
 * inscripciones o de Eventos y la antigüedad de la última lista confirmada. No contrata ni reserva.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportSuggestionsScreen(
    state: SupportSuggestionsUiState,
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
                title = { Text(stringResource(if (detail == null) R.string.support_suggestions_title else R.string.support_suggestions_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = if (detail == null) onBack else onCloseDetail, modifier = Modifier.testTag("support_suggestions_back")) {
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
                .testTag("support_suggestions_screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val snapshot = state.snapshot
            when {
                state.loading -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.testTag("support_suggestions_loading"))
                    Text(stringResource(R.string.support_suggestions_loading))
                }
                snapshot == null -> WithoutSnapshot(state, onRetry)
                detail != null -> {
                    SyncStatus(state, snapshot, onRetry)
                    ServiceDetail(detail, state.zone)
                }
                else -> {
                    SyncStatus(state, snapshot, onRetry)
                    Context(snapshot)
                    if (state.empty) {
                        Text(
                            stringResource(if (snapshot.missingRegistrations) R.string.support_suggestions_empty_without_registrations else R.string.support_suggestions_empty),
                            Modifier
                                .testTag("support_suggestions_empty")
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    } else {
                        Text(stringResource(R.string.support_suggestions_device_zone, state.zone.id), style = MaterialTheme.typography.bodySmall)
                        Notice(R.string.support_suggestions_fee_informative, "support_suggestions_fee_informative")
                        snapshot.items.forEach { ServiceCard(it, state.zone, onOpenDetail) }
                    }
                }
            }
        }
    }
}

/** Sin lista confirmada: nunca sincronizada sin red, error o acceso rechazado. */
@Composable
private fun WithoutSnapshot(state: SupportSuggestionsUiState, onRetry: () -> Unit) {
    if (state.neverSynced) {
        Notice(R.string.support_suggestions_never_synced, "support_suggestions_never_synced")
    } else {
        Notice(errorMessage(state.problem), "support_suggestions_error", MaterialTheme.colorScheme.error)
    }
    Button(onClick = onRetry, enabled = !state.refreshing, modifier = Modifier.testTag("support_suggestions_retry")) {
        Text(stringResource(R.string.action_retry))
    }
}

private fun errorMessage(problem: SupportSuggestionsProblem?): Int = when (problem) {
    SupportSuggestionsProblem.CAPABILITY_REQUIRED -> R.string.support_suggestions_error_capability_required
    SupportSuggestionsProblem.PERMISSION_REQUIRED -> R.string.support_suggestions_error_permission_required
    SupportSuggestionsProblem.INVALID_REQUEST -> R.string.support_suggestions_error_invalid_request
    SupportSuggestionsProblem.INVALID_RESPONSE -> R.string.support_suggestions_error_invalid_response
    SupportSuggestionsProblem.STORAGE -> R.string.support_suggestions_error_storage
    SupportSuggestionsProblem.NO_CONNECTION, SupportSuggestionsProblem.UNAVAILABLE, null -> R.string.support_suggestions_error_unavailable
}

/** Actualización en curso, antigüedad y conectividad de la lista mostrada. */
@Composable
private fun SyncStatus(state: SupportSuggestionsUiState, snapshot: SupportSuggestionsSnapshot, onRetry: () -> Unit) {
    if (state.refreshing) {
        val refreshing = stringResource(R.string.support_suggestions_refreshing)
        LinearProgressIndicator(
            Modifier
                .fillMaxWidth()
                .testTag("support_suggestions_refreshing")
                .semantics { contentDescription = refreshing },
        )
    }
    Text(
        stringResource(R.string.support_suggestions_synced_at, SessionTimes.dateTime(snapshot.syncedAt, state.zone, currentLocale())),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("support_suggestions_synced_at"),
    )
    if (state.stale) Notice(R.string.support_suggestions_stale, "support_suggestions_stale", MaterialTheme.colorScheme.error)
    when (state.problem) {
        null -> Unit
        SupportSuggestionsProblem.NO_CONNECTION -> Notice(R.string.support_suggestions_offline_with_cache, "support_suggestions_problem")
        else -> {
            Notice(R.string.support_suggestions_unavailable_with_cache, "support_suggestions_problem")
            OutlinedButton(onClick = onRetry, enabled = !state.refreshing, modifier = Modifier.testTag("support_suggestions_retry")) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

/**
 * Qué contexto se evaluó (CA1, CA2, CA5): la prioridad por eventos inscritos solo se afirma con
 * `personalized: true`; se explica la falta de inscripciones y la de Eventos o Usuarios.
 */
@Composable
private fun Context(snapshot: SupportSuggestionsSnapshot) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (snapshot.personalized) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(if (snapshot.personalized) "support_suggestions_personalized" else "support_suggestions_not_personalized")
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(if (snapshot.personalized) R.string.support_suggestions_personalized else R.string.support_suggestions_not_personalized),
                style = MaterialTheme.typography.titleSmall,
            )
            when {
                snapshot.eventsUnavailable -> Text(stringResource(R.string.support_suggestions_events_unavailable), Modifier.testTag("support_suggestions_events_unavailable"))
                snapshot.missingRegistrations -> Text(stringResource(R.string.support_suggestions_missing_registrations), Modifier.testTag("support_suggestions_missing_event_registrations"))
            }
            snapshot.missingContext.filter { it != SupportSuggestionsSnapshot.MISSING_EVENT_REGISTRATIONS }.forEach { code ->
                Text(stringResource(R.string.support_suggestions_missing_other), Modifier.testTag("support_suggestions_missing_$code"))
            }
            if (snapshot.usersSource == SupportSourceStatus.UNAVAILABLE) {
                Text(stringResource(R.string.support_suggestions_users_unavailable), Modifier.testTag("support_suggestions_users_unavailable"))
            }
        }
    }
}

/** Tarjeta de un servicio: proveedor, categoría, modalidad, tarifa, próximo intervalo y motivo (CA3). */
@Composable
private fun ServiceCard(item: SupportServiceSuggestion, zone: ZoneId, onOpenDetail: (String) -> Unit) {
    val openDescription = stringResource(R.string.support_suggestions_open_detail, item.title)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = openDescription) { onOpenDetail(item.serviceId) }
            .testTag("support_suggestion_${item.serviceId}")
            .semantics(mergeDescendants = true) { },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            Provider(item)
            Text(stringResource(R.string.support_suggestions_summary_line, categoryLabel(item.category), modalityLabel(item.modality)))
            Text(feeLabel(item.fee))
            Text(nextIntervalLabel(item.nextInterval, zone), Modifier.testTag("support_suggestion_next_interval"))
            Reasons(item)
        }
    }
}

@Composable
private fun Provider(item: SupportServiceSuggestion) {
    val author = item.authorName
    Text(
        if (author != null) stringResource(R.string.support_suggestions_provider, author) else stringResource(R.string.support_suggestions_provider_unknown),
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun Reasons(item: SupportServiceSuggestion) {
    item.knownReasons.forEach { reason ->
        Text(
            stringResource(
                when (reason) {
                    SupportReason.EVENT_LOGISTICS -> R.string.support_suggestions_reason_event_logistics
                    SupportReason.UPCOMING_AVAILABILITY -> R.string.support_suggestions_reason_upcoming_availability
                }
            ),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.testTag("support_suggestion_reason_${reason.wire}"),
        )
    }
}

/** Detalle informativo del servicio; sin acciones de contratar ni reservar. */
@Composable
private fun ServiceDetail(item: SupportServiceSuggestion, zone: ZoneId) {
    val locale = currentLocale()
    val interval = item.nextInterval
    Column(Modifier.testTag("support_suggestion_detail"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(item.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Provider(item)
        Text(stringResource(R.string.support_suggestions_field_category, categoryLabel(item.category)))
        Text(stringResource(R.string.support_suggestions_field_modality, modalityLabel(item.modality)))
        Text(feeLabel(item.fee), Modifier.testTag("support_suggestion_fee"))
        Notice(R.string.support_suggestions_fee_informative, "support_suggestions_fee_informative")
        SectionTitle(R.string.support_suggestions_interval_title)
        Text(SessionTimes.range(interval.startsAt, interval.endsAt, zone, locale), Modifier.testTag("support_suggestion_detail_interval"))
        Text(stringResource(R.string.support_suggestions_device_zone, zone.id), style = MaterialTheme.typography.bodySmall)
        Text(
            if (SessionTimes.sameOffset(interval.startsAt, zone, interval.timeZone)) {
                stringResource(R.string.support_suggestions_provider_zone_same, interval.timeZone.id)
            } else {
                stringResource(R.string.support_suggestions_provider_zone, interval.timeZone.id, SessionTimes.range(interval.startsAt, interval.endsAt, interval.timeZone, locale))
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("support_suggestion_provider_zone"),
        )
        if (item.knownReasons.isNotEmpty()) {
            SectionTitle(R.string.support_suggestions_reasons_title)
            Reasons(item)
        }
        SectionTitle(R.string.support_suggestions_description_title)
        Text(item.summary, Modifier.testTag("support_suggestion_description"))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("support_suggestion_hire_info")
                .semantics(mergeDescendants = true) { },
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.support_suggestions_hire_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.support_suggestions_hire_body))
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

/** «Próximo intervalo: …» en la zona del dispositivo. */
@Composable
private fun nextIntervalLabel(interval: SupportInterval, zone: ZoneId): String =
    stringResource(R.string.support_suggestions_next_interval, SessionTimes.range(interval.startsAt, interval.endsAt, zone, currentLocale()))

/** Un código que esta versión no conoce se muestra tal cual: nunca se reinterpreta como otro valor. */
@Composable
private fun categoryLabel(code: String): String = when (SupportCategory.fromWire(code)) {
    SupportCategory.COACHING -> stringResource(R.string.support_suggestions_category_coaching)
    SupportCategory.TRANSPORT -> stringResource(R.string.support_suggestions_category_transport)
    SupportCategory.ACCOMPANIMENT -> stringResource(R.string.support_suggestions_category_accompaniment)
    null -> code
}

@Composable
private fun modalityLabel(code: String): String = when (SupportModality.fromWire(code)) {
    SupportModality.IN_PERSON -> stringResource(R.string.support_suggestions_modality_in_person)
    SupportModality.VIRTUAL -> stringResource(R.string.support_suggestions_modality_virtual)
    SupportModality.HYBRID -> stringResource(R.string.support_suggestions_modality_hybrid)
    null -> code
}

/** «Tarifa informativa: 80.000 COP por servicio». */
@Composable
private fun feeLabel(fee: ServiceFee): String {
    val amount = FeeFormat.amount(fee, currentLocale())
    val unit = when (BillingUnit.fromWire(fee.billingUnit)) {
        BillingUnit.SERVICE -> stringResource(R.string.support_suggestions_unit_service)
        BillingUnit.SESSION -> stringResource(R.string.support_suggestions_unit_session)
        BillingUnit.HOUR -> stringResource(R.string.support_suggestions_unit_hour)
        BillingUnit.DAY -> stringResource(R.string.support_suggestions_unit_day)
        null -> fee.billingUnit
    }
    return stringResource(R.string.support_suggestions_fee, amount, unit)
}

/** Idioma vigente de la configuración: observable, a diferencia de `Locale.getDefault()`. */
@Composable
@ReadOnlyComposable
private fun currentLocale(): Locale = LocalConfiguration.current.locales[0]
