package com.lfnj.sport_app.contract

import com.lfnj.sport_app.features.integrations.data.StravaApi
import com.lfnj.sport_app.features.integrations.data.StravaAuthorizationResponseDto
import com.lfnj.sport_app.features.integrations.data.StravaIntegrationResponseDto
import com.lfnj.sport_app.features.integrations.data.StravaSyncResponseDto
import com.lfnj.sport_app.features.integrations.data.StravaSyncSummaryDto
import com.lfnj.sport_app.features.integrations.model.StravaLinkResult
import com.lfnj.sport_app.features.integrations.model.StravaLinkStatus
import com.lfnj.sport_app.features.integrations.model.StravaSyncError
import com.lfnj.sport_app.features.integrations.model.StravaSyncStatus
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** HU031 en el cliente móvil frente a la copia exacta de `bff-mobile` 0.22.0 (ADR-042). */
class StravaContractTest {
    private val contract = OpenApiContract.load()
    private val integration = "/v1/me/integrations/strava"
    private val authorization = "/v1/me/integrations/strava/authorization"
    private val syncs = "/v1/me/integrations/strava/syncs"
    private val callback = "/v1/integrations/strava/callback"

    @Test
    fun `las cuatro operaciones del cliente existen con su metodo HTTP`() {
        contract.assertApiMatches(StravaApi::class.java)
        assertTrue(contract.hasOperation("post", authorization))
        assertTrue(contract.hasOperation("get", integration))
        assertTrue(contract.hasOperation("delete", integration))
        assertTrue(contract.hasOperation("post", syncs))
    }

    @Test
    fun `todas llevan el token y la sincronizacion la clave de idempotencia`() {
        listOf("post" to authorization, "get" to integration, "delete" to integration, "post" to syncs).forEach { (method, path) ->
            assertTrue("$method $path", "authorization" in contract.parameters(method, path, "header"))
        }
        assertEquals(setOf("idempotency-key", "authorization"), contract.parameters("post", syncs, "header"))
        assertTrue(contract.parameters("get", integration, "query").isEmpty())
    }

    @Test
    fun `los modelos corresponden a los esquemas del contrato y ninguno transporta tokens`() {
        contract.assertModelMatches("StravaAuthorizationResponse", StravaAuthorizationResponseDto.serializer())
        contract.assertModelMatches("StravaIntegrationResponse", StravaIntegrationResponseDto.serializer())
        contract.assertModelMatches("StravaSyncSummary", StravaSyncSummaryDto.serializer())
        contract.assertModelMatches("StravaSyncResponse", StravaSyncResponseDto.serializer())
        listOf("StravaAuthorizationResponse", "StravaIntegrationResponse", "StravaSyncSummary", "StravaSyncResponse").forEach { schema ->
            contract.schemaProperties(schema).forEach { property ->
                assertFalse("$schema.$property", "token" in property || "secret" in property || property == "code" || property == "state")
            }
        }
    }

    @Test
    fun `los estados y codigos de error del contrato son los que el cliente conoce`() {
        OpenApiContract.assertSameElements(
            "StravaIntegrationResponse.status", contract.enumValues("StravaIntegrationResponse", "status"), StravaLinkStatus.entries.map { it.name }.toSet(),
        )
        OpenApiContract.assertSameElements(
            "StravaSyncSummary.status", contract.enumValues("StravaSyncSummary", "status"), StravaSyncStatus.entries.map { it.name }.toSet(),
        )
        OpenApiContract.assertSameElements(
            "StravaSyncResponse.status", contract.enumValues("StravaSyncResponse", "status"), StravaSyncStatus.entries.map { it.name }.toSet(),
        )
        val errorCodes = contract.propertySchema("StravaSyncSummary", "error_code").getValue("anyOf").jsonArray
            .map { it.jsonObject }
            .firstNotNullOf { it["enum"]?.jsonArray }
            .map { it.jsonPrimitive.content }
            .toSet()
        OpenApiContract.assertSameElements("StravaSyncSummary.error_code", errorCodes, StravaSyncError.entries.map { it.wire }.toSet())
    }

    @Test
    fun `el cliente trata todas las respuestas documentadas`() {
        assertEquals(setOf("201", "401", "403", "422", "503", "504"), contract.responseCodes("post", authorization))
        assertEquals(setOf("200", "401", "403", "422", "503", "504"), contract.responseCodes("get", integration))
        assertEquals(setOf("204", "401", "403", "422", "503", "504"), contract.responseCodes("delete", integration))
        assertEquals(setOf("202", "401", "403", "409", "422", "503", "504"), contract.responseCodes("post", syncs))
    }

    @Test
    fun `el callback publico redirige al enlace profundo con los cuatro resultados que la app procesa`() {
        assertEquals(setOf("302", "422"), contract.responseCodes("get", callback))
        val location = checkNotNull(
            Regex("`(sportapp://[^`?]+)\\?result=<RESULT>` con `RESULT` en (.+)\\.").find(callbackLocationDescription())
        ) { "La descripción de Location no documenta el enlace profundo" }
        assertEquals("sportapp://integrations/strava", location.groupValues[1])
        assertEquals("${StravaLinkResult.SCHEME}://${StravaLinkResult.HOST}${StravaLinkResult.PATH}", location.groupValues[1])
        val results = Regex("`([A-Z_]+)`").findAll(location.groupValues[2]).map { it.groupValues[1] }.toSet()
        OpenApiContract.assertSameElements("result del enlace profundo", results, StravaLinkResult.entries.map { it.name }.toSet())
    }

    private fun callbackLocationDescription(): String {
        val document = kotlinx.serialization.json.Json.parseToJsonElement(
            checkNotNull(OpenApiContract::class.java.getResourceAsStream(OpenApiContract.RESOURCE)).bufferedReader(Charsets.UTF_8).use { it.readText() }
        ).jsonObject
        return document.getValue("paths").jsonObject.getValue(callback).jsonObject.getValue("get").jsonObject
            .getValue("responses").jsonObject.getValue("302").jsonObject.getValue("headers").jsonObject
            .getValue("Location").jsonObject.getValue("description").jsonPrimitive.content
    }
}
