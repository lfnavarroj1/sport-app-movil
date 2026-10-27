package com.lfnj.sport_app.contract

import com.lfnj.sport_app.features.nutrition.intake.model.MealType
import com.lfnj.sport_app.features.nutrition.routines.data.NutritionRoutinesApi
import com.lfnj.sport_app.features.nutrition.routines.data.RoutineItemDto
import com.lfnj.sport_app.features.nutrition.routines.data.RoutineMealDto
import com.lfnj.sport_app.features.nutrition.routines.data.RoutineReferenceDto
import com.lfnj.sport_app.features.nutrition.routines.data.RoutineSourceDto
import com.lfnj.sport_app.features.nutrition.routines.data.RoutineSuggestionItemDto
import com.lfnj.sport_app.features.nutrition.routines.data.RoutineSuggestionsResponseDto
import com.lfnj.sport_app.features.nutrition.routines.model.NutritionRoutinesSnapshot
import com.lfnj.sport_app.features.nutrition.routines.model.RoutineMissingContext
import com.lfnj.sport_app.features.nutrition.routines.model.RoutineNotice
import com.lfnj.sport_app.features.nutrition.routines.model.RoutineReason
import com.lfnj.sport_app.features.nutrition.routines.model.RoutineSourceStatus
import com.lfnj.sport_app.features.training.session.model.TrainingSport
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** HU030 en el cliente móvil frente a la copia exacta de `bff-mobile` 0.21.0 (ADR-041 §4). */
class NutritionRoutinesContractTest {
    private val contract = OpenApiContract.load()
    private val path = "/v1/me/suggestions/nutrition-routines"

    @Test
    fun `la operacion de rutinas sugeridas existe con su limite`() {
        contract.assertApiMatches(NutritionRoutinesApi::class.java)
        assertEquals(setOf("limit"), contract.parameters("get", path, "query"))
        assertTrue("authorization" in contract.parameters("get", path, "header"))
        assertTrue(NutritionRoutinesApi.LIMIT in 1..50)
    }

    @Test
    fun `la respuesta, el item, la referencia, las comidas y las fuentes corresponden al contrato`() {
        contract.assertModelMatches("RoutineSuggestionsResponse", RoutineSuggestionsResponseDto.serializer())
        contract.assertModelMatches("RoutineSuggestionItem", RoutineSuggestionItemDto.serializer())
        contract.assertModelMatches("RoutineReference", RoutineReferenceDto.serializer())
        contract.assertModelMatches("RoutineMeal", RoutineMealDto.serializer())
        contract.assertModelMatches("RoutineItem", RoutineItemDto.serializer())
        contract.assertModelMatches("SuggestionSource", RoutineSourceDto.serializer())
    }

    @Test
    fun `sport y author_name son obligatorios y anulables`() {
        listOf("sport", "author_name").forEach { field ->
            assertTrue(field in contract.schemaRequired("RoutineSuggestionItem"))
            val types = contract.propertySchema("RoutineSuggestionItem", field).getValue("anyOf").jsonArray
                .mapNotNull { it.jsonObject["type"]?.jsonPrimitive?.content }
            assertTrue("$field debe admitir null", "null" in types)
        }
        val sportEnum = contract.propertySchema("RoutineSuggestionItem", "sport").getValue("anyOf").jsonArray
            .firstNotNullOf { it.jsonObject["enum"]?.jsonArray }.map { it.jsonPrimitive.content }.toSet()
        OpenApiContract.assertSameElements("RoutineSuggestionItem.sport", sportEnum, TrainingSport.entries.map { it.wire }.toSet())
    }

    @Test
    fun `los catalogos que la pantalla traduce son los del contrato`() {
        OpenApiContract.assertSameElements(
            "RoutineSuggestionItem.goal",
            contract.enumValues("RoutineSuggestionItem", "goal"),
            setOf("HEALTH", "PERFORMANCE", "WEIGHT_MANAGEMENT", "RECREATION", "COMPETITION"),
        )
        OpenApiContract.assertSameElements(
            "RoutineSuggestionItem.dietary_pattern",
            contract.enumValues("RoutineSuggestionItem", "dietary_pattern"),
            setOf("OMNIVORE", "VEGETARIAN", "VEGAN", "PESCATARIAN", "FLEXITARIAN"),
        )
        OpenApiContract.assertSameElements(
            "RoutineSuggestionItem.contains_allergens",
            contract.arrayEnum("RoutineSuggestionItem", "contains_allergens"),
            setOf("PEANUTS", "TREE_NUTS", "MILK", "EGGS", "WHEAT", "SOY", "FISH", "SHELLFISH", "SESAME"),
        )
        OpenApiContract.assertSameElements("RoutineMeal.meal", contract.enumValues("RoutineMeal", "meal"), MealType.entries.map { it.wire }.toSet())
        OpenApiContract.assertSameElements("SuggestionSource.status", contract.enumValues("SuggestionSource", "status"), RoutineSourceStatus.entries.map { it.name }.toSet())
        assertEquals(NutritionRoutinesSnapshot.SOURCE_USERS, contract.const("SuggestionSource", "source"))
        assertEquals("nutrition_routine_template", contract.const("RoutineReference", "type"))
    }

    @Test
    fun `motivos, contexto faltante y avisos son los del contrato`() {
        OpenApiContract.assertSameElements(
            "RoutineSuggestionItem.reasons",
            contract.arrayEnum("RoutineSuggestionItem", "reasons"),
            RoutineReason.entries.map { it.wire }.toSet(),
        )
        OpenApiContract.assertSameElements(
            "RoutineSuggestionsResponse.missing_context",
            contract.arrayEnum("RoutineSuggestionsResponse", "missing_context"),
            RoutineMissingContext.entries.map { it.wire }.toSet(),
        )
        OpenApiContract.assertSameElements(
            "RoutineSuggestionsResponse.notices",
            contract.arrayEnum("RoutineSuggestionsResponse", "notices"),
            RoutineNotice.entries.map { it.wire }.toSet(),
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
