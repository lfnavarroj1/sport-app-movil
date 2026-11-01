package com.lfnj.sport_app.contract

import com.lfnj.sport_app.features.services.data.NextIntervalDto
import com.lfnj.sport_app.features.services.data.ServiceFeeDto
import com.lfnj.sport_app.features.services.data.ServiceReferenceDto
import com.lfnj.sport_app.features.services.data.ServiceSuggestionItemDto
import com.lfnj.sport_app.features.services.data.ServiceSuggestionSourceDto
import com.lfnj.sport_app.features.services.data.ServiceSuggestionsResponseDto
import com.lfnj.sport_app.features.services.data.SupportSuggestionsApi
import com.lfnj.sport_app.features.services.model.BillingUnit
import com.lfnj.sport_app.features.services.model.SupportCategory
import com.lfnj.sport_app.features.services.model.SupportModality
import com.lfnj.sport_app.features.services.model.SupportReason
import com.lfnj.sport_app.features.services.model.SupportSource
import com.lfnj.sport_app.features.services.model.SupportSourceStatus
import com.lfnj.sport_app.features.services.model.SupportSuggestionsSnapshot
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** HU029 en el cliente móvil frente a la copia exacta de `bff-mobile` 0.18.0 (ADR-041 §4). */
class SupportSuggestionsContractTest {
    private val contract = OpenApiContract.load()
    private val path = "/v1/me/suggestions/services"

    @Test
    fun `la operacion de sugerencias existe con su limite`() {
        contract.assertApiMatches(SupportSuggestionsApi::class.java)
        assertEquals(setOf("limit"), contract.parameters("get", path, "query"))
        assertTrue("authorization" in contract.parameters("get", path, "header"))
        assertTrue(SupportSuggestionsApi.LIMIT in 1..50)
    }

    @Test
    fun `la respuesta, el item, la referencia, la tarifa, el intervalo y las fuentes corresponden al contrato`() {
        contract.assertModelMatches("ServiceSuggestionsResponse", ServiceSuggestionsResponseDto.serializer())
        contract.assertModelMatches("ServiceSuggestionItem", ServiceSuggestionItemDto.serializer())
        contract.assertModelMatches("ServiceReference", ServiceReferenceDto.serializer())
        contract.assertModelMatches("ServiceFee", ServiceFeeDto.serializer())
        contract.assertModelMatches("NextInterval", NextIntervalDto.serializer())
        contract.assertModelMatches("ServiceSuggestionSource", ServiceSuggestionSourceDto.serializer())
    }

    @Test
    fun `los catalogos del cliente son los del contrato y excluyen medicina deportiva`() {
        val categories = contract.enumValues("ServiceSuggestionItem", "category")
        OpenApiContract.assertSameElements("ServiceSuggestionItem.category", categories, SupportCategory.entries.map { it.wire }.toSet())
        assertTrue("SPORTS_MEDICINE" !in categories)
        OpenApiContract.assertSameElements(
            "ServiceSuggestionItem.modality",
            contract.enumValues("ServiceSuggestionItem", "modality"),
            SupportModality.entries.map { it.wire }.toSet(),
        )
        OpenApiContract.assertSameElements("ServiceFee.billing_unit", contract.enumValues("ServiceFee", "billing_unit"), BillingUnit.entries.map { it.wire }.toSet())
        OpenApiContract.assertSameElements(
            "ServiceSuggestionSource.source",
            contract.enumValues("ServiceSuggestionSource", "source"),
            SupportSource.entries.map { it.wire }.toSet(),
        )
        OpenApiContract.assertSameElements(
            "ServiceSuggestionSource.status",
            contract.enumValues("ServiceSuggestionSource", "status"),
            SupportSourceStatus.entries.map { it.name }.toSet(),
        )
        assertEquals("service", contract.const("ServiceReference", "type"))
        assertEquals("COP", contract.const("ServiceFee", "currency"))
    }

    @Test
    fun `motivos y contexto faltante son los del contrato`() {
        OpenApiContract.assertSameElements(
            "ServiceSuggestionItem.reasons",
            contract.propertySchema("ServiceSuggestionItem", "reasons").getValue("items").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }.toSet(),
            SupportReason.entries.map { it.wire }.toSet(),
        )
        assertEquals(
            SupportSuggestionsSnapshot.MISSING_EVENT_REGISTRATIONS,
            contract.propertySchema("ServiceSuggestionsResponse", "missing_context").getValue("items").jsonObject.getValue("const").jsonPrimitive.content,
        )
    }

    @Test
    fun `el cliente trata todas las respuestas documentadas`() {
        assertEquals(setOf("200", "401", "403", "422", "503", "504"), contract.responseCodes("get", path))
    }

    private fun OpenApiContract.const(schema: String, property: String): String =
        propertySchema(schema, property).getValue("const").jsonPrimitive.content
}
