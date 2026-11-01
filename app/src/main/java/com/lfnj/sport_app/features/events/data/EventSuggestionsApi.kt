package com.lfnj.sport_app.features.events.data

import com.lfnj.sport_app.features.events.model.EventIds
import com.lfnj.sport_app.features.events.model.EventPlace
import com.lfnj.sport_app.features.events.model.EventPrice
import com.lfnj.sport_app.features.events.model.EventSuggestion
import com.lfnj.sport_app.features.events.model.EventSuggestionsSnapshot
import com.lfnj.sport_app.features.events.model.MyRegistration
import com.lfnj.sport_app.features.recommendations.data.InvalidSuggestionsResponse
import com.lfnj.sport_app.features.recommendations.data.SuggestionSourceDto
import com.lfnj.sport_app.features.recommendations.model.ContextSourceStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** Eventos sugeridos al deportista en `bff-mobile` 0.15.0 (HU027, ADR-041 §4). */
interface EventSuggestionsApi {
    @GET("v1/me/suggestions/events")
    suspend fun events(
        @Header("Authorization") authorization: String,
        @Query("limit") limit: Int,
    ): Response<EventSuggestionsResponseDto>

    companion object {
        /** Valor por defecto del contrato (1–50): la pantalla muestra una sola página. */
        const val LIMIT = 20
    }
}

@Serializable
data class EventSuggestionsResponseDto(
    val items: List<EventSuggestionItemDto>,
    val personalized: Boolean,
    @SerialName("missing_context") val missingContext: List<String>,
    @SerialName("generated_at") val generatedAt: String,
    val sources: List<SuggestionSourceDto>,
) {
    /** Sin nombres ni lugares: un `toString` accidental no expone el contenido. */
    override fun toString(): String = "EventSuggestionsResponseDto(items=${items.size}, personalized=$personalized)"
}

@Serializable
data class EventSuggestionItemDto(
    val reference: SuggestedEventReferenceDto,
    val title: String,
    val summary: String,
    val reasons: List<String>,
    @SerialName("event_id") val eventId: String,
    val discipline: String,
    val location: EventLocationDto,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("ends_at") val endsAt: String,
    @SerialName("time_zone") val timeZone: String,
    val capacity: Int,
    /** Obligatorio y anulable en el contrato: `null` sin tarifa. */
    val fee: EventFeeDto?,
    /** Obligatorio y anulable en el contrato: `null` si el deportista no está inscrito. */
    @SerialName("my_registration") val myRegistration: MyRegistrationDto?,
) {
    override fun toString(): String = "EventSuggestionItemDto(event_id=$eventId)"
}

/** `SuggestedEventReference` del contrato (`type` = `event`). */
@Serializable
data class SuggestedEventReferenceDto(val type: String, val id: String)

/**
 * Convierte una respuesta `200` en la lista confirmada. Cualquier campo obligatorio mal formado
 * invalida toda la respuesta: nunca se completan datos inventados.
 */
fun EventSuggestionsResponseDto.toSnapshot(syncedAt: Instant): EventSuggestionsSnapshot = try {
    EventSuggestionsSnapshot(
        items = items.map { it.toSuggestion() },
        personalized = personalized,
        missingContext = missingContext,
        generatedAt = instant(generatedAt),
        usersSource = sources.firstOrNull { it.source == EventSuggestionsSnapshot.SOURCE_USERS }
            ?.let { ContextSourceStatus.fromWire(it.status) },
        syncedAt = syncedAt,
    )
} catch (_: DateTimeParseException) {
    throw InvalidSuggestionsResponse()
}

private fun EventSuggestionItemDto.toSuggestion(): EventSuggestion {
    // El identificador abre el detalle de HU041: debe ser el UUID del evento referenciado.
    if (reference.type != REFERENCE_TYPE || reference.id != eventId || !EventIds.isValid(eventId)) throw InvalidSuggestionsResponse()
    if (myRegistration != null && myRegistration.status != REGISTERED) throw InvalidSuggestionsResponse()
    return EventSuggestion(
        eventId = eventId,
        title = title,
        summary = summary,
        discipline = discipline,
        location = EventPlace(location.name, location.city, location.countryCode),
        startsAt = instant(startsAt),
        endsAt = instant(endsAt),
        timeZone = timeZone,
        capacity = capacity,
        fee = fee?.let { EventPrice(it.amount, it.currency) },
        myRegistration = myRegistration?.let { MyRegistration(it.registrationId, instant(it.registeredAt)) },
        reasons = reasons,
    )
}

private const val REFERENCE_TYPE = "event"
private const val REGISTERED = "REGISTERED"

private fun instant(value: String): Instant = OffsetDateTime.parse(value).toInstant()
