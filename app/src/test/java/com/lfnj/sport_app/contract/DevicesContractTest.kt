package com.lfnj.sport_app.contract

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.features.sensors.data.DeviceDto
import com.lfnj.sport_app.features.sensors.data.DevicesApi
import com.lfnj.sport_app.features.sensors.data.DevicesResponseDto
import com.lfnj.sport_app.features.sensors.data.EvaluationDto
import com.lfnj.sport_app.features.sensors.data.MeasurementBatchRequestDto
import com.lfnj.sport_app.features.sensors.data.MeasurementBatchResponseDto
import com.lfnj.sport_app.features.sensors.data.MeasurementSampleDto
import com.lfnj.sport_app.features.sensors.data.RegisterDeviceRequestDto
import com.lfnj.sport_app.features.sensors.data.RejectedSampleDto
import com.lfnj.sport_app.features.sensors.data.toRemote
import com.lfnj.sport_app.features.sensors.model.DEVICE_TYPE_HEART_RATE_MONITOR
import com.lfnj.sport_app.features.sensors.model.DeviceStatus
import com.lfnj.sport_app.features.sensors.model.PROTOCOL_BLE_HRS
import com.lfnj.sport_app.testing.SensorsFixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.DELETE
import retrofit2.http.Header

/** HU037 en el cliente móvil frente a la copia exacta de `bff-mobile` 0.21.0 (ADR-044). */
class DevicesContractTest {
    private val contract = OpenApiContract.load()
    private val devices = "/v1/me/devices"
    private val device = "/v1/me/devices/{device_id}"
    private val measurements = "/v1/training-sessions/{session_id}/measurements"

    /** Lectura directa de la copia para los cuerpos en línea, `const` y descripciones de respuestas. */
    private val document by lazy {
        val text = checkNotNull(javaClass.getResourceAsStream(OpenApiContract.RESOURCE)).bufferedReader(Charsets.UTF_8).use { it.readText() }
        Json.parseToJsonElement(text).jsonObject
    }

    private fun requestSchema(method: String, path: String): JsonObject =
        document.getValue("paths").jsonObject.getValue(path).jsonObject.getValue(method).jsonObject
            .getValue("requestBody").jsonObject.getValue("content").jsonObject
            .getValue("application/json").jsonObject.getValue("schema").jsonObject

    private fun JsonObject.properties(): JsonObject = getValue("properties").jsonObject

    private fun JsonObject.required(): Set<String> = getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet()

    private fun JsonObject.constOf(property: String): String = properties().getValue(property).jsonObject.getValue("const").jsonPrimitive.content

    private fun responseDescriptions(method: String, path: String): Map<String, String> =
        document.getValue("paths").jsonObject.getValue(path).jsonObject.getValue(method).jsonObject.getValue("responses").jsonObject
            .mapValues { (_, response) -> response.jsonObject.getValue("description").jsonPrimitive.content }

    @Test
    fun `las operaciones de HU037 existen con sus parametros`() {
        contract.assertApiMatches(DevicesApi::class.java)
        val delete = DevicesApi::class.java.declaredMethods.mapNotNull { it.getAnnotation(DELETE::class.java)?.value }
        assertEquals(listOf("v1/me/devices/{device_id}"), delete)
        assertTrue(contract.hasOperation("get", devices))
        assertTrue(contract.hasOperation("post", devices))
        assertTrue(contract.hasOperation("delete", device))
        assertTrue(contract.hasOperation("post", measurements))

        assertTrue("idempotency-key" in contract.parameters("post", devices, "header"))
        assertEquals(setOf("device_id"), contract.parameters("delete", device, "path"))
        assertEquals(setOf("session_id"), contract.parameters("post", measurements, "path"))
        listOf("get" to devices, "post" to devices, "delete" to device, "post" to measurements).forEach { (method, path) ->
            assertTrue("$method $path", "authorization" in contract.parameters(method, path, "header"))
        }
    }

    @Test
    fun `los encabezados que envia el cliente son los del contrato`() {
        val headers = DevicesApi::class.java.declaredMethods
            .flatMap { method -> method.parameterAnnotations.flatMap { it.toList() } }
            .filterIsInstance<Header>()
            .map { it.value.lowercase() }
            .toSet()

        assertEquals(setOf("authorization", "idempotency-key"), headers)
    }

    @Test
    fun `los modelos de respuesta corresponden al contrato`() {
        contract.assertModelMatches("DeviceResponse", DeviceDto.serializer())
        contract.assertModelMatches("DevicesResponse", DevicesResponseDto.serializer())
        contract.assertModelMatches("MeasurementBatchResponse", MeasurementBatchResponseDto.serializer())
        contract.assertModelMatches("RejectedSample", RejectedSampleDto.serializer())
        contract.assertModelMatches("EvaluationResponse", EvaluationDto.serializer())
        OpenApiContract.assertSameElements(
            "DeviceResponse.status",
            contract.enumValues("DeviceResponse", "status"),
            DeviceStatus.entries.map { it.wire }.toSet(),
        )
    }

    @Test
    fun `el cuerpo de vinculacion solo usa propiedades del esquema cerrado`() {
        contract.assertRequestBodyMatches("post", devices, RegisterDeviceRequestDto.serializer())
        val schema = requestSchema("post", devices)
        assertEquals("false", schema.getValue("additionalProperties").jsonPrimitive.content)
        assertEquals(DEVICE_TYPE_HEART_RATE_MONITOR, schema.constOf("device_type"))
        assertEquals(PROTOCOL_BLE_HRS, schema.constOf("protocol"))
        assertEquals(60, schema.properties().getValue("display_name").jsonObject.getValue("maxLength").jsonPrimitive.int)

        val body = RegisterDeviceRequestDto(SensorsFixtures.deviceId(), SensorsFixtures.DISPLAY_NAME, DEVICE_TYPE_HEART_RATE_MONITOR, PROTOCOL_BLE_HRS)
        val serialized = BffMobileClient.json.encodeToJsonElement(RegisterDeviceRequestDto.serializer(), body).jsonObject

        assertEquals(schema.properties().keys, serialized.keys)
        assertTrue(serialized.keys.containsAll(schema.required()))
        val pattern = Regex(schema.properties().getValue("device_id").jsonObject.getValue("pattern").jsonPrimitive.content)
        assertTrue(pattern.matches(serialized.getValue("device_id").jsonPrimitive.content))
    }

    @Test
    fun `el lote de mediciones solo usa propiedades del esquema cerrado`() {
        contract.assertRequestBodyMatches("post", measurements, MeasurementBatchRequestDto.serializer())
        val schema = requestSchema("post", measurements)
        val sampleSchema = schema.properties().getValue("samples").jsonObject.getValue("items").jsonObject
        assertEquals("false", schema.getValue("additionalProperties").jsonPrimitive.content)
        assertEquals("false", sampleSchema.getValue("additionalProperties").jsonPrimitive.content)
        assertEquals(DevicesApi.MAX_SAMPLES_PER_BATCH, schema.properties().getValue("samples").jsonObject.getValue("maxItems").jsonPrimitive.int)
        assertEquals(DevicesApi.SAMPLE_TYPE_HEART_RATE, sampleSchema.constOf("type"))
        assertEquals(DevicesApi.SAMPLE_UNIT_BPM, sampleSchema.constOf("unit"))

        val body = MeasurementBatchRequestDto(
            SensorsFixtures.deviceId(),
            listOf(MeasurementSampleDto("2026-10-24T12:30:05Z", DevicesApi.SAMPLE_TYPE_HEART_RATE, 128, DevicesApi.SAMPLE_UNIT_BPM)),
        )
        val serialized = BffMobileClient.json.encodeToJsonElement(MeasurementBatchRequestDto.serializer(), body).jsonObject

        assertEquals(schema.properties().keys, serialized.keys)
        assertTrue(serialized.keys.containsAll(schema.required()))
        val sample = serialized.getValue("samples").jsonArray.single().jsonObject
        assertEquals(sampleSchema.properties().keys, sample.keys)
        assertTrue(sample.keys.containsAll(sampleSchema.required()))
    }

    @Test
    fun `una respuesta del contrato se deserializa con el cliente`() {
        val dto = BffMobileClient.json.decodeFromString(DevicesResponseDto.serializer(), SensorsFixtures.devicesBody(SensorsFixtures.deviceBody()))

        val remote = dto.items.single().toRemote()
        assertEquals(SensorsFixtures.deviceId(), remote.deviceId)
        assertEquals(DeviceStatus.ACTIVE, remote.status)

        val revoked = BffMobileClient.json.decodeFromString(
            DeviceDto.serializer(),
            SensorsFixtures.deviceBody(status = "REVOKED", version = 2, revokedAt = "2026-10-24T13:00:00Z"),
        ).toRemote()
        assertEquals(DeviceStatus.REVOKED, revoked.status)
    }

    @Test
    fun `un lote confirmado con alertas de HU036 se deserializa con el cliente`() {
        val body = """{"session_id":"${SensorsFixtures.SESSION_ID}","device_id":"${SensorsFixtures.deviceId()}","received":3,"accepted":1,""" +
            """"duplicates":1,"rejected":[{"index":2,"issue":"too_old"}],"evaluation":{"status":"RISK","alerts":[""" +
            """{"alert_id":"${SensorsFixtures.SESSION_ID}","rule":"HR_ABOVE_THRESHOLD_V1","severity":"HIGH","message":"mensaje",""" +
            """"instruction":"instruccion","detected_at":"2026-10-24T12:30:05Z"}]}}"""

        val dto = BffMobileClient.json.decodeFromString(MeasurementBatchResponseDto.serializer(), body)

        assertEquals(3, dto.received)
        assertEquals(1, dto.duplicates)
        assertEquals(listOf(RejectedSampleDto(2, "too_old")), dto.rejected)
        assertEquals(1, dto.evaluation.alerts.size)
        assertTrue("too_old" in document.getValue("components").jsonObject.getValue("schemas").jsonObject
            .getValue("SampleIssue").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `el cliente trata todas las respuestas documentadas`() {
        assertEquals(setOf("200", "401", "403", "422", "503", "504"), contract.responseCodes("get", devices))
        assertEquals(setOf("200", "201", "401", "403", "409", "422", "503", "504"), contract.responseCodes("post", devices))
        assertEquals(setOf("200", "401", "403", "404", "422", "503", "504"), contract.responseCodes("delete", device))
        assertEquals(setOf("200", "401", "403", "404", "409", "422", "503", "504"), contract.responseCodes("post", measurements))
    }

    @Test
    fun `los codigos de error que distingue el cliente estan documentados`() {
        val post = responseDescriptions("post", devices)
        listOf("device_already_registered", "idempotency_key_reused").forEach { assertTrue(it, it in post.getValue("409")) }
        assertTrue("capability_required" in post.getValue("403"))
        assertTrue("device_not_found" in responseDescriptions("delete", device).getValue("404"))
        val ingest = responseDescriptions("post", measurements)
        listOf("device_revoked", "training_session_already_finished").forEach { assertTrue(it, it in ingest.getValue("409")) }
        listOf("training_session_not_found", "device_not_found").forEach { assertTrue(it, it in ingest.getValue("404")) }
        assertTrue("capability_required" in ingest.getValue("403"))
    }

    @Test
    fun `los modelos no exponen datos en toString`() {
        val body = RegisterDeviceRequestDto(SensorsFixtures.deviceId(), SensorsFixtures.DISPLAY_NAME, DEVICE_TYPE_HEART_RATE_MONITOR, PROTOCOL_BLE_HRS)

        assertFalse(body.toString().contains(SensorsFixtures.deviceId()))
        assertFalse(body.toString().contains(SensorsFixtures.DISPLAY_NAME))
        assertFalse(MeasurementSampleDto("2026-10-24T12:30:05Z", "HEART_RATE", 128, "bpm").toString().contains("128"))
    }
}
