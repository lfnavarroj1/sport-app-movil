package com.lfnj.sport_app.features.services.data

import com.lfnj.sport_app.features.services.model.ServiceFee
import com.lfnj.sport_app.features.services.model.SupportInterval
import com.lfnj.sport_app.features.services.model.SupportServiceSuggestion
import com.lfnj.sport_app.features.services.model.SupportSource
import com.lfnj.sport_app.features.services.model.SupportSourceStatus
import com.lfnj.sport_app.features.services.model.SupportSuggestionsSnapshot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/** Servicios de acompañamiento y logística sugeridos al deportista en `bff-mobile` 0.18.0 (HU029, ADR-041 §4). */
interface SupportSuggestionsApi {
    @GET("v1/me/suggestions/services")
    suspend fun services(
        @Header("Authorization") authorization: String,
        @Query("limit") limit: Int,
    ): Response<ServiceSuggestionsResponseDto>

    companion object {
        /** Valor por defecto del contrato (1–50): la pantalla muestra una sola página. */
        const val LIMIT = 20
    }
}

@Serializable
data class ServiceSuggestionsResponseDto(
    val items: List<ServiceSuggestionItemDto>,
    val personalized: Boolean,
    @SerialName("missing_context") val missingContext: List<String>,
    @SerialName("generated_at") val generatedAt: String,
    val sources: List<ServiceSuggestionSourceDto>,
) {
    /** Sin títulos ni proveedores: un `toString` accidental no expone el contenido. */
    override fun toString(): String = "ServiceSuggestionsResponseDto(items=${items.size}, personalized=$personalized)"
}

@Serializable
data class ServiceSuggestionItemDto(
    val reference: ServiceReferenceDto,
    val title: String,
    val summary: String,
    val reasons: List<String>,
    @SerialName("service_id") val serviceId: String,
    val category: String,
    val modality: String,
    val fee: ServiceFeeDto,
    @SerialName("next_interval") val nextInterval: NextIntervalDto,
    /** Obligatorio y anulable en el contrato: `null` si Usuarios no respondió. */
    @SerialName("author_name") val authorName: String?,
) {
    override fun toString(): String = "ServiceSuggestionItemDto(service_id=$serviceId)"
}

@Serializable
data class ServiceReferenceDto(val type: String, val id: String)

@Serializable
data class ServiceFeeDto(
    val amount: String,
    val currency: String,
    @SerialName("billing_unit") val billingUnit: String,
)

@Serializable
data class NextIntervalDto(
    @SerialName("starts_at") val startsAt: String,
    @SerialName("ends_at") val endsAt: String,
    @SerialName("time_zone") val timeZone: String,
)

@Serializable
data class ServiceSuggestionSourceDto(val source: String, val status: String)

/** Error de contrato: la respuesta no corresponde a `bff-mobile` y no se muestra parcialmente. */
class InvalidSupportSuggestionsResponse : Exception()

/**
 * Convierte una respuesta `200` en la lista confirmada. Cualquier campo obligatorio mal formado
 * invalida toda la respuesta: nunca se completan datos inventados.
 */
fun ServiceSuggestionsResponseDto.toSnapshot(syncedAt: Instant): SupportSuggestionsSnapshot = try {
    SupportSuggestionsSnapshot(
        items = items.map { it.toSuggestion() },
        personalized = personalized,
        missingContext = missingContext,
        generatedAt = instant(generatedAt),
        eventsSource = sourceStatus(SupportSource.EVENTS),
        usersSource = sourceStatus(SupportSource.USERS),
        syncedAt = syncedAt,
    )
} catch (_: DateTimeException) {
    throw InvalidSupportSuggestionsResponse()
}

private fun ServiceSuggestionsResponseDto.sourceStatus(source: SupportSource): SupportSourceStatus? =
    sources.firstOrNull { it.source == source.wire }?.let { SupportSourceStatus.fromWire(it.status) }

private fun ServiceSuggestionItemDto.toSuggestion(): SupportServiceSuggestion {
    if (reference.type != REFERENCE_TYPE || reference.id != serviceId) throw InvalidSupportSuggestionsResponse()
    val startsAt = instant(nextInterval.startsAt)
    val endsAt = instant(nextInterval.endsAt)
    if (!endsAt.isAfter(startsAt)) throw InvalidSupportSuggestionsResponse()
    return SupportServiceSuggestion(
        serviceId = serviceId,
        title = title,
        summary = summary,
        category = category,
        modality = modality,
        fee = ServiceFee(fee.amount, fee.currency, fee.billingUnit),
        nextInterval = SupportInterval(startsAt, endsAt, ZoneId.of(nextInterval.timeZone)),
        authorName = authorName?.takeIf { it.isNotBlank() },
        reasons = reasons,
    )
}

private const val REFERENCE_TYPE = "service"

private fun instant(value: String): Instant = OffsetDateTime.parse(value).toInstant()
