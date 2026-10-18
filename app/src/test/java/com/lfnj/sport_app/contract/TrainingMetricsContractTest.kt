package com.lfnj.sport_app.contract

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.features.training.metrics.data.CalculateTrainingMetricRequestDto
import com.lfnj.sport_app.features.training.metrics.data.CatalogInputDto
import com.lfnj.sport_app.features.training.metrics.data.CatalogMethodDto
import com.lfnj.sport_app.features.training.metrics.data.MetricInputValueDto
import com.lfnj.sport_app.features.training.metrics.data.MetricResultDto
import com.lfnj.sport_app.features.training.metrics.data.TrainingMetricCalculationDto
import com.lfnj.sport_app.features.training.metrics.data.TrainingMetricCalculationsDto
import com.lfnj.sport_app.features.training.metrics.data.TrainingMetricCatalogDto
import com.lfnj.sport_app.features.training.metrics.data.TrainingMetricsApi
import com.lfnj.sport_app.features.training.metrics.model.MetricMethod
import com.lfnj.sport_app.testing.MetricsFixtures
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** HU034 en el cliente móvil frente a la copia exacta de `bff-mobile` (ADR-030). */
class TrainingMetricsContractTest {
    private val contract = OpenApiContract.load()
    private val json = BffMobileClient.json

    @Test
    fun `las operaciones de ADR-030 existen en el contrato`() {
        contract.assertApiMatches(TrainingMetricsApi::class.java)
    }

    @Test
    fun `los cuerpos y respuestas corresponden al contrato`() {
        contract.assertRequestBodyMatches("post", "/v1/training-metrics/calculations", CalculateTrainingMetricRequestDto.serializer())
        contract.assertModelMatches("TrainingMetricCatalogResponse", TrainingMetricCatalogDto.serializer())
        contract.assertModelMatches("CatalogMethod", CatalogMethodDto.serializer())
        contract.assertModelMatches("CatalogInput", CatalogInputDto.serializer())
        contract.assertModelMatches("MetricInputValue", MetricInputValueDto.serializer())
        contract.assertModelMatches("MetricResult", MetricResultDto.serializer())
        contract.assertModelMatches("TrainingMetricCalculationResponse", TrainingMetricCalculationDto.serializer())
        contract.assertModelMatches("TrainingMetricCalculationsResponse", TrainingMetricCalculationsDto.serializer())
    }

    @Test
    fun `el calculo documenta Idempotency-Key y el catalogo If-None-Match con ETag`() {
        assertTrue("idempotency-key" in contract.parameters("post", "/v1/training-metrics/calculations", "header"))
        assertTrue("if-none-match" in contract.parameters("get", "/v1/training-metrics/catalog", "header"))
        assertEquals(setOf("metric_code", "limit", "cursor"), contract.parameters("get", "/v1/training-metrics/calculations", "query"))
    }

    @Test
    fun `el unico tipo de entrada del contrato es el que el cliente captura`() {
        val valueType = contract.propertySchema("CatalogInput", "value_type").getValue("const").jsonPrimitive.content
        assertEquals(MetricMethod.VALUE_TYPE_INTEGER, valueType)
        assertEquals(TrainingMetricCalculationDto.STATUS_CONFIRMED, contract.propertySchema("TrainingMetricCalculationResponse", "status").getValue("const").jsonPrimitive.content)
    }

    @Test
    fun `el cliente trata todas las respuestas documentadas`() {
        assertTrue(contract.responseCodes("get", "/v1/training-metrics/catalog").containsAll(setOf("200", "304", "401", "403", "503", "504")))
        assertTrue(contract.responseCodes("post", "/v1/training-metrics/calculations").containsAll(setOf("200", "201", "401", "403", "409", "422", "503", "504")))
        assertTrue(contract.responseCodes("get", "/v1/training-metrics/calculations").containsAll(setOf("200", "401", "403", "422", "503", "504")))
    }

    @Test
    fun `CA1 y CA2 los ejemplos de ADR-030 se interpretan con su valor exacto`() {
        val ftp = checkNotNull(json.decodeFromString(TrainingMetricCalculationDto.serializer(), MetricsFixtures.ftpResult()).toModel())
        val cooper = checkNotNull(json.decodeFromString(TrainingMetricCalculationDto.serializer(), MetricsFixtures.cooperResult("c")).toModel())

        assertEquals("238", ftp.value.toPlainString())
        assertEquals(0, ftp.scale)
        assertEquals("W", ftp.unit)
        assertEquals("42.4", cooper.value.toPlainString())
        assertEquals(1, cooper.scale)
        assertEquals("mL/kg/min", cooper.unit)
    }

    @Test
    fun `CA7 el catalogo real y uno con un tercer indicador se interpretan sin cambio de codigo`() {
        val real = json.decodeFromString(TrainingMetricCatalogDto.serializer(), MetricsFixtures.CATALOG)
        val extended = json.decodeFromString(TrainingMetricCatalogDto.serializer(), MetricsFixtures.CATALOG_WITH_THIRD)

        assertEquals(listOf("FTP_20_MIN_95_V1", "COOPER_12_MIN_V1"), real.methods.map { it.methodCode })
        assertTrue(extended.methods.map { it.toModel() }.all(MetricMethod::supported))
        assertEquals("TEST_SCALAR_V1", extended.methods.last().methodCode)
    }

    @Test
    fun `el cuerpo que envia el cliente no lleva athlete_id ni un resultado calculado en el dispositivo`() {
        val descriptor = CalculateTrainingMetricRequestDto.serializer().descriptor
        val fields = (0 until descriptor.elementsCount).map(descriptor::getElementName).toSet()

        assertEquals(setOf("calculation_id", "metric_code", "method_code", "method_version", "measured_at", "inputs"), fields)
    }

    @Test
    fun `los modelos con datos sensibles no los exponen al convertirse en texto`() {
        val dto = json.decodeFromString(TrainingMetricCalculationDto.serializer(), MetricsFixtures.ftpResult())
        listOf(dto.toString(), dto.result.toString(), dto.inputs.first().toString()).forEach { text ->
            assertFalse(text, "238" in text || "250" in text)
        }
    }
}
