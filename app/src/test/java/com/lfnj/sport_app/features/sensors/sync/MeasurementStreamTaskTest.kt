package com.lfnj.sport_app.features.sensors.sync

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.features.sensors.data.ConnectableDevice
import com.lfnj.sport_app.features.sensors.data.SensorSamples
import com.lfnj.sport_app.features.sensors.data.local.SensorsCodec
import com.lfnj.sport_app.features.sensors.model.HeartRateReading
import com.lfnj.sport_app.features.sensors.model.IngestionState
import com.lfnj.sport_app.features.sensors.model.MonitorConnection
import com.lfnj.sport_app.features.sensors.model.MonitorSnapshot
import com.lfnj.sport_app.features.training.session.foreground.ActiveSession
import com.lfnj.sport_app.testing.FakeCipher
import com.lfnj.sport_app.testing.FakeConnectableDevices
import com.lfnj.sport_app.testing.FakeDevicesApi
import com.lfnj.sport_app.testing.FakeTokenStore
import com.lfnj.sport_app.testing.InMemorySensorsDao
import com.lfnj.sport_app.testing.SensorsFixtures
import com.lfnj.sport_app.testing.SessionFixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.ZoneOffset

/**
 * HU037: captura de 1 Hz y lotes cada 5 s durante la sesión activa, con tiempo virtual. El BFF es
 * una API en memoria (MockWebServer solo atiende el inicio de sesión): **no** es un E2E.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeasurementStreamTaskTest {
    private val server = MockWebServer().apply { start() }
    private val retrofit = BffMobileClient.retrofit(server.url("/").toString(), BffMobileClient.okHttp())
    private val sessionManager = SessionManager(retrofit.create(SessionApi::class.java), FakeTokenStore(), BffMobileClient.json, clock = { 1_000_000L })
    private val dao = InMemorySensorsDao()
    private val samples = SensorSamples(dao, SensorsCodec(FakeCipher(), BffMobileClient.json))
    private val api = FakeDevicesApi()
    private val start = SensorsFixtures.START
    private val device = ConnectableDevice(SensorsFixtures.deviceId(), SensorsFixtures.ADDRESS)
    private val devices = FakeConnectableDevices(device)
    private val monitor = MutableStateFlow(MonitorSnapshot(MonitorConnection.CONNECTING))
    private val linkedAddresses = mutableListOf<String>()
    private val uploader = MeasurementUploader(samples, devices, api, sessionManager, BffMobileClient.json, Clock.fixed(start.plusSeconds(30), ZoneOffset.UTC))
    private val task = MeasurementStreamTask(
        devices = devices,
        link = { address -> linkedAddresses += address; monitor },
        samples = samples,
        uploader = uploader,
    )
    private val session = ActiveSession(SensorsFixtures.OWNER, SensorsFixtures.SESSION_ID, start, monotonicStartMillis = null)

    @After
    fun tearDown() = server.shutdown()

    private suspend fun login() {
        server.enqueue(SessionFixtures.ok(SessionFixtures.sessionBody()))
        sessionManager.login("ana@example.com", "Secreta-123")
        server.takeRequest()
    }

    private fun TestScope.read(connection: MonitorConnection, bpm: Int, afterMillis: Long) {
        monitor.value = monitor.value.copy(connection = connection, lastReading = HeartRateReading(bpm, start.plusMillis(afterMillis)))
        runCurrent()
    }

    private fun sentValues(): List<List<Int>> = api.ingested.map { batch -> batch.samples.map { it.value } }

    @Test
    fun `registra una muestra por segundo solo mientras el monitor esta conectado`() = runTest {
        login()
        backgroundScope.launch { task.run(session) }
        runCurrent()

        read(MonitorConnection.CONNECTED, 120, 1_000)
        read(MonitorConnection.CONNECTED, 125, 1_500) // mismo segundo: se conserva la primera lectura
        read(MonitorConnection.DISCONNECTED, 130, 2_000) // desconectado: no es una lectura actual
        read(MonitorConnection.CONNECTING, 131, 2_500)
        read(MonitorConnection.CONNECTED, 135, 3_000)

        assertEquals(listOf(SensorsFixtures.ADDRESS), linkedAddresses)
        assertEquals(2, samples.count(SensorsFixtures.OWNER, SensorsFixtures.SESSION_ID))
    }

    @Test
    fun `ignora las lecturas anteriores al inicio de la sesion`() = runTest {
        login()
        backgroundScope.launch { task.run(session) }
        runCurrent()

        read(MonitorConnection.CONNECTED, 140, -10_000)
        read(MonitorConnection.CONNECTED, 141, -1)

        assertEquals(0, samples.count(SensorsFixtures.OWNER, SensorsFixtures.SESSION_ID))
    }

    @Test
    fun `envia un lote cada 5 segundos con las muestras del intervalo`() = runTest {
        login()
        backgroundScope.launch { task.run(session) }
        runCurrent()
        read(MonitorConnection.CONNECTED, 120, 1_000)
        read(MonitorConnection.CONNECTED, 135, 3_000)

        advanceTimeBy(4_999)
        assertTrue(api.ingested.isEmpty())

        advanceTimeBy(2)
        assertEquals(listOf(listOf(120, 135)), sentValues())
        assertEquals(device.deviceId, api.ingested.single().deviceId)
        assertEquals(0, samples.count(SensorsFixtures.OWNER, SensorsFixtures.SESSION_ID))
        assertEquals(IngestionState.SENDING, uploader.status.value.state)

        read(MonitorConnection.CONNECTED, 140, 6_000)
        advanceTimeBy(5_000)
        assertEquals(listOf(listOf(120, 135), listOf(140)), sentValues())
    }

    @Test
    fun `sin dispositivo conectable no captura ni envia`() = runTest {
        login()
        devices.connectable.value = null
        backgroundScope.launch { task.run(session) }
        runCurrent()
        read(MonitorConnection.CONNECTED, 120, 1_000)

        advanceTimeBy(20_000)

        assertTrue(linkedAddresses.isEmpty())
        assertTrue(api.ingested.isEmpty())
        assertEquals(0, samples.count(SensorsFixtures.OWNER, SensorsFixtures.SESSION_ID))
        assertEquals(IngestionState.IDLE, uploader.status.value.state)
    }

    @Test
    fun `tras 409 device_revoked deja de enviar y de capturar`() = runTest {
        login()
        api.ingestResponses.addLast(SensorsFixtures.error(409, "device_revoked"))
        backgroundScope.launch { task.run(session) }
        runCurrent()
        read(MonitorConnection.CONNECTED, 120, 1_000)

        advanceTimeBy(5_001)
        assertEquals(listOf(SensorsFixtures.OWNER to device.deviceId), devices.revoked)
        assertEquals(IngestionState.DEVICE_REVOKED, uploader.status.value.state)

        read(MonitorConnection.CONNECTED, 125, 7_000)
        advanceTimeBy(20_000)
        assertEquals(1, api.ingested.size)
        assertEquals(IngestionState.DEVICE_REVOKED, uploader.status.value.state)
    }

    @Test
    fun `al terminar la sesion descarta las muestras pendientes`() = runTest {
        login()
        val job = backgroundScope.launch { task.run(session) }
        runCurrent()
        read(MonitorConnection.CONNECTED, 120, 1_000)
        read(MonitorConnection.CONNECTED, 121, 2_000)
        assertEquals(2, samples.count(SensorsFixtures.OWNER, SensorsFixtures.SESSION_ID))

        job.cancelAndJoin()

        assertEquals(0, samples.count(SensorsFixtures.OWNER, SensorsFixtures.SESSION_ID))
        assertTrue(api.ingested.isEmpty())
        assertEquals(IngestionState.IDLE, uploader.status.value.state)
    }

    @Test
    fun `al empezar descarta las muestras de sesiones anteriores`() = runTest {
        login()
        samples.record(SensorsFixtures.OWNER, "sesion-anterior", device.deviceId, start.epochSecond - 60, 99)
        backgroundScope.launch { task.run(session) }
        runCurrent()

        assertEquals(0, samples.count(SensorsFixtures.OWNER, "sesion-anterior"))
    }
}
