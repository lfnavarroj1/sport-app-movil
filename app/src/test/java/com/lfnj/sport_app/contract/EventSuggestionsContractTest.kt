package com.lfnj.sport_app.contract

import com.lfnj.sport_app.features.events.data.EventFeeDto
import com.lfnj.sport_app.features.events.data.EventLocationDto
import com.lfnj.sport_app.features.events.data.EventSuggestionItemDto
import com.lfnj.sport_app.features.events.data.EventSuggestionsApi
import com.lfnj.sport_app.features.events.data.EventSuggestionsResponseDto
import com.lfnj.sport_app.features.events.data.MyRegistrationDto
import com.lfnj.sport_app.features.events.data.SuggestedEventReferenceDto
import com.lfnj.sport_app.features.events.model.EventMissingContext
import com.lfnj.sport_app.features.events.model.EventSuggestionReason
import com.lfnj.sport_app.features.events.model.EventSuggestionsSnapshot
import com.lfnj.sport_app.features.recommendations.data.SuggestionSourceDto
import com.lfnj.sport_app.features.recommendations.model.ContextSourceStatus
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** HU027 en el cliente móvil frente a la copia exacta de `bff-mobile` 0.15.0 (ADR-041 §4 y §HU027). */
class EventSuggestionsContractTest {
    private val contract = OpenApiContract.load()
    private val path = "/v1/me/suggestions/events"

    @Test
    fun `la operacion de eventos sugeridos existe con su limite`() {
        contract.assertApiMatches(EventSuggestionsApi::class.java)
        assertEquals(setOf("limit"), contract.parameters("get", path, "query"))
        assertTrue("authorization" in contract.parameters("get", path, "header"))
        assertTrue(EventSuggestionsApi.LIMIT in 1..50)
    }

    @Test
    fun `la respuesta, el item, la referencia, el lugar, la tarifa, la inscripcion y las fuentes corresponden al contrato`() {
        contract.assertModelMatches("EventSuggestionsResponse", EventSuggestionsResponseDto.serializer())
        contract.assertModelMatches("EventSuggestionItem", EventSuggestionItemDto.serializer())
        contract.assertModelMatches("SuggestedEventReference", SuggestedEventReferenceDto.serializer())
        contract.assertModelMatches("Location", EventLocationDto.serializer())
        contract.assertModelMatches("EventFee", EventFeeDto.serializer())
        contract.assertModelMatches("MyRegistration", MyRegistrationDto.serializer())
        contract.assertModelMatches("SuggestionSource", SuggestionSourceDto.serializer())
        assertEquals("#/components/schemas/SuggestedEventReference", contract.ref("EventSuggestionItem", "reference"))
        assertEquals("#/components/schemas/Location", contract.ref("EventSuggestionItem", "location"))
        assertEquals(
            "#/components/schemas/EventSuggestionItem",
            contract.propertySchema("EventSuggestionsResponse", "items").getValue("items").jsonObject.getValue("\$ref").jsonPrimitive.content,
        )
    }

    @Test
    fun `fee y my_registration son obligatorios y anulables`() {
        mapOf("fee" to "#/components/schemas/EventFee", "my_registration" to "#/components/schemas/MyRegistration").forEach { (field, ref) ->
            assertTrue(field in contract.schemaRequired("EventSuggestionItem"))
            val options = contract.propertySchema("EventSuggestionItem", field).getValue("anyOf").jsonArray.map { it.jsonObject }
            assertTrue(field, options.any { it["\$ref"]?.jsonPrimitive?.content == ref })
            assertTrue(field, options.any { it["type"]?.jsonPrimitive?.content == "null" })
        }
    }

    @Test
    fun `motivos, contexto faltante y constantes son los del contrato`() {
        OpenApiContract.assertSameElements(
            "EventSuggestionItem.reasons",
            contract.arrayEnum("EventSuggestionItem", "reasons"),
            EventSuggestionReason.entries.map { it.wire }.toSet(),
        )
        OpenApiContract.assertSameElements(
            "EventSuggestionsResponse.missing_context",
            contract.arrayEnum("EventSuggestionsResponse", "missing_context"),
            EventMissingContext.entries.map { it.wire }.toSet(),
        )
        OpenApiContract.assertSameElements("SuggestionSource.status", contract.enumValues("SuggestionSource", "status"), ContextSourceStatus.entries.map { it.name }.toSet())
        assertEquals(EventSuggestionsSnapshot.SOURCE_USERS, contract.const("SuggestionSource", "source"))
        assertEquals("event", contract.const("SuggestedEventReference", "type"))
        assertEquals("REGISTERED", contract.const("MyRegistration", "status"))
        assertEquals("integer", contract.propertySchema("EventSuggestionItem", "capacity").getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `el cliente trata todas las respuestas documentadas`() {
        assertEquals(setOf("200", "401", "403", "422", "503", "504"), contract.responseCodes("get", path))
    }

    private fun OpenApiContract.ref(schema: String, property: String): String =
        propertySchema(schema, property).getValue("\$ref").jsonPrimitive.content

    private fun OpenApiContract.const(schema: String, property: String): String =
        propertySchema(schema, property).getValue("const").toString().trim('"')

    private fun OpenApiContract.arrayEnum(schema: String, property: String): Set<String> =
        propertySchema(schema, property).getValue("items").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }.toSet()
}
