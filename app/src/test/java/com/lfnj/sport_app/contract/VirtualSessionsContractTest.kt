package com.lfnj.sport_app.contract

import com.lfnj.sport_app.features.services.data.AppointmentDto
import com.lfnj.sport_app.features.services.data.AppointmentsResponseDto
import com.lfnj.sport_app.features.services.data.AssociatedServiceSummaryDto
import com.lfnj.sport_app.features.services.data.BookAppointmentRequestDto
import com.lfnj.sport_app.features.services.data.CatalogIntervalDto
import com.lfnj.sport_app.features.services.data.CatalogServiceDto
import com.lfnj.sport_app.features.services.data.ServiceAvailabilityResponseDto
import com.lfnj.sport_app.features.services.data.ServicePriceDto
import com.lfnj.sport_app.features.services.data.VirtualSessionsApi
import com.lfnj.sport_app.features.services.model.AppointmentStatus
import com.lfnj.sport_app.features.services.model.BillingUnit
import com.lfnj.sport_app.features.services.model.ServiceModality
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.DELETE

/** HU040 en el cliente móvil frente a la copia exacta de `bff-mobile` 0.18.0 (ADR-046). */
class VirtualSessionsContractTest {
    private val contract = OpenApiContract.load()
    private val availability = "/v1/services/availability"
    private val appointments = "/v1/me/appointments"
    private val appointment = "/v1/me/appointments/{appointment_id}"

    @Test
    fun `las operaciones de HU040 existen con sus parametros`() {
        contract.assertApiMatches(VirtualSessionsApi::class.java)
        val delete = VirtualSessionsApi::class.java.declaredMethods.mapNotNull { it.getAnnotation(DELETE::class.java)?.value }
        assertEquals(listOf("v1/me/appointments/{appointment_id}"), delete)
        assertTrue(contract.hasOperation("delete", appointment))

        assertTrue(setOf("from", "to", "category", "modality", "limit", "cursor").all { it in contract.parameters("get", availability, "query") })
        assertEquals(setOf("from", "to", "status", "limit", "cursor"), contract.parameters("get", appointments, "query"))
        assertTrue("idempotency-key" in contract.parameters("post", appointments, "header"))
        assertEquals(setOf("appointment_id"), contract.parameters("delete", appointment, "path"))
        listOf("get" to availability, "get" to appointments, "post" to appointments, "delete" to appointment).forEach { (method, path) ->
            assertTrue("$method $path", "authorization" in contract.parameters(method, path, "header"))
        }
        assertTrue(VirtualSessionsApi.CATALOG_LIMIT in 1..50)
        assertTrue(VirtualSessionsApi.APPOINTMENTS_LIMIT in 1..100)
    }

    @Test
    fun `los modelos del catalogo y de la cita corresponden al contrato`() {
        contract.assertModelMatches("ServiceAvailabilityResponse", ServiceAvailabilityResponseDto.serializer())
        contract.assertModelMatches("CatalogService", CatalogServiceDto.serializer())
        contract.assertModelMatches("CatalogInterval", CatalogIntervalDto.serializer())
        contract.assertModelMatches("ServicePrice", ServicePriceDto.serializer())
        contract.assertModelMatches("AppointmentsResponse", AppointmentsResponseDto.serializer())
        contract.assertModelMatches("AppointmentResponse", AppointmentDto.serializer())
        contract.assertModelMatches("AssociatedServiceSummary", AssociatedServiceSummaryDto.serializer())
        contract.assertRequestBodyMatches("post", appointments, BookAppointmentRequestDto.serializer())
    }

    @Test
    fun `los catalogos del cliente son los del contrato`() {
        assertTrue(VirtualSessionsApi.CATEGORY_COACHING in contract.enumOf("Category"))
        assertTrue(ServiceModality.entries.map { it.wire }.toSet().all { it in contract.enumOf("Modality") })
        OpenApiContract.assertSameElements("BillingUnit", contract.enumOf("BillingUnit"), BillingUnit.entries.map { it.wire }.toSet())
        OpenApiContract.assertSameElements(
            "AppointmentResponse.status",
            contract.enumValues("AppointmentResponse", "status"),
            AppointmentStatus.entries.map { it.wire }.toSet(),
        )
    }

    @Test
    fun `meeting_url es siempre nulo y cancelled_at es obligatorio y anulable`() {
        assertEquals("null", contract.propertySchema("AppointmentResponse", "meeting_url").getValue("type").jsonPrimitive.content)
        assertTrue("cancelled_at" in contract.schemaRequired("AppointmentResponse"))
        val types = contract.propertySchema("AppointmentResponse", "cancelled_at").getValue("anyOf").jsonArray
            .map { it.jsonObject.getValue("type").jsonPrimitive.content }.toSet()
        assertEquals(setOf("string", "null"), types)
    }

    @Test
    fun `el cliente trata todas las respuestas documentadas`() {
        assertEquals(setOf("200", "401", "403", "422", "503", "504"), contract.responseCodes("get", availability))
        assertEquals(setOf("200", "401", "403", "422", "503", "504"), contract.responseCodes("get", appointments))
        assertEquals(setOf("201", "401", "403", "409", "422", "503", "504"), contract.responseCodes("post", appointments))
        assertEquals(setOf("200", "401", "403", "404", "422", "503", "504"), contract.responseCodes("delete", appointment))
    }

    @Test
    fun `los codigos de error que distingue el cliente estan documentados`() {
        val post = contract.responseDescriptions("post", appointments)
        listOf("availability_full", "appointment_exists", "idempotency_key_reused").forEach { assertTrue(it, it in post.getValue("409")) }
        listOf("service_not_bookable", "availability_not_bookable").forEach { assertTrue(it, it in post.getValue("422")) }
        assertTrue("capability_required" in post.getValue("403"))
        val delete = contract.responseDescriptions("delete", appointment)
        assertTrue("cancellation_window_closed" in delete.getValue("422"))
        assertTrue("appointment_not_found" in delete.getValue("404"))
    }

    /** Lectura directa de la copia para enumeraciones de nivel superior y descripciones de respuestas. */
    private val document by lazy {
        val text = checkNotNull(javaClass.getResourceAsStream(OpenApiContract.RESOURCE)).bufferedReader(Charsets.UTF_8).use { it.readText() }
        Json.parseToJsonElement(text).jsonObject
    }

    private fun OpenApiContract.enumOf(schema: String): Set<String> =
        document.getValue("components").jsonObject.getValue("schemas").jsonObject.getValue(schema).jsonObject
            .getValue("enum").jsonArray.map { it.jsonPrimitive.content }.toSet()

    private fun OpenApiContract.responseDescriptions(method: String, path: String): Map<String, String> =
        document.getValue("paths").jsonObject.getValue(path).jsonObject.getValue(method).jsonObject.getValue("responses").jsonObject
            .mapValues { (_, response) -> response.jsonObject.getValue("description").jsonPrimitive.content }
}
