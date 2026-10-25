package com.lfnj.sport_app.features.services.data

import com.lfnj.sport_app.features.services.model.Appointment
import com.lfnj.sport_app.features.services.model.AppointmentStatus
import com.lfnj.sport_app.features.services.model.AvailabilitySlot
import com.lfnj.sport_app.features.services.model.CoachingService
import com.lfnj.sport_app.features.services.model.ServiceFee
import com.lfnj.sport_app.features.services.model.ServiceModality
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** Sesiones virtuales con entrenadores en `bff-mobile` 0.18.0 (HU040, ADR-046; catálogo de ADR-038). */
interface VirtualSessionsApi {
    /** Servicios `COACHING` virtuales o híbridos con intervalos que empiezan en `[from, to)`. */
    @GET("v1/services/availability")
    suspend fun availability(
        @Header("Authorization") authorization: String,
        @Query("from") from: String,
        @Query("to") to: String,
        @Query("category") category: List<String>,
        @Query("modality") modality: List<String>,
        @Query("limit") limit: Int,
        @Query("cursor") cursor: String?,
    ): Response<ServiceAvailabilityResponseDto>

    /** Citas propias, reservadas y canceladas, por `starts_at` ascendente. */
    @GET("v1/me/appointments")
    suspend fun appointments(
        @Header("Authorization") authorization: String,
        @Query("limit") limit: Int,
        @Query("cursor") cursor: String?,
    ): Response<AppointmentsResponseDto>

    /** Reserva solo en línea: no se encola (ADR-046 §8). */
    @POST("v1/me/appointments")
    suspend fun book(
        @Header("Authorization") authorization: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: BookAppointmentRequestDto,
    ): Response<AppointmentDto>

    @DELETE("v1/me/appointments/{appointment_id}")
    suspend fun cancel(
        @Header("Authorization") authorization: String,
        @Path("appointment_id") appointmentId: String,
    ): Response<AppointmentDto>

    companion object {
        const val CATEGORY_COACHING = "COACHING"

        /** Máximo del contrato para el catálogo (1–50). */
        const val CATALOG_LIMIT = 50

        /** Máximo del contrato para las citas (1–100). */
        const val APPOINTMENTS_LIMIT = 100
    }
}

@Serializable
data class ServiceAvailabilityResponseDto(
    val items: List<CatalogServiceDto>,
    @SerialName("next_cursor") val nextCursor: String?,
    @SerialName("restricted_categories") val restrictedCategories: List<String>,
) {
    override fun toString(): String = "ServiceAvailabilityResponseDto(items=${items.size})"
}

@Serializable
data class CatalogServiceDto(
    @SerialName("service_id") val serviceId: String,
    @SerialName("provider_id") val providerId: String,
    val name: String,
    val description: String,
    val category: String,
    val modality: String,
    val price: ServicePriceDto,
    val availability: List<CatalogIntervalDto>,
    val available: Boolean,
) {
    override fun toString(): String = "CatalogServiceDto(service_id=$serviceId)"
}

@Serializable
data class CatalogIntervalDto(
    @SerialName("availability_id") val availabilityId: String,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("ends_at") val endsAt: String,
    @SerialName("time_zone") val timeZone: String,
    val capacity: Int,
)

@Serializable
data class ServicePriceDto(
    val amount: String,
    val currency: String,
    @SerialName("billing_unit") val billingUnit: String,
)

@Serializable
data class BookAppointmentRequestDto(
    @SerialName("service_id") val serviceId: String,
    @SerialName("availability_id") val availabilityId: String,
)

@Serializable
data class AppointmentsResponseDto(
    val items: List<AppointmentDto>,
    @SerialName("next_cursor") val nextCursor: String?,
) {
    override fun toString(): String = "AppointmentsResponseDto(items=${items.size})"
}

/** `AppointmentResponse`; `meeting_url` es siempre `null` en el MVP (ADR-046 §6). */
@Serializable
data class AppointmentDto(
    @SerialName("appointment_id") val appointmentId: String,
    @SerialName("athlete_id") val athleteId: String,
    @SerialName("provider_id") val providerId: String,
    @SerialName("service_id") val serviceId: String,
    @SerialName("availability_id") val availabilityId: String,
    val service: AssociatedServiceSummaryDto,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("ends_at") val endsAt: String,
    @SerialName("time_zone") val timeZone: String,
    val status: String,
    @SerialName("meeting_url") val meetingUrl: String? = null,
    @SerialName("booked_at") val bookedAt: String,
    @SerialName("cancelled_at") val cancelledAt: String?,
    val version: Int,
) {
    override fun toString(): String = "AppointmentDto(appointment_id=$appointmentId, status=$status)"
}

@Serializable
data class AssociatedServiceSummaryDto(
    val name: String,
    val category: String,
    val modality: String,
    val fee: ServicePriceDto,
)

/** La respuesta no corresponde al contrato: nunca se completan datos inventados. */
class InvalidVirtualSessionsResponse : Exception()

/**
 * Servicios reservables de la página. La consulta ya pide `COACHING` `VIRTUAL`/`HYBRID`; un
 * servicio de otra categoría o modalidad se omite en lugar de ofrecerse como reservable.
 */
fun ServiceAvailabilityResponseDto.toServices(): List<CoachingService> = guarded {
    items.mapNotNull { item ->
        val modality = ServiceModality.fromWire(item.modality)
        if (item.category != VirtualSessionsApi.CATEGORY_COACHING || modality == null) return@mapNotNull null
        CoachingService(
            serviceId = item.serviceId,
            providerId = item.providerId,
            name = item.name,
            description = item.description,
            modality = modality,
            fee = item.price.toFee(),
            slots = item.availability.map { it.toSlot() }.sortedBy { it.startsAt },
        )
    }
}

fun AppointmentDto.toAppointment(): Appointment = guarded {
    val parsedStatus = AppointmentStatus.fromWire(status) ?: throw InvalidVirtualSessionsResponse()
    val startsAt = instant(startsAt)
    val endsAt = instant(endsAt)
    if (!endsAt.isAfter(startsAt)) throw InvalidVirtualSessionsResponse()
    Appointment(
        appointmentId = appointmentId,
        serviceId = serviceId,
        availabilityId = availabilityId,
        providerId = providerId,
        serviceName = service.name,
        modality = service.modality,
        fee = service.fee.toFee(),
        startsAt = startsAt,
        endsAt = endsAt,
        timeZone = ZoneId.of(timeZone),
        status = parsedStatus,
        bookedAt = instant(bookedAt),
        cancelledAt = cancelledAt?.let(::instant),
        version = version,
    )
}

private fun CatalogIntervalDto.toSlot(): AvailabilitySlot {
    val startsAt = instant(startsAt)
    val endsAt = instant(endsAt)
    if (!endsAt.isAfter(startsAt) || capacity < 1) throw InvalidVirtualSessionsResponse()
    return AvailabilitySlot(availabilityId, startsAt, endsAt, ZoneId.of(timeZone), capacity)
}

private fun ServicePriceDto.toFee(): ServiceFee = ServiceFee(amount, currency, billingUnit)

private fun instant(value: String): Instant = OffsetDateTime.parse(value).toInstant()

/** Fechas o zonas ilegibles invalidan toda la respuesta. */
private inline fun <T> guarded(block: () -> T): T = try {
    block()
} catch (_: DateTimeParseException) {
    throw InvalidVirtualSessionsResponse()
} catch (_: DateTimeException) {
    throw InvalidVirtualSessionsResponse()
}
