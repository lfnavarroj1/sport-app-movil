package com.lfnj.sport_app.contract

import com.lfnj.sport_app.features.events.data.EventDetailDto
import com.lfnj.sport_app.features.events.data.EventFeeDto
import com.lfnj.sport_app.features.events.data.EventLocationDto
import com.lfnj.sport_app.features.events.data.EventRegistrationApi
import com.lfnj.sport_app.features.events.data.MyRegistrationDto
import com.lfnj.sport_app.features.events.data.RegistrationDto
import com.lfnj.sport_app.features.events.data.RegistrationEventDto
import com.lfnj.sport_app.features.events.data.RegistrationsDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** HU041 en el cliente móvil frente a la copia exacta de `bff-mobile` (ADR-032 «Contrato público»). */
class EventRegistrationContractTest {
    private val contract = OpenApiContract.load()

    @Test
    fun `las tres operaciones existen con sus parametros`() {
        contract.assertApiMatches(EventRegistrationApi::class.java)
        assertEquals(setOf("event_id"), contract.parameters("get", "/v1/events/{event_id}", "path"))
        assertEquals(setOf("event_id"), contract.parameters("post", "/v1/events/{event_id}/registrations", "path"))
        assertTrue("idempotency-key" in contract.parameters("post", "/v1/events/{event_id}/registrations", "header"))
        assertTrue("authorization" in contract.parameters("post", "/v1/events/{event_id}/registrations", "header"))
        // El cliente no envía `status`: la única inscripción vigente es `REGISTERED`.
        assertTrue(setOf("limit", "cursor").all { it in contract.parameters("get", "/v1/me/event-registrations", "query") })
    }

    @Test
    fun `la inscripcion no tiene cuerpo ni athlete_id`() {
        val register = EventRegistrationApi::class.java.declaredMethods.single { it.name == "register" }
        assertTrue(register.parameterAnnotations.flatMap { it.toList() }.none { it is retrofit2.http.Body })
        assertTrue("athlete_id" !in contract.schemaProperties("RegistrationResponse"))
        assertTrue("athlete_id" !in contract.schemaProperties("EventDetailResponse"))
    }

    @Test
    fun `los modelos del cliente corresponden a los esquemas`() {
        contract.assertModelMatches("EventDetailResponse", EventDetailDto.serializer())
        contract.assertModelMatches("Location", EventLocationDto.serializer())
        contract.assertModelMatches("EventFee", EventFeeDto.serializer())
        contract.assertModelMatches("Fee", EventFeeDto.serializer())
        contract.assertModelMatches("MyRegistration", MyRegistrationDto.serializer())
        contract.assertModelMatches("RegistrationResponse", RegistrationDto.serializer())
        contract.assertModelMatches("RegistrationEvent", RegistrationEventDto.serializer())
        contract.assertModelMatches("RegistrationsResponse", RegistrationsDto.serializer())
    }

    @Test
    fun `los estados fijos coinciden con los que valida el cliente`() {
        assertEquals("PUBLISHED", contract.const("EventDetailResponse", "status"))
        assertEquals("REGISTERED", contract.const("MyRegistration", "status"))
        assertEquals("REGISTERED", contract.const("RegistrationResponse", "status"))
    }

    @Test
    fun `el cliente trata todas las respuestas documentadas`() {
        assertEquals(setOf("200", "401", "403", "404", "422", "503", "504"), contract.responseCodes("get", "/v1/events/{event_id}"))
        assertEquals(
            setOf("201", "401", "403", "404", "409", "422", "503", "504"),
            contract.responseCodes("post", "/v1/events/{event_id}/registrations"),
        )
        assertEquals(setOf("200", "401", "403", "422", "503", "504"), contract.responseCodes("get", "/v1/me/event-registrations"))
    }

    private fun OpenApiContract.const(schema: String, property: String): String =
        propertySchema(schema, property).getValue("const").toString().trim('"')
}
