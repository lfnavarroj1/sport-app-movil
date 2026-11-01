package com.lfnj.sport_app.features.calendar.data

import com.lfnj.sport_app.features.calendar.model.CalendarItem
import com.lfnj.sport_app.features.calendar.model.CalendarItemSource
import com.lfnj.sport_app.features.calendar.model.CalendarSnapshot
import com.lfnj.sport_app.features.calendar.model.CalendarSourceState
import com.lfnj.sport_app.features.calendar.model.EventLocation
import com.lfnj.sport_app.features.calendar.model.SourceStatus
import com.lfnj.sport_app.features.events.model.EventIds
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.format.DateTimeParseException

/** Calendario deportivo del deportista en `bff-mobile` (HU035, ADR-031 «Contrato público»). */
interface CalendarApi {
    @GET("v1/me/calendar")
    suspend fun calendar(
        @Header("Authorization") authorization: String,
        @Query("from") from: String,
        @Query("to") to: String,
    ): Response<CalendarResponseDto>
}

@Serializable
data class CalendarResponseDto(
    val from: String,
    val to: String,
    val items: List<CalendarItemDto>,
    val sources: List<CalendarSourceDto>,
    @SerialName("evaluated_at") val evaluatedAt: String,
) {
    /** Sin títulos, fechas ni ubicaciones: un `toString` accidental no expone actividades. */
    override fun toString(): String = "CalendarResponseDto(items=${items.size}, sources=${sources.size})"
}

/**
 * Elemento de cualquiera de las fuentes. Los campos propios de cada fuente son opcionales aquí y
 * se validan al convertirlo según su `source`. De `reference` solo se lee el `event_id` de un evento,
 * con el que HU041 abre su detalle.
 */
@Serializable
data class CalendarItemDto(
    @SerialName("item_id") val itemId: String,
    val source: String,
    @SerialName("occurs_on") val occursOn: String,
    val title: String,
    val sport: String? = null,
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null,
    @SerialName("time_zone") val timeZone: String? = null,
    val location: CalendarLocationDto? = null,
    @SerialName("duration_minutes") val durationMinutes: Int? = null,
    val rpe: Int? = null,
    val reference: CalendarReferenceDto? = null,
) {
    override fun toString(): String = "CalendarItemDto(source=$source)"
}

@Serializable
data class CalendarLocationDto(
    val name: String,
    val city: String,
    @SerialName("country_code") val countryCode: String,
) {
    override fun toString(): String = "CalendarLocationDto(***)"
}

/** `EventReference` de un evento (`event_id`); las referencias de sesiones no se leen. */
@Serializable
data class CalendarReferenceDto(@SerialName("event_id") val eventId: String? = null) {
    override fun toString(): String = "CalendarReferenceDto(***)"
}

@Serializable
data class CalendarSourceDto(val name: String, val status: String, val truncated: Boolean)

/** Error de contrato: la respuesta no corresponde a ADR-031 y no se muestra parcialmente. */
class InvalidCalendarResponse : Exception()

/**
 * Convierte una respuesta `200` en el periodo confirmado de [month]. Cualquier campo obligatorio
 * ausente o mal formado invalida toda la respuesta: nunca se completan datos inventados.
 */
fun CalendarResponseDto.toSnapshot(month: YearMonth, syncedAt: Instant): CalendarSnapshot = try {
    CalendarSnapshot(
        month = month,
        from = instant(from),
        to = instant(to),
        items = items.map { it.toItem() },
        sources = sources.map { CalendarSourceState(it.name, SourceStatus.fromWire(it.status), it.truncated) },
        evaluatedAt = instant(evaluatedAt),
        syncedAt = syncedAt,
    )
} catch (_: DateTimeParseException) {
    throw InvalidCalendarResponse()
}

private fun CalendarItemDto.toItem(): CalendarItem {
    val date = LocalDate.parse(occursOn)
    return when (CalendarItemSource.fromWire(source)) {
        CalendarItemSource.EVENT_PUBLISHED -> CalendarItem.Event(
            itemId = itemId,
            occursOn = date,
            title = title,
            sport = sport ?: throw InvalidCalendarResponse(),
            startsAt = instant(startsAt ?: throw InvalidCalendarResponse()),
            endsAt = instant(endsAt ?: throw InvalidCalendarResponse()),
            timeZone = timeZone ?: throw InvalidCalendarResponse(),
            location = location?.let { EventLocation(it.name, it.city, it.countryCode) } ?: throw InvalidCalendarResponse(),
            eventId = reference?.eventId?.takeIf(EventIds::isValid),
        )
        CalendarItemSource.TRAINING_PLAN_SESSION -> CalendarItem.PlannedSession(
            itemId = itemId,
            occursOn = date,
            title = title,
            sport = sport ?: throw InvalidCalendarResponse(),
            durationMinutes = durationMinutes,
            rpe = rpe,
        )
        null -> CalendarItem.Other(itemId, date, title, source)
    }
}

private fun instant(value: String): Instant = OffsetDateTime.parse(value).toInstant()
