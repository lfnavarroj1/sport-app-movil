package com.lfnj.sport_app.contract

import com.lfnj.sport_app.features.calendar.data.CalendarApi
import com.lfnj.sport_app.features.calendar.data.CalendarItemDto
import com.lfnj.sport_app.features.calendar.data.CalendarLocationDto
import com.lfnj.sport_app.features.calendar.data.CalendarResponseDto
import com.lfnj.sport_app.features.calendar.data.CalendarSourceDto
import com.lfnj.sport_app.features.calendar.model.CalendarItemSource
import com.lfnj.sport_app.features.calendar.model.CalendarSnapshot
import com.lfnj.sport_app.features.calendar.model.SourceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** HU035 en el cliente móvil frente a la copia exacta de `bff-mobile` (ADR-031). */
class CalendarContractTest {
    private val contract = OpenApiContract.load()

    @Test
    fun `la operacion del calendario existe con su periodo`() {
        contract.assertApiMatches(CalendarApi::class.java)
        assertEquals(setOf("from", "to"), contract.parameters("get", "/v1/me/calendar", "query"))
        assertTrue("authorization" in contract.parameters("get", "/v1/me/calendar", "header"))
    }

    @Test
    fun `la respuesta, las fuentes y la ubicacion corresponden al contrato`() {
        contract.assertModelMatches("CalendarResponse", CalendarResponseDto.serializer())
        contract.assertModelMatches("CalendarSource", CalendarSourceDto.serializer())
        contract.assertModelMatches("CalendarLocation", CalendarLocationDto.serializer())
        OpenApiContract.assertSameElements("CalendarSource.status", contract.enumValues("CalendarSource", "status"), SourceStatus.entries.map { it.name }.toSet())
        OpenApiContract.assertSameElements("CalendarSource.name", contract.enumValues("CalendarSource", "name"), CalendarSnapshot.EXPECTED_SOURCES.toSet())
    }

    /**
     * `CalendarItemDto` reúne las dos variantes de `items[]` (`EventCalendarItem` y
     * `SessionCalendarItem`). `reference` se omite a propósito: HU035 no abre el detalle.
     */
    @Test
    fun `el elemento del cliente cubre ambas variantes del contrato`() {
        val descriptor = CalendarItemDto.serializer().descriptor
        val model = (0 until descriptor.elementsCount).map(descriptor::getElementName).toSet()
        val variants = listOf("EventCalendarItem", "SessionCalendarItem")
        val properties = variants.flatMap(contract::schemaProperties).toSet()
        val required = variants.flatMap(contract::schemaRequired).toSet() - "reference"

        assertTrue("propiedades ausentes del contrato: ${model - properties}", (model - properties).isEmpty())
        assertTrue("faltan obligatorias: ${required - model}", (required - model).isEmpty())
        assertEquals(setOf("EVENT_PUBLISHED"), setOf(contract.const("EventCalendarItem", "source")))
        assertEquals(setOf("TRAINING_PLAN_SESSION"), setOf(contract.const("SessionCalendarItem", "source")))
        assertEquals(setOf("EVENT_PUBLISHED", "TRAINING_PLAN_SESSION"), CalendarItemSource.entries.map { it.wire }.toSet())
    }

    @Test
    fun `el cliente trata todas las respuestas documentadas`() {
        assertEquals(setOf("200", "401", "403", "422", "503", "504"), contract.responseCodes("get", "/v1/me/calendar"))
    }

    private fun OpenApiContract.const(schema: String, property: String): String =
        propertySchema(schema, property).getValue("const").toString().trim('"')
}
