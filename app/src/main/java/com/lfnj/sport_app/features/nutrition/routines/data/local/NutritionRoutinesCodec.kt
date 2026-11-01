package com.lfnj.sport_app.features.nutrition.routines.data.local

import com.lfnj.sport_app.core.security.TokenCipher
import com.lfnj.sport_app.features.nutrition.routines.model.NutritionRoutineSuggestion
import com.lfnj.sport_app.features.nutrition.routines.model.NutritionRoutinesSnapshot
import com.lfnj.sport_app.features.nutrition.routines.model.RoutineFood
import com.lfnj.sport_app.features.nutrition.routines.model.RoutineMeal
import com.lfnj.sport_app.features.nutrition.routines.model.RoutineSourceStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.time.DateTimeException
import java.time.Instant

/** Contenido cifrado de [NutritionRoutinesCacheEntity.payload]. Instantes en segundos UTC. */
@Serializable
internal data class RoutinesPayload(
    val items: List<CachedRoutine>,
    val personalized: Boolean,
    @SerialName("missing_context") val missingContext: List<String>,
    val notices: List<String>,
    @SerialName("generated_at") val generatedAt: Long,
    @SerialName("users_source") val usersSource: String? = null,
    @SerialName("last_synced_at") val lastSyncedAt: Long,
)

@Serializable
internal data class CachedRoutine(
    @SerialName("template_id") val templateId: String,
    @SerialName("professional_id") val professionalId: String,
    val title: String,
    val summary: String,
    val goal: String,
    val sport: String? = null,
    @SerialName("dietary_pattern") val dietaryPattern: String,
    @SerialName("contains_allergens") val containsAllergens: List<String>,
    @SerialName("duration_days") val durationDays: Int,
    @SerialName("daily_meals") val dailyMeals: List<CachedMeal>,
    @SerialName("published_at") val publishedAt: Long,
    @SerialName("author_name") val authorName: String? = null,
    val reasons: List<String>,
)

@Serializable
internal data class CachedMeal(val meal: String, val items: List<CachedFood>)

@Serializable
internal data class CachedFood(@SerialName("food_code") val foodCode: String, val portions: Int)

/**
 * Cifra y descifra la lista confirmada con AES-GCM y una clave no exportable de Android Keystore
 * propia de HU030. Una caché que no puede descifrarse se trata como inexistente: nunca se muestran
 * rutinas inventadas (ADR-041 §5).
 */
class NutritionRoutinesCodec(private val cipher: TokenCipher, private val json: Json) {

    fun encode(snapshot: NutritionRoutinesSnapshot): ByteArray {
        val payload = RoutinesPayload(
            items = snapshot.items.map(::cached),
            personalized = snapshot.personalized,
            missingContext = snapshot.missingContext,
            notices = snapshot.notices,
            generatedAt = snapshot.generatedAt.epochSecond,
            usersSource = snapshot.usersSource?.name,
            lastSyncedAt = snapshot.syncedAt.epochSecond,
        )
        return cipher.encrypt(json.encodeToString(RoutinesPayload.serializer(), payload).toByteArray(Charsets.UTF_8))
    }

    /** `null` si la caché no puede descifrarse o no corresponde al formato esperado. */
    fun decode(entity: NutritionRoutinesCacheEntity): NutritionRoutinesSnapshot? = try {
        val payload = json.decodeFromString(RoutinesPayload.serializer(), cipher.decrypt(entity.payload).toString(Charsets.UTF_8))
        NutritionRoutinesSnapshot(
            items = payload.items.map { it.toSuggestion() },
            personalized = payload.personalized,
            missingContext = payload.missingContext,
            notices = payload.notices,
            generatedAt = Instant.ofEpochSecond(payload.generatedAt),
            usersSource = payload.usersSource?.let(RoutineSourceStatus::fromWire),
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

    private fun cached(item: NutritionRoutineSuggestion) = CachedRoutine(
        templateId = item.templateId,
        professionalId = item.professionalId,
        title = item.title,
        summary = item.summary,
        goal = item.goal,
        sport = item.sport,
        dietaryPattern = item.dietaryPattern,
        containsAllergens = item.containsAllergens,
        durationDays = item.durationDays,
        dailyMeals = item.dailyMeals.map { meal -> CachedMeal(meal.meal, meal.items.map { CachedFood(it.foodCode, it.portions) }) },
        publishedAt = item.publishedAt.epochSecond,
        authorName = item.authorName,
        reasons = item.reasons,
    )

    private fun CachedRoutine.toSuggestion() = NutritionRoutineSuggestion(
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
        publishedAt = Instant.ofEpochSecond(publishedAt),
        authorName = authorName,
        reasons = reasons,
    )

    companion object {
        /** Alias de la clave no exportable de Android Keystore para la caché de HU030. */
        const val KEY_ALIAS = "sportapp.nutrition_routines.v1"
    }
}
