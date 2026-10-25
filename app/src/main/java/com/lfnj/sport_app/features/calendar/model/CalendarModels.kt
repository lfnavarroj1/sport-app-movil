package com.lfnj.sport_app.features.calendar.model

import com.lfnj.sport_app.core.session.AccessSnapshot
import com.lfnj.sport_app.core.session.SubscriptionStatus
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Capacidad del plan que habilita el calendario deportivo (`/calendario`, ADR-031). */
const val CALENDAR_CAPABILITY = "calendar.sport.view"

/** Guarda visual de la ruta: solo un deportista con suscripción activa y la capacidad vigente. */
object CalendarAccess {
    fun canView(access: AccessSnapshot?): Boolean =
        access != null && access.subscriptionStatus == SubscriptionStatus.ACTIVE && CALENDAR_CAPABILITY in access.capabilities
}

/** Partición local por identidad técnica: huella SHA-256 del `user_id` (ADR-031, política offline §3). */
object CalendarOwner {
    fun keyOf(userId: String): String =
        MessageDigest.getInstance("SHA-256").digest(userId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/**
 * Periodo consultado: el mes visible completo en la zona del dispositivo (ADR-031 §4). Nunca supera
 * los 92 días del contrato. Los extremos viajan como instantes ISO 8601 con desfase.
 */
data class CalendarPeriod(val month: YearMonth, val from: OffsetDateTime, val to: OffsetDateTime) {
    val fromParam: String get() = from.format(WIRE)
    val toParam: String get() = to.format(WIRE)

    companion object {
        private val END_OF_DAY: LocalTime = LocalTime.of(23, 59, 59)

        /** Siempre con segundos y desfase (`Z` en UTC), p. ej. `2026-11-01T00:00:00-05:00`. */
        private val WIRE: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)

        fun forMonth(month: YearMonth, zone: ZoneId): CalendarPeriod = CalendarPeriod(
            month = month,
            from = month.atDay(1).atStartOfDay(zone).toOffsetDateTime(),
            to = month.atEndOfMonth().atTime(END_OF_DAY).atZone(zone).toOffsetDateTime(),
        )
    }
}

/** Fuente de un elemento (`source`). El contrato admite fuentes futuras sin cambio incompatible. */
enum class CalendarItemSource(val wire: String) {
    EVENT_PUBLISHED("EVENT_PUBLISHED"),
    TRAINING_PLAN_SESSION("TRAINING_PLAN_SESSION");

    companion object {
        fun fromWire(value: String): CalendarItemSource? = entries.firstOrNull { it.wire == value }
    }
}

data class EventLocation(val name: String, val city: String, val countryCode: String)

/** Elemento del calendario tal como lo confirmó el BFF; los instantes se conservan sin alterar (CA4). */
sealed interface CalendarItem {
    val itemId: String
    /** Fecha del evento en su propia zona o fecha planificada de la sesión (`occurs_on`). */
    val occursOn: LocalDate
    val title: String

    /**
     * Evento masivo publicado (`EVENT_PUBLISHED`), con instantes UTC y su `time_zone` declarada.
     * [eventId] es el `event_id` de su `reference`, con el que se abre el detalle de HU041; `null` si
     * no vino o la caché es anterior a HU041.
     */
    data class Event(
        override val itemId: String,
        override val occursOn: LocalDate,
        override val title: String,
        val sport: String,
        val startsAt: Instant,
        val endsAt: Instant,
        val timeZone: String,
        val location: EventLocation,
        val eventId: String? = null,
    ) : CalendarItem

    /** Sesión de un plan asignado (`TRAINING_PLAN_SESSION`): solo tiene fecha, sin hora. */
    data class PlannedSession(
        override val itemId: String,
        override val occursOn: LocalDate,
        override val title: String,
        val sport: String,
        val durationMinutes: Int?,
        val rpe: Int?,
    ) : CalendarItem

    /** Fuente que esta versión del cliente aún no conoce: se muestra sin inventar su significado. */
    data class Other(
        override val itemId: String,
        override val occursOn: LocalDate,
        override val title: String,
        val source: String,
    ) : CalendarItem
}

enum class SourceStatus {
    OK, UNAVAILABLE, TIMEOUT;

    companion object {
        fun fromWire(value: String): SourceStatus? = entries.firstOrNull { it.name == value }
    }
}

/** Estado de una fuente compuesta por el BFF (`sources[]`). [status] `null` = valor desconocido. */
data class CalendarSourceState(val name: String, val status: SourceStatus?, val truncated: Boolean) {
    val complete: Boolean get() = status == SourceStatus.OK
}

/**
 * Último periodo confirmado por el BFF para un deportista, con su `last_synced_at` ([syncedAt]).
 * Es lo que se guarda cifrado en el dispositivo y lo que la pantalla observa (ADR-031 offline §2).
 */
data class CalendarSnapshot(
    val month: YearMonth,
    val from: Instant,
    val to: Instant,
    val items: List<CalendarItem>,
    val sources: List<CalendarSourceState>,
    val evaluatedAt: Instant,
    val syncedAt: Instant,
) {
    /**
     * Fuentes que no se pudieron consultar. Una fuente esperada ausente también cuenta como
     * incompleta: nunca se afirma que no hay actividades sin la confirmación de su fuente (CA7).
     */
    val incompleteSources: List<String>
        get() = sources.filterNot { it.complete }.map { it.name } +
            EXPECTED_SOURCES.filter { expected -> sources.none { it.name == expected } }

    val truncatedSources: List<String> get() = sources.filter { it.truncated }.map { it.name }

    val complete: Boolean get() = incompleteSources.isEmpty()

    /** Sin elementos y con todas las fuentes confirmadas: estado vacío, no error (CA2). */
    val confirmedEmpty: Boolean get() = items.isEmpty() && complete

    /** Confirmado hace más de 24 horas (ADR-031 offline §4). */
    fun isStale(now: Instant): Boolean = Duration.between(syncedAt, now) > STALE_AFTER

    companion object {
        const val SOURCE_EVENTS = "events"
        const val SOURCE_TRAINING = "training"
        val EXPECTED_SOURCES = listOf(SOURCE_EVENTS, SOURCE_TRAINING)
        val STALE_AFTER: Duration = Duration.of(24, ChronoUnit.HOURS)
    }
}

/** Elementos de un día en la zona del dispositivo. */
data class CalendarDay(val date: LocalDate, val items: List<CalendarItem>)

/**
 * Agrupa los elementos por día en la zona del dispositivo (CA4) sin modificar los datos fuente.
 * - Un evento se ubica en el día de su inicio convertido a [zone]; si empezó antes del mes
 *   visible (se superpone con él), se ubica en el primer día del mes.
 * - Una sesión planificada solo tiene fecha y no se convierte.
 * Dentro de un día van primero los elementos sin hora y luego por inicio; ante empate se conserva
 * el orden estable del BFF (ADR-031 §6).
 */
object CalendarDays {
    fun group(items: List<CalendarItem>, month: YearMonth, zone: ZoneId): List<CalendarDay> {
        val first = month.atDay(1)
        val last = month.atEndOfMonth()
        return items.withIndex()
            .groupBy { (_, item) ->
                val day = dayOf(item, zone)
                when {
                    day.isBefore(first) -> first
                    day.isAfter(last) -> last
                    else -> day
                }
            }
            .toSortedMap()
            .map { (date, entries) ->
                CalendarDay(
                    date,
                    entries.sortedWith(compareBy({ startOf(it.value) ?: Instant.MIN }, { it.index })).map { it.value },
                )
            }
    }

    fun dayOf(item: CalendarItem, zone: ZoneId): LocalDate = when (item) {
        is CalendarItem.Event -> item.startsAt.atZone(zone).toLocalDate()
        is CalendarItem.PlannedSession, is CalendarItem.Other -> item.occursOn
    }

    private fun startOf(item: CalendarItem): Instant? = (item as? CalendarItem.Event)?.startsAt
}
