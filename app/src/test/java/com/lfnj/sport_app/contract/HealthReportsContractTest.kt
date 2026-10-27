package com.lfnj.sport_app.contract

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.features.health.data.HealthConsentDto
import com.lfnj.sport_app.features.health.data.HealthReportResponseDto
import com.lfnj.sport_app.features.health.data.HealthReportsApi
import com.lfnj.sport_app.features.health.data.ReportHealthRequestDto
import com.lfnj.sport_app.features.health.model.BodyArea
import com.lfnj.sport_app.features.health.model.HealthReportType
import com.lfnj.sport_app.features.health.model.HealthSeverity
import com.lfnj.sport_app.testing.HealthFixtures
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** HU042 en el cliente móvil frente a la copia exacta de `bff-mobile` 0.21.0 (ADR-037). */
class HealthReportsContractTest {
    private val contract = OpenApiContract.load()
    private val json = BffMobileClient.json

    @Test
    fun `la operacion de ADR-037 existe en el contrato con Idempotency-Key`() {
        contract.assertApiMatches(HealthReportsApi::class.java)
        assertTrue("idempotency-key" in contract.parameters("post", "/v1/me/health-reports", "header"))
    }

    @Test
    fun `el cuerpo y la respuesta corresponden al contrato`() {
        contract.assertRequestBodyMatches("post", "/v1/me/health-reports", ReportHealthRequestDto.serializer())
        contract.assertModelMatches("HealthReportResponse", HealthReportResponseDto.serializer())
        contract.assertModelMatches("HealthConsent", HealthConsentDto.serializer())
    }

    @Test
    fun `los catalogos del cliente son exactamente los del contrato`() {
        fun enumOf(property: String) = contract.propertySchema("HealthReportResponse", property).getValue("enum").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(enumOf("type"), HealthReportType.entries.map { it.wire })
        assertEquals(enumOf("body_area"), BodyArea.entries.map { it.wire })
        assertEquals(enumOf("severity"), HealthSeverity.entries.map { it.wire })
        assertEquals(HealthReportResponseDto.STATUS_OPEN, contract.propertySchema("HealthReportResponse", "status").getValue("const").jsonPrimitive.content)
    }

    @Test
    fun `el cliente trata todas las respuestas documentadas`() {
        assertTrue(
            contract.responseCodes("post", "/v1/me/health-reports")
                .containsAll(setOf("201", "401", "403", "404", "409", "422", "503", "504"))
        )
    }

    @Test
    fun `el cuerpo no lleva athlete_id, texto libre ni diagnostico`() {
        val descriptor = ReportHealthRequestDto.serializer().descriptor
        val fields = (0 until descriptor.elementsCount).map(descriptor::getElementName).toSet()

        assertEquals(
            setOf("report_id", "type", "body_area", "severity", "occurred_at", "session_id", "expected_recovery_days", "consent"),
            fields,
        )
    }

    @Test
    fun `CA1 la respuesta OPEN version 1 confirma el reporte enviado y otra no`() {
        val sent = ReportHealthRequestDto("r-1", "DISCOMFORT", "KNEE", "MODERATE", "2026-10-24T18:30:00Z", consent = HealthConsentDto(HealthFixtures.CONSENT_VERSION))
        val ok = json.decodeFromString(HealthReportResponseDto.serializer(), HealthFixtures.response("r-1"))
        val otherArea = json.decodeFromString(HealthReportResponseDto.serializer(), HealthFixtures.response("r-1", bodyArea = "HIP"))
        val closed = json.decodeFromString(HealthReportResponseDto.serializer(), HealthFixtures.response("r-1", status = "CLOSED"))

        assertEquals(1, ok.version)
        assertEquals(Instant.parse("2026-10-24T18:35:02Z"), ok.confirms(sent))
        assertNull(otherArea.confirms(sent))
        assertNull(closed.confirms(sent))
    }

    @Test
    fun `los opcionales nulos no se envian`() {
        val body = ReportHealthRequestDto("r-1", "INJURY", "BACK", "HIGH", "2026-10-24T18:30:00Z", consent = HealthConsentDto("v1"))
        val text = json.encodeToString(ReportHealthRequestDto.serializer(), body)

        assertFalse("session_id" in text)
        assertFalse("expected_recovery_days" in text)
    }

    @Test
    fun `los modelos con datos de salud no los exponen al convertirse en texto`() {
        val dto = json.decodeFromString(HealthReportResponseDto.serializer(), HealthFixtures.response("r-1", severity = "HIGH"))
        val body = ReportHealthRequestDto("r-1", "INJURY", "BACK", "HIGH", "2026-10-24T18:30:00Z", consent = HealthConsentDto("v1"))
        listOf(dto.toString(), body.toString()).forEach { text ->
            assertFalse(text, "HIGH" in text || "BACK" in text || "KNEE" in text || "INJURY" in text)
        }
    }
}
