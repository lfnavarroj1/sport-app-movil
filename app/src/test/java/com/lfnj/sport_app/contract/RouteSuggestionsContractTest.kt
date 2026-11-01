package com.lfnj.sport_app.contract

import com.lfnj.sport_app.features.recommendations.data.RoutePointDto
import com.lfnj.sport_app.features.recommendations.data.RouteReferenceDto
import com.lfnj.sport_app.features.recommendations.data.RouteSuggestionItemDto
import com.lfnj.sport_app.features.recommendations.data.RouteSuggestionsApi
import com.lfnj.sport_app.features.recommendations.data.RouteSuggestionsResponseDto
import com.lfnj.sport_app.features.recommendations.data.SuggestionSourceDto
import com.lfnj.sport_app.features.recommendations.model.ContextSourceStatus
import com.lfnj.sport_app.features.recommendations.model.RouteDifficulty
import com.lfnj.sport_app.features.recommendations.model.RouteMissingContext
import com.lfnj.sport_app.features.recommendations.model.RouteSuggestionReason
import com.lfnj.sport_app.features.recommendations.model.RouteSuggestionsSnapshot
import com.lfnj.sport_app.features.training.session.model.TrainingSport
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** HU026 en el cliente móvil frente a la copia exacta de `bff-mobile` 0.15.0 (ADR-041 §4). */
class RouteSuggestionsContractTest {
    private val contract = OpenApiContract.load()
    private val path = "/v1/me/suggestions/routes"

    @Test
    fun `la operacion de rutas sugeridas existe con su limite`() {
        contract.assertApiMatches(RouteSuggestionsApi::class.java)
        assertEquals(setOf("limit"), contract.parameters("get", path, "query"))
        assertTrue("authorization" in contract.parameters("get", path, "header"))
        assertTrue(RouteSuggestionsApi.LIMIT in 1..50)
    }

    @Test
    fun `la respuesta, el item, la referencia, los puntos y las fuentes corresponden al contrato`() {
        contract.assertModelMatches("RouteSuggestionsResponse", RouteSuggestionsResponseDto.serializer())
        contract.assertModelMatches("RouteSuggestionItem", RouteSuggestionItemDto.serializer())
        contract.assertModelMatches("SuggestionReference", RouteReferenceDto.serializer())
        contract.assertModelMatches("RoutePoint", RoutePointDto.serializer())
        contract.assertModelMatches("SuggestionSource", SuggestionSourceDto.serializer())
        assertEquals("#/components/schemas/SuggestionReference", contract.propertySchema("RouteSuggestionItem", "reference").getValue("\$ref").jsonPrimitive.content)
        assertEquals("#/components/schemas/RoutePoint", contract.propertySchema("RouteSuggestionItem", "start_point").getValue("\$ref").jsonPrimitive.content)
        assertEquals(
            "#/components/schemas/RoutePoint",
            contract.propertySchema("RouteSuggestionItem", "waypoints").getValue("items").jsonObject.getValue("\$ref").jsonPrimitive.content,
        )
    }

    @Test
    fun `los catalogos y constantes del cliente son los del contrato`() {
        OpenApiContract.assertSameElements(
            "RouteSuggestionItem.sport",
            contract.enumValues("RouteSuggestionItem", "sport"),
            TrainingSport.entries.map { it.wire }.toSet(),
        )
        OpenApiContract.assertSameElements(
            "RouteSuggestionItem.difficulty",
            contract.enumValues("RouteSuggestionItem", "difficulty"),
            RouteDifficulty.entries.map { it.wire }.toSet(),
        )
        OpenApiContract.assertSameElements("SuggestionSource.status", contract.enumValues("SuggestionSource", "status"), ContextSourceStatus.entries.map { it.name }.toSet())
        assertEquals(RouteSuggestionsSnapshot.SOURCE_USERS, contract.const("SuggestionSource", "source"))
        assertEquals("route", contract.const("SuggestionReference", "type"))
        assertEquals("ORGANIZER", contract.const("RouteSuggestionItem", "source"))
    }

    @Test
    fun `motivos y contexto faltante son los del contrato`() {
        OpenApiContract.assertSameElements(
            "RouteSuggestionItem.reasons",
            contract.arrayEnum("RouteSuggestionItem", "reasons"),
            RouteSuggestionReason.entries.map { it.wire }.toSet(),
        )
        OpenApiContract.assertSameElements(
            "RouteSuggestionsResponse.missing_context",
            contract.arrayEnum("RouteSuggestionsResponse", "missing_context"),
            RouteMissingContext.entries.map { it.wire }.toSet(),
        )
    }

    @Test
    fun `summary y author_name son obligatorios y anulables`() {
        listOf("summary", "author_name").forEach { field ->
            assertTrue(field in contract.schemaRequired("RouteSuggestionItem"))
            val types = contract.propertySchema("RouteSuggestionItem", field).getValue("anyOf").jsonArray
                .map { it.jsonObject.getValue("type").jsonPrimitive.content }.toSet()
            assertEquals(setOf("string", "null"), types)
        }
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
