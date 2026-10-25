package com.lfnj.sport_app.features.recommendations.data

import com.lfnj.sport_app.features.recommendations.model.ContextSourceStatus
import com.lfnj.sport_app.features.recommendations.model.PlanSuggestionsSnapshot
import com.lfnj.sport_app.features.recommendations.model.PlanTemplateSuggestion
import com.lfnj.sport_app.features.recommendations.model.WeekDays
import com.lfnj.sport_app.features.recommendations.model.WeeklySession
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** Plantillas de plan sugeridas al deportista en `bff-mobile` 0.15.0 (HU028, ADR-041 §4). */
interface PlanSuggestionsApi {
    @GET("v1/me/suggestions/training-plan-templates")
    suspend fun trainingPlanTemplates(
        @Header("Authorization") authorization: String,
        @Query("limit") limit: Int,
    ): Response<TemplateSuggestionsResponseDto>

    companion object {
        /** Valor por defecto del contrato (1–50): la pantalla muestra una sola página. */
        const val LIMIT = 20
    }
}

@Serializable
data class TemplateSuggestionsResponseDto(
    val items: List<TemplateSuggestionItemDto>,
    val personalized: Boolean,
    @SerialName("missing_context") val missingContext: List<String>,
    @SerialName("generated_at") val generatedAt: String,
    val sources: List<SuggestionSourceDto>,
) {
    /** Sin títulos ni autores: un `toString` accidental no expone el contenido. */
    override fun toString(): String = "TemplateSuggestionsResponseDto(items=${items.size}, personalized=$personalized)"
}

@Serializable
data class TemplateSuggestionItemDto(
    val reference: TemplateReferenceDto,
    val title: String,
    val summary: String,
    val reasons: List<String>,
    @SerialName("template_id") val templateId: String,
    @SerialName("professional_id") val professionalId: String,
    val sport: String,
    val goal: String,
    val level: String,
    @SerialName("duration_weeks") val durationWeeks: Int,
    @SerialName("weekly_sessions") val weeklySessions: List<WeeklySessionDto>,
    @SerialName("published_at") val publishedAt: String,
    /** Obligatorio y anulable en el contrato: `null` si Usuarios no respondió. */
    @SerialName("author_name") val authorName: String?,
) {
    override fun toString(): String = "TemplateSuggestionItemDto(template_id=$templateId)"
}

@Serializable
data class TemplateReferenceDto(val type: String, val id: String)

@Serializable
data class WeeklySessionDto(
    @SerialName("day_of_week") val dayOfWeek: String,
    val description: String,
    @SerialName("duration_minutes") val durationMinutes: Int,
)

@Serializable
data class SuggestionSourceDto(val source: String, val status: String)

/** Error de contrato: la respuesta no corresponde a `bff-mobile` y no se muestra parcialmente. */
class InvalidSuggestionsResponse : Exception()

/**
 * Convierte una respuesta `200` en la lista confirmada. Cualquier campo obligatorio mal formado
 * invalida toda la respuesta: nunca se completan datos inventados.
 */
fun TemplateSuggestionsResponseDto.toSnapshot(syncedAt: Instant): PlanSuggestionsSnapshot = try {
    PlanSuggestionsSnapshot(
        items = items.map { it.toSuggestion() },
        personalized = personalized,
        missingContext = missingContext,
        generatedAt = instant(generatedAt),
        usersSource = sources.firstOrNull { it.source == PlanSuggestionsSnapshot.SOURCE_USERS }
            ?.let { ContextSourceStatus.fromWire(it.status) },
        syncedAt = syncedAt,
    )
} catch (_: DateTimeParseException) {
    throw InvalidSuggestionsResponse()
}

private fun TemplateSuggestionItemDto.toSuggestion(): PlanTemplateSuggestion {
    if (reference.type != REFERENCE_TYPE) throw InvalidSuggestionsResponse()
    return PlanTemplateSuggestion(
        templateId = templateId,
        professionalId = professionalId,
        title = title,
        summary = summary,
        sport = sport,
        goal = goal,
        level = level,
        durationWeeks = durationWeeks,
        weeklySessions = weeklySessions.map {
            WeeklySession(WeekDays.fromWire(it.dayOfWeek) ?: throw InvalidSuggestionsResponse(), it.description, it.durationMinutes)
        },
        publishedAt = instant(publishedAt),
        authorName = authorName?.takeIf { it.isNotBlank() },
        reasons = reasons,
    )
}

private const val REFERENCE_TYPE = "training_plan_template"

private fun instant(value: String): Instant = OffsetDateTime.parse(value).toInstant()
