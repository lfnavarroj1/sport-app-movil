package com.lfnj.sport_app.testing

import com.lfnj.sport_app.features.nutrition.routines.data.local.NutritionRoutinesCacheDao
import com.lfnj.sport_app.features.nutrition.routines.data.local.NutritionRoutinesCacheEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Datos sintéticos de HU030 conforme a `RoutineSuggestionsResponse` de `bff-mobile` 0.21.0. */
object NutritionRoutinesFixtures {
    const val GOAL_TEMPLATE_ID = "7d1e2f30-4a5b-4c6d-8e7f-90a1b2c3d4e5"
    const val SPORT_TEMPLATE_ID = "1b2c3d4e-5f60-4718-89ab-cdef01234567"
    const val PROFESSIONAL_ID = "2e3f4a5b-6c7d-4e8f-9a0b-1c2d3e4f5a6b"

    fun meal(meal: String = "BREAKFAST", items: List<Pair<String, Int>> = listOf("OATS" to 1, "BANANA" to 2)) =
        """{"meal":"$meal","items":${items.joinToString(",", "[", "]") { (code, portions) -> """{"food_code":"$code","portions":$portions}""" }}}"""

    fun item(
        templateId: String = GOAL_TEMPLATE_ID,
        title: String = "Desayunos para fondo",
        goal: String = "PERFORMANCE",
        sport: String? = "running",
        pattern: String = "VEGETARIAN",
        allergens: List<String> = listOf("MILK"),
        days: Int = 14,
        meals: List<String> = listOf(meal(), meal("LUNCH", listOf("QUINOA" to 1, "LENTILS" to 1)), meal("HYDRATION", listOf("WATER" to 4))),
        reasons: List<String> = listOf("RESTRICTION_COMPATIBLE", "GOAL_MATCH", "SPORT_MATCH"),
        authorName: String? = "Marta Ruiz",
        publishedAt: String = "2026-10-20T14:00:00Z",
        referenceType: String = "nutrition_routine_template",
    ): String {
        val author = authorName?.let { "\"$it\"" } ?: "null"
        val sportJson = sport?.let { "\"$it\"" } ?: "null"
        return """{"reference":{"type":"$referenceType","id":"$templateId"},"title":"$title",""" +
            """"summary":"Comidas del día tipo para semanas de carga en carrera.",""" +
            """"reasons":${reasons.joinToString(",", "[", "]") { "\"$it\"" }},"template_id":"$templateId",""" +
            """"professional_id":"$PROFESSIONAL_ID","goal":"$goal","sport":$sportJson,"dietary_pattern":"$pattern",""" +
            """"contains_allergens":${allergens.joinToString(",", "[", "]") { "\"$it\"" }},"duration_days":$days,""" +
            """"daily_meals":${meals.joinToString(",", "[", "]")},"published_at":"$publishedAt","author_name":$author}"""
    }

    fun body(
        items: List<String> = listOf(
            item(),
            item(templateId = SPORT_TEMPLATE_ID, title = "Recuperación ciclista", goal = "HEALTH", sport = null, allergens = emptyList(), reasons = listOf("RESTRICTION_COMPATIBLE")),
        ),
        personalized: Boolean = true,
        missingContext: List<String> = emptyList(),
        notices: List<String> = listOf("NOT_MEDICAL_ADVICE"),
        usersStatus: String = "OK",
        generatedAt: String = "2026-10-27T13:00:00Z",
    ): String =
        """{"items":${items.joinToString(",", "[", "]")},"personalized":$personalized,""" +
            """"missing_context":${missingContext.joinToString(",", "[", "]") { "\"$it\"" }},""" +
            """"notices":${notices.joinToString(",", "[", "]") { "\"$it\"" }},""" +
            """"generated_at":"$generatedAt","sources":[{"source":"users","status":"$usersStatus"}]}"""

    /** Sin hábitos alimenticios (HU009): últimas publicadas, sin motivos y sin verificar restricciones (CA3). */
    fun notPersonalized(missing: List<String> = listOf("nutrition_habits"), usersStatus: String = "OK") = body(
        items = listOf(item(reasons = emptyList(), allergens = listOf("PEANUTS", "MILK"))),
        personalized = false,
        missingContext = missing,
        notices = listOf("RESTRICTIONS_NOT_VERIFIED", "NOT_MEDICAL_ADVICE"),
        usersStatus = usersStatus,
    )

    fun validationError(): String =
        """{"code":"validation_error","message":"mensaje","correlation_id":"8c1f2d3e-0000-4000-8000-000000000030",""" +
            """"details":[{"field":"query.limit","issue":"out_of_range"}]}"""
}

/** Caché en memoria con la misma semántica que el DAO de Room (una fila por deportista). */
class InMemoryNutritionRoutinesDao : NutritionRoutinesCacheDao {
    val rows = MutableStateFlow<Map<String, NutritionRoutinesCacheEntity>>(emptyMap())
    var failWrites = false

    override fun observe(owner: String): Flow<NutritionRoutinesCacheEntity?> = rows.map { it[owner] }

    override suspend fun replace(entry: NutritionRoutinesCacheEntity) {
        check(!failWrites) { "disco lleno" }
        rows.value = rows.value + (entry.ownerKey to entry)
    }

    override suspend fun delete(owner: String) {
        rows.value = rows.value - owner
    }

    override suspend fun deleteAllExcept(owner: String) {
        rows.value = rows.value.filterKeys { it == owner }
    }
}
