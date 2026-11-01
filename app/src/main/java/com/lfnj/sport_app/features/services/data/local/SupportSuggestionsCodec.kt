package com.lfnj.sport_app.features.services.data.local

import com.lfnj.sport_app.core.security.TokenCipher
import com.lfnj.sport_app.features.services.model.ServiceFee
import com.lfnj.sport_app.features.services.model.SupportInterval
import com.lfnj.sport_app.features.services.model.SupportServiceSuggestion
import com.lfnj.sport_app.features.services.model.SupportSourceStatus
import com.lfnj.sport_app.features.services.model.SupportSuggestionsSnapshot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId

/** Contenido cifrado de [SupportSuggestionsCacheEntity.payload]. Instantes en segundos UTC. */
@Serializable
internal data class SupportSuggestionsPayload(
    val items: List<CachedSupportService>,
    val personalized: Boolean,
    @SerialName("missing_context") val missingContext: List<String>,
    @SerialName("generated_at") val generatedAt: Long,
    @SerialName("events_source") val eventsSource: String? = null,
    @SerialName("users_source") val usersSource: String? = null,
    @SerialName("last_synced_at") val lastSyncedAt: Long,
)

@Serializable
internal data class CachedSupportService(
    @SerialName("service_id") val serviceId: String,
    val title: String,
    val summary: String,
    val category: String,
    val modality: String,
    @SerialName("fee_amount") val feeAmount: String,
    @SerialName("fee_currency") val feeCurrency: String,
    @SerialName("fee_billing_unit") val feeBillingUnit: String,
    @SerialName("starts_at") val startsAt: Long,
    @SerialName("ends_at") val endsAt: Long,
    @SerialName("time_zone") val timeZone: String,
    @SerialName("author_name") val authorName: String? = null,
    val reasons: List<String>,
)

/**
 * Cifra y descifra la lista confirmada con AES-GCM y una clave no exportable de Android Keystore
 * propia de HU029. Una caché que no puede descifrarse se trata como inexistente: nunca se muestran
 * servicios inventados (ADR-041 §5).
 */
class SupportSuggestionsCodec(private val cipher: TokenCipher, private val json: Json) {

    fun encode(snapshot: SupportSuggestionsSnapshot): ByteArray {
        val payload = SupportSuggestionsPayload(
            items = snapshot.items.map(::cached),
            personalized = snapshot.personalized,
            missingContext = snapshot.missingContext,
            generatedAt = snapshot.generatedAt.epochSecond,
            eventsSource = snapshot.eventsSource?.name,
            usersSource = snapshot.usersSource?.name,
            lastSyncedAt = snapshot.syncedAt.epochSecond,
        )
        return cipher.encrypt(json.encodeToString(SupportSuggestionsPayload.serializer(), payload).toByteArray(Charsets.UTF_8))
    }

    /** `null` si la caché no puede descifrarse o no corresponde al formato esperado. */
    fun decode(entity: SupportSuggestionsCacheEntity): SupportSuggestionsSnapshot? = try {
        val payload = json.decodeFromString(SupportSuggestionsPayload.serializer(), cipher.decrypt(entity.payload).toString(Charsets.UTF_8))
        SupportSuggestionsSnapshot(
            items = payload.items.map { it.toSuggestion() },
            personalized = payload.personalized,
            missingContext = payload.missingContext,
            generatedAt = Instant.ofEpochSecond(payload.generatedAt),
            eventsSource = payload.eventsSource?.let(SupportSourceStatus::fromWire),
            usersSource = payload.usersSource?.let(SupportSourceStatus::fromWire),
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

    private fun cached(item: SupportServiceSuggestion) = CachedSupportService(
        serviceId = item.serviceId,
        title = item.title,
        summary = item.summary,
        category = item.category,
        modality = item.modality,
        feeAmount = item.fee.amount,
        feeCurrency = item.fee.currency,
        feeBillingUnit = item.fee.billingUnit,
        startsAt = item.nextInterval.startsAt.epochSecond,
        endsAt = item.nextInterval.endsAt.epochSecond,
        timeZone = item.nextInterval.timeZone.id,
        authorName = item.authorName,
        reasons = item.reasons,
    )

    private fun CachedSupportService.toSuggestion() = SupportServiceSuggestion(
        serviceId = serviceId,
        title = title,
        summary = summary,
        category = category,
        modality = modality,
        fee = ServiceFee(feeAmount, feeCurrency, feeBillingUnit),
        nextInterval = SupportInterval(Instant.ofEpochSecond(startsAt), Instant.ofEpochSecond(endsAt), ZoneId.of(timeZone)),
        authorName = authorName,
        reasons = reasons,
    )

    companion object {
        /** Alias de la clave no exportable de Android Keystore para la caché de HU029. */
        const val KEY_ALIAS = "sportapp.support_suggestions.v1"
    }
}
