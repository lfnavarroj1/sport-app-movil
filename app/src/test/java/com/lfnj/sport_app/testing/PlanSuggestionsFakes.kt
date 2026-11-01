package com.lfnj.sport_app.testing

import com.lfnj.sport_app.features.recommendations.data.local.PlanSuggestionsCacheDao
import com.lfnj.sport_app.features.recommendations.data.local.PlanSuggestionsCacheEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Datos sintéticos de HU028 conforme a `TemplateSuggestionsResponse` de `bff-mobile` 0.15.0. */
object PlanSuggestionsFixtures {
    const val GOAL_TEMPLATE_ID = "3f2b8c1d-5e6a-4b7c-8d9e-0a1b2c3d4e5f"
    const val SPORT_TEMPLATE_ID = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"
    const val PROFESSIONAL_ID = "5c4b3a29-1807-4f6e-9d5c-4b3a29180706"

    fun session(day: String = "MON", minutes: Int = 45, description: String = "Rodaje suave en zona 2") =
        """{"day_of_week":"$day","description":"$description","duration_minutes":$minutes}"""

    fun item(
        templateId: String = GOAL_TEMPLATE_ID,
        title: String = "Base aeróbica 10K",
        sport: String = "running",
        goal: String = "PERFORMANCE",
        level: String = "BEGINNER",
        weeks: Int = 8,
        sessions: List<String> = listOf(session(), session("WED", 30, "Series cortas"), session("SAT", 60, "Fondo largo")),
        reasons: List<String> = listOf("SPORT_MATCH", "GOAL_MATCH"),
        authorName: String? = "Laura Gómez",
        publishedAt: String = "2026-10-20T14:00:00Z",
        referenceType: String = "training_plan_template",
    ): String {
        val author = authorName?.let { "\"$it\"" } ?: "null"
        return """{"reference":{"type":"$referenceType","id":"$templateId"},"title":"$title",""" +
            """"summary":"Construir la base aeróbica para completar una carrera de 10 kilómetros.",""" +
            """"reasons":${reasons.joinToString(",", "[", "]") { "\"$it\"" }},"template_id":"$templateId",""" +
            """"professional_id":"$PROFESSIONAL_ID","sport":"$sport","goal":"$goal","level":"$level",""" +
            """"duration_weeks":$weeks,"weekly_sessions":${sessions.joinToString(",", "[", "]")},""" +
            """"published_at":"$publishedAt","author_name":$author}"""
    }

    fun body(
        items: List<String> = listOf(item(), item(templateId = SPORT_TEMPLATE_ID, title = "Fuerza para corredores", goal = "HEALTH", reasons = listOf("SPORT_MATCH"))),
        personalized: Boolean = true,
        missingContext: List<String> = emptyList(),
        usersStatus: String = "OK",
        generatedAt: String = "2026-10-25T13:00:00Z",
    ): String =
        """{"items":${items.joinToString(",", "[", "]")},"personalized":$personalized,""" +
            """"missing_context":${missingContext.joinToString(",", "[", "]") { "\"$it\"" }},""" +
            """"generated_at":"$generatedAt","sources":[{"source":"users","status":"$usersStatus"}]}"""

    /** Sin deportes registrados: últimas publicadas, sin motivos (CA2). */
    fun notPersonalized(missing: List<String> = listOf("sports", "primary_goal"), usersStatus: String = "OK") = body(
        items = listOf(item(reasons = emptyList(), sport = "cycling")),
        personalized = false,
        missingContext = missing,
        usersStatus = usersStatus,
    )

    fun validationError(): String =
        """{"code":"validation_error","message":"mensaje","correlation_id":"8c1f2d3e-0000-4000-8000-000000000028",""" +
            """"details":[{"field":"query.limit","issue":"out_of_range"}]}"""
}

/** Caché en memoria con la misma semántica que el DAO de Room (una fila por deportista). */
class InMemoryPlanSuggestionsDao : PlanSuggestionsCacheDao {
    val rows = MutableStateFlow<Map<String, PlanSuggestionsCacheEntity>>(emptyMap())
    var failWrites = false

    override fun observe(owner: String): Flow<PlanSuggestionsCacheEntity?> = rows.map { it[owner] }

    override suspend fun replace(entry: PlanSuggestionsCacheEntity) {
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
