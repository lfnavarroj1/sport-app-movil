package com.lfnj.sport_app.contract

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.features.sensors.data.EvaluationDto
import com.lfnj.sport_app.features.sensors.data.MeasurementBatchResponseDto
import com.lfnj.sport_app.features.sensors.data.RiskAlertDto
import com.lfnj.sport_app.features.training.monitoring.data.MonitoringApi
import com.lfnj.sport_app.features.training.monitoring.data.MonitoringSettingsDto
import com.lfnj.sport_app.features.training.monitoring.data.MonitoringSettingsRequestDto
import com.lfnj.sport_app.features.training.monitoring.model.EvaluationStatus
import com.lfnj.sport_app.features.training.monitoring.model.RULE_HR_ABOVE_THRESHOLD_V1
import com.lfnj.sport_app.features.training.monitoring.model.ThresholdRules
import com.lfnj.sport_app.features.training.monitoring.model.ThresholdSource
import com.lfnj.sport_app.features.training.session.data.FinishTrainingSessionRequestDto
import com.lfnj.sport_app.features.training.session.data.TrainingSessionDto
import com.lfnj.sport_app.features.training.session.model.SessionEndReason
import com.lfnj.sport_app.testing.MonitoringFixtures
import com.lfnj.sport_app.testing.SensorsFixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.Header

/** HU036 en el cliente móvil frente a la copia exacta de `bff-mobile` 0.22.0 (ADR-044; HU031 solo agregó operaciones de Strava). */
class MonitoringContractTest {
    private val contract = OpenApiContract.load()
    private val settings = "/v1/me/monitoring-settings"
    private val measurements = "/v1/training-sessions/{session_id}/measurements"
    private val finish = "/v1/training-sessions/{session_id}/finish"

    private val document by lazy {
        val text = checkNotNull(javaClass.getResourceAsStream(OpenApiContract.RESOURCE)).bufferedReader(Charsets.UTF_8).use { it.readText() }
        Json.parseToJsonElement(text).jsonObject
    }

    private val schemas: JsonObject get() = document.getValue("components").jsonObject.getValue("schemas").jsonObject

    private fun enumOf(schema: String): Set<String> = schemas.getValue(schema).jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }.toSet()

    private fun requestSchema(method: String, path: String): JsonObject =
        document.getValue("paths").jsonObject.getValue(path).jsonObject.getValue(method).jsonObject
            .getValue("requestBody").jsonObject.getValue("content").jsonObject
            .getValue("application/json").jsonObject.getValue("schema").jsonObject

    private fun responseDescriptions(method: String, path: String): Map<String, String> =
        document.getValue("paths").jsonObject.getValue(path).jsonObject.getValue(method).jsonObject.getValue("responses").jsonObject
            .mapValues { (_, response) -> response.jsonObject.getValue("description").jsonPrimitive.content }

    @Test
    fun `el cliente consume la version 0_22_0`() {
        assertEquals("0.22.0", contract.version)
    }

    @Test
    fun `las operaciones del umbral existen con sus parametros y encabezados`() {
        contract.assertApiMatches(MonitoringApi::class.java)
        assertTrue(contract.hasOperation("get", settings))
        assertTrue(contract.hasOperation("put", settings))
        listOf("get", "put").forEach { assertTrue(it, "authorization" in contract.parameters(it, settings, "header")) }

        val headers = MonitoringApi::class.java.declaredMethods
            .flatMap { method -> method.parameterAnnotations.flatMap { it.toList() } }
            .filterIsInstance<Header>()
            .map { it.value.lowercase() }
            .toSet()
        assertEquals(setOf("authorization"), headers)
    }

    @Test
    fun `el cuerpo del umbral solo usa propiedades del esquema cerrado`() {
        contract.assertRequestBodyMatches("put", settings, MonitoringSettingsRequestDto.serializer())
        val schema = requestSchema("put", settings)
        assertEquals("false", schema.getValue("additionalProperties").jsonPrimitive.content)
        val bpm = schema.getValue("properties").jsonObject.getValue("max_heart_rate_alert_bpm").jsonObject
        assertEquals(ThresholdRules.MIN_BPM, bpm.getValue("minimum").jsonPrimitive.int)
        assertEquals(ThresholdRules.MAX_BPM, bpm.getValue("maximum").jsonPrimitive.int)

        val serialized = BffMobileClient.json.encodeToJsonElement(MonitoringSettingsRequestDto.serializer(), MonitoringSettingsRequestDto(150)).jsonObject
        assertEquals(schema.getValue("properties").jsonObject.keys, serialized.keys)
    }

    @Test
    fun `los modelos de respuesta corresponden al contrato`() {
        contract.assertModelMatches("MonitoringSettingsResponse", MonitoringSettingsDto.serializer())
        contract.assertModelMatches("EvaluationResponse", EvaluationDto.serializer())
        contract.assertModelMatches("RiskAlertResponse", RiskAlertDto.serializer())
        contract.assertModelMatches("MeasurementBatchResponse", MeasurementBatchResponseDto.serializer())
        contract.assertModelMatches("TrainingSessionResponse", TrainingSessionDto.serializer())
        OpenApiContract.assertSameElements("ThresholdSource", enumOf("ThresholdSource"), ThresholdSource.entries.map { it.name }.toSet())
        OpenApiContract.assertSameElements("EvaluationStatus", enumOf("EvaluationStatus"), EvaluationStatus.entries.map { it.name }.toSet())
        OpenApiContract.assertSameElements("SessionEndReason", enumOf("SessionEndReason"), SessionEndReason.entries.map { it.wire }.toSet())
        assertEquals(setOf("HIGH"), enumOf("AlertSeverity"))
        val rule = schemas.getValue("RiskAlertResponse").jsonObject.getValue("properties").jsonObject.getValue("rule").jsonObject
        assertEquals(RULE_HR_ABOVE_THRESHOLD_V1, rule.getValue("const").jsonPrimitive.content)
    }

    @Test
    fun `el fin con RISK_STOP usa el campo opcional reason del contrato`() {
        contract.assertRequestBodyMatches("post", finish, FinishTrainingSessionRequestDto.serializer())
        val schema = requestSchema("post", finish)
        assertTrue("reason" in schema.getValue("properties").jsonObject.keys)
        assertFalse("reason" in schema.getValue("required").jsonArray.map { it.jsonPrimitive.content })

        val withReason = BffMobileClient.json.encodeToJsonElement(
            FinishTrainingSessionRequestDto.serializer(),
            FinishTrainingSessionRequestDto("2026-10-24T12:40:00Z", SessionEndReason.RISK_STOP.wire),
        ).jsonObject
        assertEquals(setOf("ended_at", "reason"), withReason.keys)
        assertEquals("RISK_STOP", withReason.getValue("reason").jsonPrimitive.content)

        // Sin motivo el cuerpo de HU032 no cambia.
        val plain = BffMobileClient.json.encodeToJsonElement(FinishTrainingSessionRequestDto.serializer(), FinishTrainingSessionRequestDto("2026-10-24T12:40:00Z")).jsonObject
        assertEquals(setOf("ended_at"), plain.keys)
    }

    @Test
    fun `respuestas del contrato se deserializan con el cliente`() {
        val settingsDto = BffMobileClient.json.decodeFromString(MonitoringSettingsDto.serializer(), MonitoringFixtures.settingsBody(167, "AGE_DEFAULT"))
        assertEquals(167, settingsDto.maxHeartRateAlertBpm)
        assertNull(settingsDto.updatedAt)

        val batch = BffMobileClient.json.decodeFromString(
            MeasurementBatchResponseDto.serializer(),
            SensorsFixtures.batchBody(received = 5, accepted = 5, evaluation = MonitoringFixtures.evaluationJson("RISK", MonitoringFixtures.alertJson(1))),
        )
        assertEquals(MonitoringFixtures.alertDto(1), batch.evaluation.alerts.single())

        val finished = BffMobileClient.json.decodeFromString(
            TrainingSessionDto.serializer(),
            """{"session_id":"${SensorsFixtures.SESSION_ID}","athlete_id":"${SensorsFixtures.SESSION_ID}","plan_id":null,"sport":"running",""" +
                """"started_at":"2026-10-24T12:30:00Z","ended_at":"2026-10-24T12:40:00Z","duration_seconds":600,"status":"FINISHED",""" +
                """"version":2,"created_at":"2026-10-24T12:30:00Z","updated_at":"2026-10-24T12:40:00Z","end_reason":"RISK_STOP"}""",
        )
        assertEquals("RISK_STOP", finished.endReason)
    }

    @Test
    fun `el cliente trata las respuestas y codigos documentados`() {
        assertEquals(setOf("200", "401", "403", "422", "503", "504"), contract.responseCodes("get", settings))
        assertEquals(setOf("200", "401", "403", "422", "503", "504"), contract.responseCodes("put", settings))
        assertTrue("capability_required" in responseDescriptions("get", settings).getValue("403"))
        assertTrue("capability_required" in responseDescriptions("put", settings).getValue("403"))
        assertTrue("out_of_range" in responseDescriptions("put", settings).getValue("422"))
        assertTrue("capability_required" in responseDescriptions("post", measurements).getValue("403"))
    }

    @Test
    fun `los modelos no exponen datos en toString`() {
        assertFalse(MonitoringSettingsRequestDto(150).toString().contains("150"))
        assertFalse(MonitoringSettingsDto(150, "CUSTOM", null, "aviso").toString().contains("150"))
        assertFalse(MonitoringFixtures.alertDto(1).toString().contains(MonitoringFixtures.alertId(1)))
    }
}
