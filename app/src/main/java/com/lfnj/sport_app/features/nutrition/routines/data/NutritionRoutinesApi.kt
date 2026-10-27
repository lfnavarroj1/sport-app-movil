package com.lfnj.sport_app.features.nutrition.routines.data

import com.lfnj.sport_app.features.nutrition.routines.model.NutritionRoutineSuggestion
import com.lfnj.sport_app.features.nutrition.routines.model.NutritionRoutinesSnapshot
import com.lfnj.sport_app.features.nutrition.routines.model.RoutineFood
import com.lfnj.sport_app.features.nutrition.routines.model.RoutineMeal
import com.lfnj.sport_app.features.nutrition.routines.model.RoutineSourceStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** Rutinas de alimentación sugeridas al deportista en `bff-mobile` 0.21.0 (HU030, ADR-041 §4). */
interface NutritionRoutinesApi {
    @GET("v1/me/suggestions/nutrition-routines")
    suspend fun nutritionRoutines(
        @Header("Authorization") authorization: String,
        @Query("limit") limit: Int,
    ): Response<RoutineSuggestionsResponseDto>

    companion object {
        /** Valor por defecto del contrato (1–50): la pantalla muestra una sola página. */
        const val LIMIT = 20
    }
}

@Serializable
data class RoutineSuggestionsResponseDto(
    val items: List<RoutineSuggestionItemDto>,
    val personalized: Boolean,
    @SerialName("missing_context") val missingContext: List<String>,
    val notices: List<String>,
    @SerialName("generated_at") val generatedAt: String,
    val sources: List<RoutineSourceDto>,
) {
    /** Sin títulos, autores ni alérgenos: un `toString` accidental no expone el contenido. */
    override fun toString(): String = "RoutineSuggestionsResponseDto(items=${items.size}, personalized=$personalized)"
}

@Serializable
data class RoutineSuggestionItemDto(
    val reference: RoutineReferenceDto,
    val title: String,
    val summary: String,
    val reasons: List<String>,
    @SerialName("template_id") val templateId: String,
    @SerialName("professional_id") val professionalId: String,
    val goal: String,
    /** Obligatorio y anulable en el contrato: rutina sin deporte específico. */
    val sport: String?,
    @SerialName("dietary_pattern") val dietaryPattern: String,
    @SerialName("contains_allergens") val containsAllergens: List<String>,
    @SerialName("duration_days") val durationDays: Int,
    @SerialName("daily_meals") val dailyMeals: List<RoutineMealDto>,
    @SerialName("published_at") val publishedAt: String,
    /** Obligatorio y anulable en el contrato: `null` si Usuarios no respondió. */
    @SerialName("author_name") val authorName: String?,
) {
    override fun toString(): String = "RoutineSuggestionItemDto(template_id=$templateId)"
}

@Serializable
data class RoutineReferenceDto(val type: String, val id: String)

@Serializable
data class RoutineMealDto(val meal: String, val items: List<RoutineItemDto>)

@Serializable
data class RoutineItemDto(@SerialName("food_code") val foodCode: String, val portions: Int)

@Serializable
data class RoutineSourceDto(val source: String, val status: String)

/** Error de contrato: la respuesta no corresponde a `bff-mobile` y no se muestra parcialmente. */
class InvalidRoutinesResponse : Exception()

/**
 * Convierte una respuesta `200` en la lista confirmada. Cualquier campo obligatorio mal formado
 * invalida toda la respuesta: nunca se completan datos inventados.
 */
fun RoutineSuggestionsResponseDto.toSnapshot(syncedAt: Instant): NutritionRoutinesSnapshot = try {
    NutritionRoutinesSnapshot(
        items = items.map { it.toSuggestion() },
        personalized = personalized,
        missingContext = missingContext,
        notices = notices,
        generatedAt = instant(generatedAt),
        usersSource = sources.firstOrNull { it.source == NutritionRoutinesSnapshot.SOURCE_USERS }
            ?.let { RoutineSourceStatus.fromWire(it.status) },
        syncedAt = syncedAt,
    )
} catch (_: DateTimeParseException) {
    throw InvalidRoutinesResponse()
}

private fun RoutineSuggestionItemDto.toSuggestion(): NutritionRoutineSuggestion {
    if (reference.type != REFERENCE_TYPE) throw InvalidRoutinesResponse()
    return NutritionRoutineSuggestion(
        templateId = templateId,
        professionalId = professionalId,
        title = title,
        summary = summary,
        goal = goal,
        sport = sport,
        dietaryPattern = dietaryPattern,
        containsAllergens = containsAllergens,
        durationDays = durationDays,
        dailyMeals = dailyMeals.map { meal -> RoutineMeal(meal.meal, meal.items.map { RoutineFood(it.foodCode, it.portions) }) },
        publishedAt = instant(publishedAt),
        authorName = authorName?.takeIf { it.isNotBlank() },
        reasons = reasons,
    )
}

private const val REFERENCE_TYPE = "nutrition_routine_template"

private fun instant(value: String): Instant = OffsetDateTime.parse(value).toInstant()
