package com.lfnj.sport_app.contract

import com.lfnj.sport_app.features.recommendations.data.PlanSuggestionsApi
import com.lfnj.sport_app.features.recommendations.data.SuggestionSourceDto
import com.lfnj.sport_app.features.recommendations.data.TemplateReferenceDto
import com.lfnj.sport_app.features.recommendations.data.TemplateSuggestionItemDto
import com.lfnj.sport_app.features.recommendations.data.TemplateSuggestionsResponseDto
import com.lfnj.sport_app.features.recommendations.data.WeeklySessionDto
import com.lfnj.sport_app.features.recommendations.model.ContextSourceStatus
import com.lfnj.sport_app.features.recommendations.model.MissingContext
import com.lfnj.sport_app.features.recommendations.model.PlanSuggestionsSnapshot
import com.lfnj.sport_app.features.recommendations.model.SuggestionReason
import com.lfnj.sport_app.features.recommendations.model.WeekDays
import com.lfnj.sport_app.features.training.session.model.TrainingSport
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

/** HU028 en el cliente móvil frente a la copia exacta de `bff-mobile` 0.15.0 (ADR-041 §4). */
class PlanSuggestionsContractTest {
    private val contract = OpenApiContract.load()
    private val path = "/v1/me/suggestions/training-plan-templates"

    @Test
    fun `la operacion de sugerencias existe con su limite`() {
        contract.assertApiMatches(PlanSuggestionsApi::class.java)
        assertEquals(setOf("limit"), contract.parameters("get", path, "query"))
        assertTrue("authorization" in contract.parameters("get", path, "header"))
        assertTrue(PlanSuggestionsApi.LIMIT in 1..50)
    }

    @Test
    fun `la respuesta, el item, la referencia, las sesiones y las fuentes corresponden al contrato`() {
        contract.assertModelMatches("TemplateSuggestionsResponse", TemplateSuggestionsResponseDto.serializer())
        contract.assertModelMatches("TemplateSuggestionItem", TemplateSuggestionItemDto.serializer())
        contract.assertModelMatches("TemplateReference", TemplateReferenceDto.serializer())
        contract.assertModelMatches("WeeklySession", WeeklySessionDto.serializer())
        contract.assertModelMatches("SuggestionSource", SuggestionSourceDto.serializer())
    }

    @Test
    fun `los catalogos del cliente son los del contrato`() {
        OpenApiContract.assertSameElements(
            "TemplateSuggestionItem.sport",
            contract.enumValues("TemplateSuggestionItem", "sport"),
            TrainingSport.entries.map { it.wire }.toSet(),
        )
        OpenApiContract.assertSameElements(
            "TemplateSuggestionItem.level",
            contract.enumValues("TemplateSuggestionItem", "level"),
            setOf("BEGINNER", "INTERMEDIATE", "ADVANCED"),
        )
        OpenApiContract.assertSameElements(
            "TemplateSuggestionItem.goal",
            contract.enumValues("TemplateSuggestionItem", "goal"),
            setOf("HEALTH", "PERFORMANCE", "WEIGHT_MANAGEMENT", "RECREATION", "COMPETITION"),
        )
        OpenApiContract.assertSameElements("WeeklySession.day_of_week", contract.enumValues("WeeklySession", "day_of_week"), DayOfWeek.values().map(WeekDays::toWire).toSet())
        OpenApiContract.assertSameElements("SuggestionSource.status", contract.enumValues("SuggestionSource", "status"), ContextSourceStatus.entries.map { it.name }.toSet())
        assertEquals("users", contract.const("SuggestionSource", "source"))
        assertEquals(PlanSuggestionsSnapshot.SOURCE_USERS, contract.const("SuggestionSource", "source"))
        assertEquals("training_plan_template", contract.const("TemplateReference", "type"))
    }

    @Test
    fun `motivos y contexto faltante son los del contrato`() {
        OpenApiContract.assertSameElements(
            "TemplateSuggestionItem.reasons",
            contract.arrayEnum("TemplateSuggestionItem", "reasons"),
            SuggestionReason.entries.map { it.wire }.toSet(),
        )
        OpenApiContract.assertSameElements(
            "TemplateSuggestionsResponse.missing_context",
            contract.arrayEnum("TemplateSuggestionsResponse", "missing_context"),
            MissingContext.entries.map { it.wire }.toSet(),
        )
    }

    @Test
    fun `el cliente trata todas las respuestas documentadas`() {
        assertEquals(setOf("200", "401", "403", "422", "503", "504"), contract.responseCodes("get", path))
    }

    private fun OpenApiContract.const(schema: String, property: String): String =
        propertySchema(schema, property).getValue("const").toString().trim('"')

    private fun OpenApiContract.arrayEnum(schema: String, property: String): Set<String> =
        propertySchema(schema, property).getValue("items").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }.toSet()
}
