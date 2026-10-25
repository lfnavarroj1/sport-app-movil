package com.lfnj.sport_app.features.events.data.local

import com.lfnj.sport_app.core.security.TokenCipher
import com.lfnj.sport_app.features.events.model.EventPlace
import com.lfnj.sport_app.features.events.model.EventPrice
import com.lfnj.sport_app.features.events.model.EventRegistration
import com.lfnj.sport_app.features.events.model.RegisteredEvent
import com.lfnj.sport_app.features.events.model.RegistrationsSnapshot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.time.Instant

/** Contenido cifrado de [EventRegistrationsCacheEntity.payload]. Instantes en segundos UTC. */
@Serializable
internal data class RegistrationsPayload(
    val items: List<CachedRegistration>,
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("last_synced_at") val lastSyncedAt: Long,
)

@Serializable
internal data class CachedRegistration(
    @SerialName("registration_id") val registrationId: String,
    @SerialName("event_id") val eventId: String,
    @SerialName("registered_at") val registeredAt: Long,
    @SerialName("fee_amount") val feeAmount: String? = null,
    @SerialName("fee_currency") val feeCurrency: String? = null,
    val version: Int,
    val name: String,
    @SerialName("starts_at") val startsAt: Long,
    @SerialName("ends_at") val endsAt: Long,
    @SerialName("time_zone") val timeZone: String,
    @SerialName("location_name") val locationName: String,
    @SerialName("location_city") val locationCity: String,
    @SerialName("location_country") val locationCountry: String,
)

/**
 * Cifra y descifra «Mis inscripciones» con AES-GCM y una clave no exportable de Android Keystore
 * propia de HU041. Una caché que no puede descifrarse se trata como inexistente: nunca se
 * muestran inscripciones inventadas (ADR-032 §9).
 */
class EventRegistrationsCodec(private val cipher: TokenCipher, private val json: Json) {

    fun encode(snapshot: RegistrationsSnapshot): ByteArray {
        val payload = RegistrationsPayload(
            items = snapshot.items.map(::cached),
            nextCursor = snapshot.nextCursor,
            lastSyncedAt = snapshot.syncedAt.epochSecond,
        )
        return cipher.encrypt(json.encodeToString(RegistrationsPayload.serializer(), payload).toByteArray(Charsets.UTF_8))
    }

    /** `null` si la caché no puede descifrarse o no corresponde al formato esperado. */
    fun decode(entity: EventRegistrationsCacheEntity): RegistrationsSnapshot? = try {
        val payload = json.decodeFromString(RegistrationsPayload.serializer(), cipher.decrypt(entity.payload).toString(Charsets.UTF_8))
        RegistrationsSnapshot(
            items = payload.items.map { it.toRegistration() },
            nextCursor = payload.nextCursor,
            syncedAt = Instant.ofEpochSecond(payload.lastSyncedAt),
        )
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    private fun cached(item: EventRegistration) = CachedRegistration(
        registrationId = item.registrationId,
        eventId = item.eventId,
        registeredAt = item.registeredAt.epochSecond,
        feeAmount = item.appliedFee?.amount,
        feeCurrency = item.appliedFee?.currency,
        version = item.version,
        name = item.event.name,
        startsAt = item.event.startsAt.epochSecond,
        endsAt = item.event.endsAt.epochSecond,
        timeZone = item.event.timeZone,
        locationName = item.event.location.name,
        locationCity = item.event.location.city,
        locationCountry = item.event.location.countryCode,
    )

    private fun CachedRegistration.toRegistration() = EventRegistration(
        registrationId = registrationId,
        eventId = eventId,
        registeredAt = Instant.ofEpochSecond(registeredAt),
        appliedFee = if (feeAmount != null && feeCurrency != null) EventPrice(feeAmount, feeCurrency) else null,
        version = version,
        event = RegisteredEvent(
            name = name,
            startsAt = Instant.ofEpochSecond(startsAt),
            endsAt = Instant.ofEpochSecond(endsAt),
            timeZone = timeZone,
            location = EventPlace(locationName, locationCity, locationCountry),
        ),
    )

    companion object {
        /** Alias de la clave no exportable de Android Keystore para la caché de HU041. */
        const val KEY_ALIAS = "sportapp.event_registrations.v1"
    }
}
