package com.lfnj.sport_app.features.sensors.sync

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.features.sensors.data.ConnectableDevice
import com.lfnj.sport_app.features.sensors.data.DevicesApi
import com.lfnj.sport_app.features.sensors.data.DevicesRepository
import com.lfnj.sport_app.features.sensors.data.SensorSamples
import com.lfnj.sport_app.features.sensors.data.local.SensorDeviceEntity
import com.lfnj.sport_app.features.sensors.data.local.SensorsCodec
import com.lfnj.sport_app.features.sensors.data.local.StoredDevice
import com.lfnj.sport_app.features.sensors.model.DeviceStatus
import com.lfnj.sport_app.features.sensors.model.IngestionState
import com.lfnj.sport_app.features.sensors.model.IngestionStatus
import com.lfnj.sport_app.features.training.session.foreground.ActiveSession
import com.lfnj.sport_app.testing.FakeCipher
import com.lfnj.sport_app.testing.FakeTokenStore
import com.lfnj.sport_app.testing.InMemorySensorsDao
import com.lfnj.sport_app.testing.SensorsFixtures
import com.lfnj.sport_app.testing.SessionFixtures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/**
 * HU037 CA3–CA6: lotes de mediciones contra un `bff-mobile` simulado con MockWebServer.
 * Contrato simulado según `bff-mobile` 0.21.0: **no** es un E2E.
 */
class MeasurementUploaderTest {
    private val server = MockWebServer().apply { start() }
    private val retrofit = BffMobileClient.retrofit(
        server.url("/").toString(),
        BffMobileClient.okHttp().newBuilder().retryOnConnectionFailure(false).build(),
    )
    private val sessionManager = SessionManager(retrofit.create(SessionApi::class.java), FakeTokenStore(), BffMobileClient.json, clock = { 1_000_000L })
    private val dao = InMemorySensorsDao()
    private val codec = SensorsCodec(FakeCipher(), BffMobileClient.json)
    private val api = retrofit.create(DevicesApi::class.java)
    private val devices = DevicesRepository(dao, codec, api, sessionManager, BffMobileClient.json)
    private val samples = SensorSamples(dao, codec)
    private val now = SensorsFixtures.START.plusSeconds(120)
    private val uploader = MeasurementUploader(samples, devices, api, sessionManager, BffMobileClient.json, Clock.fixed(now, ZoneOffset.UTC))
    private val owner = SensorsFixtures.OWNER
    private val sessionId = SensorsFixtures.SESSION_ID
    private val deviceId = SensorsFixtures.deviceId()
    private val session = ActiveSession(owner, sessionId, SensorsFixtures.START, monotonicStartMillis = null)
    private val device = ConnectableDevice(deviceId, SensorsFixtures.ADDRESS)
    private val path = "/v1/training-sessions/$sessionId/measurements"

    @After
    fun tearDown() = server.shutdown()

    private suspend fun login() {
        server.enqueue(SessionFixtures.ok(SessionFixtures.sessionBody()))
        sessionManager.login("ana@example.com", "Secreta-123")
        server.takeRequest()
    }

    private suspend fun linkedDevice() {
        val stored = StoredDevice(deviceId, SensorsFixtures.DISPLAY_NAME, DeviceStatus.ACTIVE, 1, Instant.EPOCH, Instant.EPOCH, null, SensorsFixtures.ADDRESS)
        dao.upsertDevice(SensorDeviceEntity(owner, deviceId, codec.encodeDevice(stored)))
    }

    /** Muestras de 1 Hz a partir del segundo 1 de la sesión, con bpm crecientes desde 100. */
    private suspend fun record(count: Int, fromSecond: Long = 1) = repeat(count) { i ->
        samples.record(owner, sessionId, deviceId, SensorsFixtures.START.epochSecond + fromSecond + i, 100 + i)
    }

    private fun takeRequest(): RecordedRequest = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS)) { "No llegó la solicitud esperada" }

    private fun respond(body: String, status: Int = 200) = server.enqueue(MockResponse().setResponseCode(status).setBody(body))

    private fun disconnect() = server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

    private fun RecordedRequest.json(): JsonObject = Json.parseToJsonElement(body.readUtf8()).jsonObject

    private fun JsonObject.measuredAt(): List<String> =
        getValue("samples").jsonArray.map { it.jsonObject.getValue("measured_at").jsonPrimitive.content }

    private suspend fun pending() = samples.count(owner, sessionId)

    // ----------------------------------------------------------------- envío

    @Test
    fun `envia las 60 muestras mas antiguas en el formato del contrato`() = runTest {
        login()
        record(65)
        respond(SensorsFixtures.batchBody(received = 60, accepted = 60))

        assertEquals(FlushResult.CONFIRMED, uploader.flush(session, device))

        val request = takeRequest()
        assertEquals("POST", request.method)
        assertEquals(path, request.requestUrl?.encodedPath)
        assertEquals("Bearer ${SessionFixtures.ACCESS_TOKEN}", request.getHeader("Authorization"))
        val body = request.json()
        assertEquals(setOf("device_id", "samples"), body.keys)
        assertEquals(deviceId, body.getValue("device_id").jsonPrimitive.content)
        val sent = body.getValue("samples").jsonArray.map { it.jsonObject }
        assertEquals(60, sent.size)
        assertEquals("2026-10-24T12:30:01Z", sent.first().getValue("measured_at").jsonPrimitive.content)
        assertEquals("2026-10-24T12:31:00Z", sent.last().getValue("measured_at").jsonPrimitive.content)
        sent.forEachIndexed { i, sample ->
            assertEquals(setOf("measured_at", "type", "value", "unit"), sample.keys)
            assertTrue(Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$").matches(sample.getValue("measured_at").jsonPrimitive.content))
            assertEquals("HEART_RATE", sample.getValue("type").jsonPrimitive.content)
            assertEquals("bpm", sample.getValue("unit").jsonPrimitive.content)
            assertEquals(100 + i, sample.getValue("value").jsonPrimitive.int)
        }
        assertEquals(5, pending())
    }

    @Test
    fun `una respuesta 200 borra las enviadas y publica aceptadas, duplicadas y rechazadas`() = runTest {
        login()
        record(3)
        respond(SensorsFixtures.batchBody(received = 3, accepted = 1, duplicates = 1, rejected = listOf(2 to "out_of_range")))

        assertEquals(FlushResult.CONFIRMED, uploader.flush(session, device))

        assertEquals(0, pending())
        val status = uploader.status.value
        assertEquals(IngestionState.SENDING, status.state)
        assertEquals(0, status.pendingSamples)
        val report = checkNotNull(status.lastBatch)
        assertEquals(now, report.sentAt)
        assertEquals(3, report.received)
        assertEquals(1, report.accepted)
        assertEquals(1, report.duplicates)
        assertEquals(1, report.rejected)
    }

    @Test
    fun `sin pendientes no envia nada`() = runTest {
        login()

        assertEquals(FlushResult.NOTHING_PENDING, uploader.flush(session, device))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `sin red conserva el lote y lo reenvia igual en el ciclo siguiente (CA5)`() = runTest {
        login()
        record(3)
        disconnect()

        assertEquals(FlushResult.RETRY_NEXT_CYCLE, uploader.flush(session, device))

        val first = takeRequest().json()
        assertEquals(3, pending())
        assertEquals(IngestionStatus(IngestionState.PENDING_RETRY, 3, null), uploader.status.value)

        respond(SensorsFixtures.batchBody(received = 3, accepted = 1, duplicates = 2))
        assertEquals(FlushResult.CONFIRMED, uploader.flush(session, device))

        val second = takeRequest().json()
        assertEquals(first.measuredAt(), second.measuredAt())
        assertEquals(first, second)
        assertEquals(0, pending())
        assertEquals(2, checkNotNull(uploader.status.value.lastBatch).duplicates)
        assertEquals(IngestionState.SENDING, uploader.status.value.state)
    }

    @Test
    fun `servicio no disponible conserva las muestras y reintenta`() = runTest {
        login()
        record(2)
        server.enqueue(SessionFixtures.error(503, "upstream_unavailable"))
        server.enqueue(SessionFixtures.error(504, "upstream_timeout"))

        assertEquals(FlushResult.RETRY_NEXT_CYCLE, uploader.flush(session, device))
        assertEquals(FlushResult.RETRY_NEXT_CYCLE, uploader.flush(session, device))
        assertEquals(2, pending())
        assertEquals(IngestionState.PENDING_RETRY, uploader.status.value.state)
    }

    @Test
    fun `sin BFF configurado conserva las muestras y reintenta`() = runTest {
        record(2)
        val unconfigured = MeasurementUploader(samples, devices, null, sessionManager, BffMobileClient.json, Clock.fixed(now, ZoneOffset.UTC))

        assertEquals(FlushResult.RETRY_NEXT_CYCLE, unconfigured.flush(session, device))
        assertEquals(2, pending())
    }

    // ----------------------------------------------------------------- rechazos

    @Test
    fun `409 device_revoked marca el dispositivo revocado y detiene el envio (CA6)`() = runTest {
        login()
        linkedDevice()
        record(3)
        server.enqueue(SessionFixtures.error(409, "device_revoked"))

        assertEquals(FlushResult.STOP, uploader.flush(session, device))

        assertEquals(IngestionState.DEVICE_REVOKED, uploader.status.value.state)
        assertEquals(DeviceStatus.REVOKED, devices.observe(owner).first().single().status)
        assertNull(devices.observeConnectable(owner).first())
        assertEquals(0, pending())
    }

    @Test
    fun `409 training_session_already_finished descarta las pendientes y detiene`() = runTest {
        login()
        record(3)
        server.enqueue(SessionFixtures.error(409, "training_session_already_finished"))

        assertEquals(FlushResult.STOP, uploader.flush(session, device))
        assertEquals(IngestionState.SESSION_FINISHED, uploader.status.value.state)
        assertEquals(0, pending())
    }

    @Test
    fun `404 training_session_not_found conserva y reintenta mientras el inicio sigue en cola`() = runTest {
        login()
        record(3)
        server.enqueue(SessionFixtures.error(404, "training_session_not_found"))

        assertEquals(FlushResult.RETRY_NEXT_CYCLE, uploader.flush(session, device))
        assertEquals(IngestionState.PENDING_RETRY, uploader.status.value.state)
        assertEquals(3, pending())
    }

    @Test
    fun `404 device_not_found descarta y detiene`() = runTest {
        login()
        record(3)
        server.enqueue(SessionFixtures.error(404, "device_not_found"))

        assertEquals(FlushResult.STOP, uploader.flush(session, device))
        assertEquals(IngestionState.REJECTED, uploader.status.value.state)
        assertEquals(0, pending())
    }

    @Test
    fun `403 capability_required detiene el envio`() = runTest {
        login()
        record(3)
        server.enqueue(SessionFixtures.error(403, "capability_required"))

        assertEquals(FlushResult.STOP, uploader.flush(session, device))
        assertEquals(IngestionState.CAPABILITY_REQUIRED, uploader.status.value.state)
        assertEquals(0, pending())
    }

    @Test
    fun `422 descarta solo el lote que el backend no puede validar`() = runTest {
        login()
        record(65)
        server.enqueue(SessionFixtures.error(422, "validation_error"))

        assertEquals(FlushResult.CONFIRMED, uploader.flush(session, device))

        assertEquals(IngestionState.REJECTED, uploader.status.value.state)
        assertEquals(5, pending())
        assertEquals(5, uploader.status.value.pendingSamples)
    }

    @Test
    fun `una detencion se conserva al reiniciar sin monitor y se limpia al vencer la sesion`() = runTest {
        login()
        record(1)
        server.enqueue(SessionFixtures.error(409, "training_session_already_finished"))
        uploader.flush(session, device)

        uploader.reset()
        assertEquals(IngestionState.SESSION_FINISHED, uploader.status.value.state)

        uploader.expire(session)
        assertEquals(IngestionStatus(), uploader.status.value)
    }

    // ----------------------------------------------------------------- vencimiento

    @Test
    fun `las muestras con mas de 15 minutos vencen y no se envian`() = runTest {
        login()
        samples.record(owner, sessionId, deviceId, now.minusSeconds(16 * 60).epochSecond, 110)
        samples.record(owner, sessionId, deviceId, now.minusSeconds(60).epochSecond, 120)
        respond(SensorsFixtures.batchBody(received = 1, accepted = 1))

        assertEquals(FlushResult.CONFIRMED, uploader.flush(session, device))

        val sent = takeRequest().json().measuredAt()
        assertEquals(listOf("2026-10-24T12:31:00Z"), sent)
        assertEquals(0, pending())
    }

    @Test
    fun `expire descarta lo pendiente de la sesion y deja el estado inicial`() = runTest {
        login()
        record(4)
        disconnect()
        uploader.flush(session, device)

        uploader.expire(session)

        assertEquals(0, pending())
        assertEquals(IngestionStatus(), uploader.status.value)
    }
}
