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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lfnj.sport_app.R
import com.lfnj.sport_app.features.services.model.Appointment
import com.lfnj.sport_app.features.services.model.AppointmentStatus
import com.lfnj.sport_app.features.services.model.AvailabilitySlot
import com.lfnj.sport_app.features.services.model.BillingUnit
import com.lfnj.sport_app.features.services.model.CoachingService
import com.lfnj.sport_app.features.services.model.FeeFormat
import com.lfnj.sport_app.features.services.model.ServiceFee
import com.lfnj.sport_app.features.services.model.ServiceModality
import com.lfnj.sport_app.features.services.model.SessionTimes
import com.lfnj.sport_app.features.services.model.VirtualSessionsRules
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * «Sesiones virtuales» (HU040, ADR-046 §8): entrenadores `COACHING` virtuales con disponibilidad,
 * intervalos en la zona del dispositivo con la del profesional visible, confirmación solo con
 * conectividad, «Mis sesiones» y cancelación con la ventana de 24 h explicada. La tarifa es
 * informativa y el enlace de la sesión lo coordina el profesional (`meeting_url` nulo).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VirtualSessionsScreen(
    state: VirtualSessionsUiState,
    onSelectTab: (VirtualSessionsTab) -> Unit,
    onOpenService: (String) -> Unit,
    onCloseService: () -> Unit,
    onChooseSlot: (String) -> Unit,
    onConfirmBooking: () -> Unit,
    onDismissBooking: () -> Unit,
    onRequestCancel: (String) -> Unit,
    onConfirmCancel: () -> Unit,
    onDismissCancel: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val detail = state.selected?.takeIf { state.tab == VirtualSessionsTab.COACHES }
    BackHandler(enabled = detail != null, onBack = onCloseService)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.virtual_sessions_title)) },
                navigationIcon = {
                    IconButton(onClick = if (detail == null) onBack else onCloseService, modifier = Modifier.testTag("virtual_sessions_back")) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
                Tab(
                    selected = state.tab == VirtualSessionsTab.COACHES,
                    onClick = { onSelectTab(VirtualSessionsTab.COACHES) },
                    text = { Text(stringResource(R.string.virtual_sessions_tab_coaches)) },
                    modifier = Modifier.testTag("virtual_sessions_tab_coaches"),
                )
                Tab(
                    selected = state.tab == VirtualSessionsTab.MY_SESSIONS,
                    onClick = { onSelectTab(VirtualSessionsTab.MY_SESSIONS) },
                    text = { Text(stringResource(R.string.virtual_sessions_tab_mine)) },
                    modifier = Modifier.testTag("virtual_sessions_tab_mine"),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
                    .testTag("virtual_sessions_screen"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.notice?.let { NoticeCard(it, state.zone) }
                if (state.offline) Notice(R.string.virtual_sessions_offline, "virtual_sessions_offline")
                when {
                    state.tab == VirtualSessionsTab.MY_SESSIONS -> MySessions(state, onRequestCancel, onRetry)
                    detail != null -> ServiceDetail(detail, state, onChooseSlot)
                    else -> Coaches(state, onOpenService, onRetry)
                }
            }
        }
    }
    state.booking?.let { BookingDialog(it, state, onConfirmBooking, onDismissBooking) }
    state.cancelling?.let { CancelDialog(it, state, onConfirmCancel, onDismissCancel) }
}

// ----------------------------------------------------------------------- entrenadores

@Composable
private fun Coaches(state: VirtualSessionsUiState, onOpenService: (String) -> Unit, onRetry: () -> Unit) {
    val catalog = state.catalog
    when {
        state.catalogLoading -> Loading(R.string.virtual_sessions_loading_coaches, "virtual_sessions_loading")
        catalog == null -> WithoutData(state.catalogProblem, state.catalogRefreshing, onRetry, R.string.virtual_sessions_never_synced_coaches)
        else -> {
            SyncStatus(catalog.syncedAt, state.zone, state.catalogStale, state.catalogRefreshing, state.catalogProblem, onRetry, "virtual_sessions_catalog")
            Notice(R.string.virtual_sessions_fee_informative, "virtual_sessions_fee_informative")
            if (catalog.truncated) Notice(R.string.virtual_sessions_catalog_truncated, "virtual_sessions_catalog_truncated")
            if (state.catalogEmpty) {
                Text(stringResource(R.string.virtual_sessions_empty_coaches), Modifier.testTag("virtual_sessions_empty_coaches"))
            } else {
                catalog.services.forEach { ServiceCard(it, state, onOpenService) }
            }
        }
    }
}

/** Tarjeta de un servicio: nombre, modalidad, tarifa informativa y próximos horarios. */
@Composable
private fun ServiceCard(service: CoachingService, state: VirtualSessionsUiState, onOpenService: (String) -> Unit) {
    val open = stringResource(R.string.virtual_sessions_open_service, service.name)
    val next = service.slots.firstOrNull { !it.startsAt.isBefore(state.now) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = open) { onOpenService(service.serviceId) }
            .testTag("virtual_service_${service.serviceId}")
            .semantics(mergeDescendants = true) { },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(service.name, style = MaterialTheme.typography.titleMedium)
            Text(modalityLabel(service.modality.wire))
            Text(feeLabel(service.fee))
            Text(pluralStringResource(R.plurals.virtual_sessions_slots_count, service.slots.size, service.slots.size))
            next?.let { Text(stringResource(R.string.virtual_sessions_next_slot, SessionTimes.range(it.startsAt, it.endsAt, state.zone, currentLocale()))) }
        }
    }
}

/** Detalle del servicio con sus intervalos reservables. */
@Composable
private fun ServiceDetail(service: CoachingService, state: VirtualSessionsUiState, onChooseSlot: (String) -> Unit) {
    Column(Modifier.testTag("virtual_service_detail"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(service.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(modalityLabel(service.modality.wire))
        if (service.description.isNotBlank()) Text(service.description, Modifier.testTag("virtual_service_description"))
        Text(feeLabel(service.fee), Modifier.testTag("virtual_service_fee"))
        Notice(R.string.virtual_sessions_fee_informative, "virtual_sessions_fee_informative")
        Notice(R.string.virtual_sessions_meeting_link, "virtual_service_meeting_link")
        SectionTitle(R.string.virtual_sessions_slots_title)
        Text(stringResource(R.string.virtual_sessions_device_zone, state.zone.id), style = MaterialTheme.typography.bodySmall)
        if (service.slots.isEmpty()) {
            Text(stringResource(R.string.virtual_sessions_no_slots), Modifier.testTag("virtual_service_no_slots"))
        }
        service.slots.forEach { SlotRow(it, state, onChooseSlot) }
    }
}

@Composable
private fun SlotRow(slot: AvailabilitySlot, state: VirtualSessionsUiState, onChooseSlot: (String) -> Unit) {
    val locale = currentLocale()
    val booked = state.activeAppointmentIn(slot.availabilityId) != null
    val leadOk = VirtualSessionsRules.isBookable(slot.startsAt, state.now)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("virtual_slot_${slot.availabilityId}"),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(SessionTimes.range(slot.startsAt, slot.endsAt, state.zone, locale), style = MaterialTheme.typography.titleSmall)
            ProviderTime(slot.startsAt, slot.endsAt, slot.timeZone, state.zone)
            Text(pluralStringResource(R.plurals.virtual_sessions_capacity, slot.capacity, slot.capacity), style = MaterialTheme.typography.bodySmall)
            when {
                booked -> Text(stringResource(R.string.virtual_sessions_slot_already_booked), Modifier.testTag("virtual_slot_booked_${slot.availabilityId}"))
                !leadOk -> Text(stringResource(R.string.virtual_sessions_slot_too_soon), Modifier.testTag("virtual_slot_too_soon_${slot.availabilityId}"))
                else -> {
                    val description = stringResource(R.string.virtual_sessions_book_description, SessionTimes.range(slot.startsAt, slot.endsAt, state.zone, locale))
                    Button(
                        onClick = { onChooseSlot(slot.availabilityId) },
                        modifier = Modifier
                            .testTag("virtual_slot_book_${slot.availabilityId}")
                            .semantics { contentDescription = description },
                    ) {
                        Text(stringResource(R.string.virtual_sessions_book))
                    }
                }
            }
        }
    }
}

/** Confirmación de la reserva: solo con conectividad y con la respuesta del backend (ADR-046 §8). */
@Composable
private fun BookingDialog(draft: BookingDraft, state: VirtualSessionsUiState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val locale = currentLocale()
    val retryable = draft.problem == BookingProblem.UNKNOWN_OUTCOME
    val terminal = draft.problem in TERMINAL_BOOKING_PROBLEMS
    val canSend = !draft.sending && !terminal && (!state.offline || retryable)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("virtual_booking_dialog"),
        title = { Text(stringResource(R.string.virtual_sessions_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(draft.service.name, style = MaterialTheme.typography.titleMedium)
                Text(SessionTimes.range(draft.slot.startsAt, draft.slot.endsAt, state.zone, locale), Modifier.testTag("virtual_booking_time"))
                ProviderTime(draft.slot.startsAt, draft.slot.endsAt, draft.slot.timeZone, state.zone)
                Text(feeLabel(draft.service.fee))
                Text(stringResource(R.string.virtual_sessions_fee_informative))
                Text(stringResource(R.string.virtual_sessions_meeting_link))
                Text(
                    stringResource(
                        R.string.virtual_sessions_cancel_policy,
                        SessionTimes.dateTime(VirtualSessionsRules.cancellationDeadline(draft.slot.startsAt), state.zone, locale),
                    ),
                    Modifier.testTag("virtual_booking_cancel_policy"),
                )
                if (draft.sending) {
                    val sending = stringResource(R.string.virtual_sessions_booking_sending)
                    LinearProgressIndicator(
                        Modifier
                            .fillMaxWidth()
                            .testTag("virtual_booking_sending")
                            .semantics { contentDescription = sending },
                    )
                }
                if (state.offline && draft.problem == null) {
                    Notice(R.string.virtual_sessions_booking_requires_connection, "virtual_booking_requires_connection", MaterialTheme.colorScheme.error)
                }
                draft.problem?.let { Notice(bookingMessage(it), "virtual_booking_problem_${it.name.lowercase(Locale.ROOT)}", MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (!terminal) {
                Button(onClick = onConfirm, enabled = canSend, modifier = Modifier.testTag("virtual_booking_confirm")) {
                    Text(stringResource(if (retryable) R.string.action_retry else R.string.virtual_sessions_confirm))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !draft.sending, modifier = Modifier.testTag("virtual_booking_dismiss")) {
                Text(stringResource(if (terminal) R.string.virtual_sessions_choose_other else R.string.virtual_sessions_close))
            }
        },
    )
}

/** Resultados definitivos: no se vuelve a enviar el mismo intento. */
private val TERMINAL_BOOKING_PROBLEMS = setOf(
    BookingProblem.FULL, BookingProblem.ALREADY_BOOKED, BookingProblem.CAPABILITY_REQUIRED, BookingProblem.PERMISSION_REQUIRED,
    BookingProblem.SERVICE_NOT_BOOKABLE, BookingProblem.SLOT_NOT_BOOKABLE, BookingProblem.INVALID,
)

private fun bookingMessage(problem: BookingProblem): Int = when (problem) {
    BookingProblem.OFFLINE -> R.string.virtual_sessions_booking_requires_connection
    BookingProblem.FULL -> R.string.virtual_sessions_booking_full
    BookingProblem.ALREADY_BOOKED -> R.string.virtual_sessions_booking_exists
    BookingProblem.CAPABILITY_REQUIRED -> R.string.virtual_sessions_capability_required
    BookingProblem.PERMISSION_REQUIRED -> R.string.virtual_sessions_permission_required
    BookingProblem.SERVICE_NOT_BOOKABLE -> R.string.virtual_sessions_service_not_bookable
    BookingProblem.SLOT_NOT_BOOKABLE -> R.string.virtual_sessions_slot_not_bookable
    BookingProblem.KEY_RENEWED -> R.string.virtual_sessions_booking_key_renewed
    BookingProblem.UNKNOWN_OUTCOME -> R.string.virtual_sessions_booking_unknown
    BookingProblem.INVALID -> R.string.virtual_sessions_booking_invalid
}

// ----------------------------------------------------------------------- mis sesiones

@Composable
private fun MySessions(state: VirtualSessionsUiState, onRequestCancel: (String) -> Unit, onRetry: () -> Unit) {
    val snapshot = state.appointments
    when {
        state.appointmentsLoading -> Loading(R.string.virtual_sessions_loading_mine, "virtual_sessions_loading_mine")
        snapshot == null -> WithoutData(state.appointmentsProblem, state.appointmentsRefreshing, onRetry, R.string.virtual_sessions_never_synced_mine)
        else -> {
            SyncStatus(snapshot.syncedAt, state.zone, state.appointmentsStale, state.appointmentsRefreshing, state.appointmentsProblem, onRetry, "virtual_sessions_mine")
            val upcoming = snapshot.upcoming(state.now)
            val history = snapshot.history(state.now)
            SectionTitle(R.string.virtual_sessions_upcoming_title)
            if (upcoming.isEmpty()) {
                Text(stringResource(R.string.virtual_sessions_empty_mine), Modifier.testTag("virtual_sessions_empty_mine"))
            }
            upcoming.forEach { AppointmentCard(it, state, onRequestCancel) }
            if (history.isNotEmpty()) {
                SectionTitle(R.string.virtual_sessions_history_title)
                history.forEach { AppointmentCard(it, state, onRequestCancel) }
            }
        }
    }
}

/** Cita con su estado, horario, nota del enlace y la ventana de cancelación explicada (CA4, CA6). */
@Composable
private fun AppointmentCard(item: Appointment, state: VirtualSessionsUiState, onRequestCancel: (String) -> Unit) {
    val locale = currentLocale()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("virtual_appointment_${item.appointmentId}"),
        colors = CardDefaults.cardColors(
            containerColor = if (item.isUpcoming(state.now)) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.serviceName, style = MaterialTheme.typography.titleMedium)
            Text(statusLabel(item, state.now), Modifier.testTag("virtual_appointment_status_${item.appointmentId}"), style = MaterialTheme.typography.labelLarge)
            Text(SessionTimes.range(item.startsAt, item.endsAt, state.zone, locale))
            ProviderTime(item.startsAt, item.endsAt, item.timeZone, state.zone)
            Text(feeLabel(item.fee), style = MaterialTheme.typography.bodySmall)
            item.cancelledAt?.let {
                Text(stringResource(R.string.virtual_sessions_cancelled_at, SessionTimes.dateTime(it, state.zone, locale)), style = MaterialTheme.typography.bodySmall)
            }
            if (item.isUpcoming(state.now)) {
                Text(stringResource(R.string.virtual_sessions_meeting_link), Modifier.testTag("virtual_appointment_meeting_${item.appointmentId}"))
                val deadline = SessionTimes.dateTime(item.cancellationDeadline, state.zone, locale)
                if (item.canCancel(state.now)) {
                    Text(stringResource(R.string.virtual_sessions_cancel_until, deadline), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = { onRequestCancel(item.appointmentId) },
                        modifier = Modifier.testTag("virtual_appointment_cancel_${item.appointmentId}"),
                    ) {
                        Text(stringResource(R.string.virtual_sessions_cancel))
                    }
                } else {
                    Text(
                        stringResource(R.string.virtual_sessions_cancel_window_closed_hint, deadline),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("virtual_appointment_window_closed_${item.appointmentId}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun CancelDialog(draft: CancelDraft, state: VirtualSessionsUiState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val locale = currentLocale()
    val retryable = draft.problem == CancelProblem.UNKNOWN_OUTCOME
    val terminal = draft.problem in setOf(CancelProblem.WINDOW_CLOSED, CancelProblem.NOT_FOUND, CancelProblem.PERMISSION_REQUIRED, CancelProblem.INVALID)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("virtual_cancel_dialog"),
        title = { Text(stringResource(R.string.virtual_sessions_cancel_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(draft.appointment.serviceName, style = MaterialTheme.typography.titleMedium)
                Text(SessionTimes.range(draft.appointment.startsAt, draft.appointment.endsAt, state.zone, locale))
                Text(stringResource(R.string.virtual_sessions_cancel_explanation))
                if (draft.sending) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("virtual_cancel_sending"))
                if (state.offline && draft.problem == null) {
                    Notice(R.string.virtual_sessions_cancel_requires_connection, "virtual_cancel_requires_connection", MaterialTheme.colorScheme.error)
                }
                draft.problem?.let { Notice(cancelMessage(it), "virtual_cancel_problem_${it.name.lowercase(Locale.ROOT)}", MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (!terminal) {
                Button(
                    onClick = onConfirm,
                    enabled = !draft.sending && (!state.offline || retryable),
                    modifier = Modifier.testTag("virtual_cancel_confirm"),
                ) {
                    Text(stringResource(if (retryable) R.string.action_retry else R.string.virtual_sessions_cancel_confirm))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !draft.sending, modifier = Modifier.testTag("virtual_cancel_dismiss")) {
                Text(stringResource(R.string.virtual_sessions_keep))
            }
        },
    )
}

private fun cancelMessage(problem: CancelProblem): Int = when (problem) {
    CancelProblem.OFFLINE -> R.string.virtual_sessions_cancel_requires_connection
    CancelProblem.WINDOW_CLOSED -> R.string.virtual_sessions_cancel_window_closed
    CancelProblem.NOT_FOUND -> R.string.virtual_sessions_cancel_not_found
    CancelProblem.PERMISSION_REQUIRED -> R.string.virtual_sessions_permission_required
    CancelProblem.UNKNOWN_OUTCOME -> R.string.virtual_sessions_cancel_unknown
    CancelProblem.INVALID -> R.string.virtual_sessions_cancel_invalid
}

// ----------------------------------------------------------------------- comunes

@Composable
private fun NoticeCard(notice: VirtualSessionsNotice, zone: ZoneId) {
    val locale = currentLocale()
    val text = when (notice) {
        is VirtualSessionsNotice.Booked ->
            stringResource(R.string.virtual_sessions_booked_notice, notice.appointment.serviceName, SessionTimes.dateTime(notice.appointment.startsAt, zone, locale))
        is VirtualSessionsNotice.Cancelled ->
            stringResource(R.string.virtual_sessions_cancelled_notice, notice.appointment.serviceName, SessionTimes.dateTime(notice.appointment.startsAt, zone, locale))
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(if (notice is VirtualSessionsNotice.Booked) "virtual_sessions_booked_notice" else "virtual_sessions_cancelled_notice")
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(text, Modifier.padding(12.dp))
    }
}

/** Hora del intervalo en la zona del profesional, siempre visible (ADR-046 §8). */
@Composable
private fun ProviderTime(startsAt: Instant, endsAt: Instant, provider: ZoneId, device: ZoneId) {
    val text = if (SessionTimes.sameOffset(startsAt, device, provider)) {
        stringResource(R.string.virtual_sessions_provider_zone_same, provider.id)
    } else {
        stringResource(R.string.virtual_sessions_provider_zone, provider.id, SessionTimes.range(startsAt, endsAt, provider, currentLocale()))
    }
    Text(text, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun SyncStatus(
    syncedAt: Instant,
    zone: ZoneId,
    stale: Boolean,
    refreshing: Boolean,
    problem: VirtualSessionsProblem?,
    onRetry: () -> Unit,
    tag: String,
) {
    if (refreshing) {
        val text = stringResource(R.string.virtual_sessions_refreshing)
        LinearProgressIndicator(
            Modifier
                .fillMaxWidth()
                .testTag("${tag}_refreshing")
                .semantics { contentDescription = text },
        )
    }
    Text(
        stringResource(R.string.virtual_sessions_synced_at, SessionTimes.dateTime(syncedAt, zone, currentLocale())),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("${tag}_synced_at"),
    )
    if (stale) Notice(R.string.virtual_sessions_stale, "${tag}_stale", MaterialTheme.colorScheme.error)
    when (problem) {
        null -> Unit
        VirtualSessionsProblem.NO_CONNECTION -> Notice(R.string.virtual_sessions_offline_with_cache, "${tag}_problem")
        else -> {
            Notice(R.string.virtual_sessions_unavailable_with_cache, "${tag}_problem")
            OutlinedButton(onClick = onRetry, enabled = !refreshing, modifier = Modifier.testTag("${tag}_retry")) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

/** Sin lectura confirmada: nunca sincronizada sin red, error o acceso rechazado. */
@Composable
private fun WithoutData(problem: VirtualSessionsProblem?, refreshing: Boolean, onRetry: () -> Unit, neverSynced: Int) {
    if (problem == VirtualSessionsProblem.NO_CONNECTION) {
        Notice(neverSynced, "virtual_sessions_never_synced")
    } else {
        Notice(readMessage(problem), "virtual_sessions_error", MaterialTheme.colorScheme.error)
    }
    Button(onClick = onRetry, enabled = !refreshing, modifier = Modifier.testTag("virtual_sessions_retry")) {
        Text(stringResource(R.string.action_retry))
    }
}

private fun readMessage(problem: VirtualSessionsProblem?): Int = when (problem) {
    VirtualSessionsProblem.PERMISSION_REQUIRED -> R.string.virtual_sessions_permission_required
    VirtualSessionsProblem.INVALID_REQUEST -> R.string.virtual_sessions_error_invalid_request
    VirtualSessionsProblem.INVALID_RESPONSE -> R.string.virtual_sessions_error_invalid_response
    VirtualSessionsProblem.STORAGE -> R.string.virtual_sessions_error_storage
    VirtualSessionsProblem.NO_CONNECTION, VirtualSessionsProblem.UNAVAILABLE, null -> R.string.virtual_sessions_error_unavailable
}

@Composable
private fun Loading(text: Int, tag: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.testTag(tag))
        Text(stringResource(text))
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

@Composable
private fun statusLabel(item: Appointment, now: Instant): String = when {
    item.status == AppointmentStatus.CANCELLED_BY_ATHLETE -> stringResource(R.string.virtual_sessions_status_cancelled)
    item.isUpcoming(now) -> stringResource(R.string.virtual_sessions_status_booked)
    else -> stringResource(R.string.virtual_sessions_status_past)
}

/** Un código que esta versión no conoce se muestra tal cual. */
@Composable
private fun modalityLabel(code: String): String = when (ServiceModality.fromWire(code)) {
    ServiceModality.VIRTUAL -> stringResource(R.string.virtual_sessions_modality_virtual)
    ServiceModality.HYBRID -> stringResource(R.string.virtual_sessions_modality_hybrid)
    null -> code
}

/** «Tarifa informativa: 120.000 COP por sesión». */
@Composable
private fun feeLabel(fee: ServiceFee): String {
    val amount = FeeFormat.amount(fee, currentLocale())
    val unit = when (BillingUnit.fromWire(fee.billingUnit)) {
        BillingUnit.SERVICE -> stringResource(R.string.virtual_sessions_unit_service)
        BillingUnit.SESSION -> stringResource(R.string.virtual_sessions_unit_session)
        BillingUnit.HOUR -> stringResource(R.string.virtual_sessions_unit_hour)
        BillingUnit.DAY -> stringResource(R.string.virtual_sessions_unit_day)
        null -> fee.billingUnit
    }
    return stringResource(R.string.virtual_sessions_fee, amount, unit)
}

/** Idioma vigente de la configuración: observable, a diferencia de `Locale.getDefault()`. */
@Composable
@ReadOnlyComposable
private fun currentLocale(): Locale = LocalConfiguration.current.locales[0]
