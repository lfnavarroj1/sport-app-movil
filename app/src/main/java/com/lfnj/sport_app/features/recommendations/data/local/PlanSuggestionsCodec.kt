package com.lfnj.sport_app.features.recommendations.data.local

import com.lfnj.sport_app.core.security.TokenCipher
import com.lfnj.sport_app.features.recommendations.model.ContextSourceStatus
import com.lfnj.sport_app.features.recommendations.model.PlanSuggestionsSnapshot
import com.lfnj.sport_app.features.recommendations.model.PlanTemplateSuggestion
import com.lfnj.sport_app.features.recommendations.model.WeekDays
import com.lfnj.sport_app.features.recommendations.model.WeeklySession
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.time.DateTimeException
import java.time.Instant

/** Contenido cifrado de [PlanSuggestionsCacheEntity.payload]. Instantes en segundos UTC. */
@Serializable
internal data class SuggestionsPayload(
    val items: List<CachedTemplate>,
    val personalized: Boolean,
    @SerialName("missing_context") val missingContext: List<String>,
    @SerialName("generated_at") val generatedAt: Long,
    @SerialName("users_source") val usersSource: String? = null,
    @SerialName("last_synced_at") val lastSyncedAt: Long,
)

@Serializable
internal data class CachedTemplate(
    @SerialName("template_id") val templateId: String,
    @SerialName("professional_id") val professionalId: String,
    val title: String,
    val summary: String,
    val sport: String,
    val goal: String,
    val level: String,
    @SerialName("duration_weeks") val durationWeeks: Int,
    @SerialName("weekly_sessions") val weeklySessions: List<CachedSession>,
    @SerialName("published_at") val publishedAt: Long,
    @SerialName("author_name") val authorName: String? = null,
    val reasons: List<String>,
)

@Serializable
internal data class CachedSession(
    val day: String,
    val description: String,
    @SerialName("duration_minutes") val durationMinutes: Int,
)

/**
 * Cifra y descifra la lista confirmada con AES-GCM y una clave no exportable de Android Keystore
 * propia de HU028. Una caché que no puede descifrarse se trata como inexistente: nunca se muestran
 * plantillas inventadas (ADR-041 §5).
 */
class PlanSuggestionsCodec(private val cipher: TokenCipher, private val json: Json) {

    fun encode(snapshot: PlanSuggestionsSnapshot): ByteArray {
        val payload = SuggestionsPayload(
            items = snapshot.items.map(::cached),
            personalized = snapshot.personalized,
            missingContext = snapshot.missingContext,
            generatedAt = snapshot.generatedAt.epochSecond,
            usersSource = snapshot.usersSource?.name,
            lastSyncedAt = snapshot.syncedAt.epochSecond,
        )
        return cipher.encrypt(json.encodeToString(SuggestionsPayload.serializer(), payload).toByteArray(Charsets.UTF_8))
    }

    /** `null` si la caché no puede descifrarse o no corresponde al formato esperado. */
    fun decode(entity: PlanSuggestionsCacheEntity): PlanSuggestionsSnapshot? = try {
        val payload = json.decodeFromString(SuggestionsPayload.serializer(), cipher.decrypt(entity.payload).toString(Charsets.UTF_8))
        PlanSuggestionsSnapshot(
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

    private fun cached(item: PlanTemplateSuggestion) = CachedTemplate(
        templateId = item.templateId,
        professionalId = item.professionalId,
        title = item.title,
        summary = item.summary,
        sport = item.sport,
        goal = item.goal,
        level = item.level,
        durationWeeks = item.durationWeeks,
        weeklySessions = item.weeklySessions.map { CachedSession(WeekDays.toWire(it.day), it.description, it.durationMinutes) },
        publishedAt = item.publishedAt.epochSecond,
        authorName = item.authorName,
        reasons = item.reasons,
    )

    private fun CachedTemplate.toSuggestion(): PlanTemplateSuggestion? = PlanTemplateSuggestion(
        templateId = templateId,
        professionalId = professionalId,
        title = title,
        summary = summary,
        sport = sport,
        goal = goal,
        level = level,
        durationWeeks = durationWeeks,
        weeklySessions = weeklySessions.map { WeeklySession(WeekDays.fromWire(it.day) ?: return null, it.description, it.durationMinutes) },
        publishedAt = Instant.ofEpochSecond(publishedAt),
        authorName = authorName,
        reasons = reasons,
    )

    companion object {
        /** Alias de la clave no exportable de Android Keystore para la caché de HU028. */
        const val KEY_ALIAS = "sportapp.plan_suggestions.v1"
    }
}
