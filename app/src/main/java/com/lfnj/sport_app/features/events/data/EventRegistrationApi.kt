package com.lfnj.sport_app.features.events.data

import com.lfnj.sport_app.features.events.model.EventDetail
import com.lfnj.sport_app.features.events.model.EventPlace
import com.lfnj.sport_app.features.events.model.EventPrice
import com.lfnj.sport_app.features.events.model.EventRegistration
import com.lfnj.sport_app.features.events.model.MyRegistration
import com.lfnj.sport_app.features.events.model.RegisteredEvent
import com.lfnj.sport_app.features.training.session.data.IDEMPOTENCY_HEADER
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Operaciones de HU041 en `bff-mobile` (ADR-032 «Contrato público»). La identidad sale del token:
 * ninguna solicitud lleva `athlete_id` y la inscripción no tiene cuerpo.
 */
interface EventRegistrationApi {
    @GET("v1/events/{event_id}")
    suspend fun detail(
        @Header("Authorization") authorization: String,
        @Path("event_id") eventId: String,
    ): Response<EventDetailDto>

    @POST("v1/events/{event_id}/registrations")
    suspend fun register(
        @Header("Authorization") authorization: String,
        @Header(IDEMPOTENCY_HEADER) idempotencyKey: String,
        @Path("event_id") eventId: String,
    ): Response<RegistrationDto>

    @GET("v1/me/event-registrations")
    suspend fun myRegistrations(
        @Header("Authorization") authorization: String,
        @Query("limit") limit: Int,
        @Query("cursor") cursor: String? = null,
    ): Response<RegistrationsDto>
}

/** `EventDetailResponse`. */
@Serializable
data class EventDetailDto(
    @SerialName("event_id") val eventId: String,
    val name: String,
    val description: String,
    val discipline: String,
    val location: EventLocationDto,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("ends_at") val endsAt: String,
    @SerialName("time_zone") val timeZone: String,
    val capacity: Int,
    val status: String,
    val version: Int,
    val fee: EventFeeDto? = null,
    @SerialName("my_registration") val myRegistration: MyRegistrationDto? = null,
) {
    /** Sin nombres, fechas ni ubicaciones: un `toString` accidental no expone la participación. */
    override fun toString(): String = "EventDetailDto(<oculto>)"
}

/** `Location`. */
@Serializable
data class EventLocationDto(
    val name: String,
    val city: String,
    @SerialName("country_code") val countryCode: String,
) {
    override fun toString(): String = "EventLocationDto(***)"
}

/** `EventFee` y `Fee`: `{amount, currency}` con `amount` decimal en texto. */
@Serializable
data class EventFeeDto(val amount: String, val currency: String)

/** `MyRegistration`. */
@Serializable
data class MyRegistrationDto(
    @SerialName("registration_id") val registrationId: String,
    val status: String,
    @SerialName("registered_at") val registeredAt: String,
) {
    override fun toString(): String = "MyRegistrationDto(<oculto>)"
}

/** `RegistrationResponse`: respuesta `201` y elemento de «Mis inscripciones». */
@Serializable
data class RegistrationDto(
    @SerialName("registration_id") val registrationId: String,
    @SerialName("event_id") val eventId: String,
    val status: String,
    @SerialName("registered_at") val registeredAt: String,
    @SerialName("applied_fee") val appliedFee: EventFeeDto? = null,
    val version: Int,
    val event: RegistrationEventDto,
) {
    override fun toString(): String = "RegistrationDto(<oculto>)"
}

/** `RegistrationEvent`. */
@Serializable
data class RegistrationEventDto(
    val name: String,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("ends_at") val endsAt: String,
    @SerialName("time_zone") val timeZone: String,
    val location: EventLocationDto,
) {
    override fun toString(): String = "RegistrationEventDto(<oculto>)"
}

/** `RegistrationsResponse`. */
@Serializable
data class RegistrationsDto(
    val items: List<RegistrationDto>,
    @SerialName("next_cursor") val nextCursor: String? = null,
) {
    override fun toString(): String = "RegistrationsDto(items=${items.size})"
}

/** Error de contrato: la respuesta no corresponde a ADR-032 y no se muestra parcialmente. */
class InvalidEventResponse : Exception()

private const val PUBLISHED = "PUBLISHED"
private const val REGISTERED = "REGISTERED"

/** Solo un evento `PUBLISHED` con todos sus campos válidos; nunca se completan datos inventados. */
fun EventDetailDto.toDetail(): EventDetail {
    if (status != PUBLISHED) throw InvalidEventResponse()
    return EventDetail(
        eventId = eventId,
        name = name,
        description = description,
        discipline = discipline,
        location = location.toPlace(),
        startsAt = instant(startsAt),
        endsAt = instant(endsAt),
        timeZone = timeZone,
        capacity = capacity,
        version = version,
        fee = fee?.toPrice(),
        myRegistration = myRegistration?.let {
            if (it.status != REGISTERED) throw InvalidEventResponse()
            MyRegistration(it.registrationId, instant(it.registeredAt))
        },
    )
}

fun RegistrationDto.toRegistration(): EventRegistration {
    if (status != REGISTERED) throw InvalidEventResponse()
    return EventRegistration(
        registrationId = registrationId,
        eventId = eventId,
        registeredAt = instant(registeredAt),
        appliedFee = appliedFee?.toPrice(),
        version = version,
        event = RegisteredEvent(
            name = event.name,
            startsAt = instant(event.startsAt),
            endsAt = instant(event.endsAt),
            timeZone = event.timeZone,
            location = event.location.toPlace(),
        ),
    )
}

private fun EventLocationDto.toPlace() = EventPlace(name, city, countryCode)

private fun EventFeeDto.toPrice() = EventPrice(amount, currency)

private fun instant(value: String): Instant = try {
    OffsetDateTime.parse(value).toInstant()
} catch (_: DateTimeParseException) {
    throw InvalidEventResponse()
}
