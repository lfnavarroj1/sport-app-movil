package com.lfnj.sport_app.features.events.data.local

import com.lfnj.sport_app.core.security.TokenCipher
import com.lfnj.sport_app.features.events.model.EventPlace
import com.lfnj.sport_app.features.events.model.EventPrice
import com.lfnj.sport_app.features.events.model.EventSuggestion
import com.lfnj.sport_app.features.events.model.EventSuggestionsSnapshot
import com.lfnj.sport_app.features.events.model.MyRegistration
import com.lfnj.sport_app.features.recommendations.model.ContextSourceStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.time.DateTimeException
import java.time.Instant

/** Contenido cifrado de [EventSuggestionsCacheEntity.payload]. Instantes en segundos UTC. */
@Serializable
internal data class EventSuggestionsPayload(
    val items: List<CachedSuggestedEvent>,
    val personalized: Boolean,
    @SerialName("missing_context") val missingContext: List<String>,
    @SerialName("generated_at") val generatedAt: Long,
    @SerialName("users_source") val usersSource: String? = null,
    @SerialName("last_synced_at") val lastSyncedAt: Long,
)

@Serializable
internal data class CachedSuggestedEvent(
    @SerialName("event_id") val eventId: String,
    val title: String,
    val summary: String,
    val discipline: String,
    @SerialName("location_name") val locationName: String,
    val city: String,
    @SerialName("country_code") val countryCode: String,
    @SerialName("starts_at") val startsAt: Long,
    @SerialName("ends_at") val endsAt: Long,
    @SerialName("time_zone") val timeZone: String,
    val capacity: Int,
    @SerialName("fee_amount") val feeAmount: String? = null,
    @SerialName("fee_currency") val feeCurrency: String? = null,
    @SerialName("registration_id") val registrationId: String? = null,
    @SerialName("registered_at") val registeredAt: Long? = null,
    val reasons: List<String>,
)

/**
 * Cifra y descifra la lista confirmada con AES-GCM y una clave no exportable de Android Keystore
 * propia de HU027. Una caché que no puede descifrarse se trata como inexistente: nunca se muestran
 * eventos inventados (ADR-041 §5).
 */
class EventSuggestionsCodec(private val cipher: TokenCipher, private val json: Json) {

    fun encode(snapshot: EventSuggestionsSnapshot): ByteArray {
        val payload = EventSuggestionsPayload(
            items = snapshot.items.map(::cached),
            personalized = snapshot.personalized,
            missingContext = snapshot.missingContext,
            generatedAt = snapshot.generatedAt.epochSecond,
            usersSource = snapshot.usersSource?.name,
            lastSyncedAt = snapshot.syncedAt.epochSecond,
        )
        return cipher.encrypt(json.encodeToString(EventSuggestionsPayload.serializer(), payload).toByteArray(Charsets.UTF_8))
    }

    /** `null` si la caché no puede descifrarse o no corresponde al formato esperado. */
    fun decode(entity: EventSuggestionsCacheEntity): EventSuggestionsSnapshot? = try {
        val payload = json.decodeFromString(EventSuggestionsPayload.serializer(), cipher.decrypt(entity.payload).toString(Charsets.UTF_8))
        EventSuggestionsSnapshot(
            items = payload.items.map { it.toSuggestion() ?: return null },
            personalized = payload.personalized,
            missingContext = payload.missingContext,
            generatedAt = Instant.ofEpochSecond(payload.generatedAt),
            usersSource = payload.usersSource?.let(ContextSourceStatus::fromWire),
            syncedAt = Instant.ofEpochSecond(payload.lastSyncedAt),
        )
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: SerializationException) {
        null
    } catch (_: DateTimeException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    private fun cached(item: EventSuggestion) = CachedSuggestedEvent(
        eventId = item.eventId,
        title = item.title,
        summary = item.summary,
        discipline = item.discipline,
        locationName = item.location.name,
        city = item.location.city,
        countryCode = item.location.countryCode,
        startsAt = item.startsAt.epochSecond,
        endsAt = item.endsAt.epochSecond,
        timeZone = item.timeZone,
        capacity = item.capacity,
        feeAmount = item.fee?.amount,
        feeCurrency = item.fee?.currency,
        registrationId = item.myRegistration?.registrationId,
        registeredAt = item.myRegistration?.registeredAt?.epochSecond,
        reasons = item.reasons,
    )

    /** `null` si una tarifa o una inscripción guardada quedó incompleta: no se muestra un dato alterado. */
    private fun CachedSuggestedEvent.toSuggestion(): EventSuggestion? {
        if ((feeAmount == null) != (feeCurrency == null)) return null
        if ((registrationId == null) != (registeredAt == null)) return null
        return EventSuggestion(
            eventId = eventId,
            title = title,
            summary = summary,
            discipline = discipline,
            location = EventPlace(locationName, city, countryCode),
            startsAt = Instant.ofEpochSecond(startsAt),
            endsAt = Instant.ofEpochSecond(endsAt),
            timeZone = timeZone,
            capacity = capacity,
            fee = if (feeAmount != null && feeCurrency != null) EventPrice(feeAmount, feeCurrency) else null,
            myRegistration = if (registrationId != null && registeredAt != null) MyRegistration(registrationId, Instant.ofEpochSecond(registeredAt)) else null,
            reasons = reasons,
        )
    }

    companion object {
        /** Alias de la clave no exportable de Android Keystore para la caché de HU027. */
        const val KEY_ALIAS = "sportapp.event_suggestions.v1"
    }
}
