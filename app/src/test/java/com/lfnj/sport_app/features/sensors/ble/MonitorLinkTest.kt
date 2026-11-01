package com.lfnj.sport_app.features.sensors.ble

import com.lfnj.sport_app.features.sensors.model.HeartRateReading
import com.lfnj.sport_app.features.sensors.model.MonitorConnection
import com.lfnj.sport_app.features.sensors.model.MonitorSnapshot
import com.lfnj.sport_app.testing.FakeMonitorSource
import com.lfnj.sport_app.testing.SensorsFixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** HU037: conexión compartida con el monitor, detección local de desconexión y reintento. */
@OptIn(ExperimentalCoroutinesApi::class)
class MonitorLinkTest {
    private val address = SensorsFixtures.ADDRESS
    private val reading = HeartRateReading(128, Instant.parse("2026-10-24T12:30:05Z"))

    @Test
    fun `tras una desconexion espera y vuelve a conectar`() = runTest {
        val source = FakeMonitorSource()
        source.connections.addLast(
            flowOf(MonitorEvent.Connecting, MonitorEvent.Connected, MonitorEvent.Battery(80), MonitorEvent.HeartRate(reading), MonitorEvent.Disconnected)
        )
        val link = MonitorLink(source, backgroundScope, reconnectDelayMillis = 1_000)
        val state = link.observe(address)
        backgroundScope.launch { state.collect { } }
        runCurrent()

        assertEquals(MonitorSnapshot(MonitorConnection.DISCONNECTED, 80, reading), state.value)
        assertEquals(1, source.connectAddresses.size)

        advanceTimeBy(999)
        assertEquals(1, source.connectAddresses.size)

        advanceTimeBy(2)
        assertEquals(listOf(address, address), source.connectAddresses)
        assertEquals(MonitorConnection.CONNECTED, state.value.connection)
        assertEquals(80, state.value.batteryPercent)
    }

    @Test
    fun `una conexion que termina sin aviso se marca desconectada y se reintenta`() = runTest {
        val source = FakeMonitorSource()
        source.connections.addLast(flowOf(MonitorEvent.Connecting, MonitorEvent.Connected))
        val link = MonitorLink(source, backgroundScope, reconnectDelayMillis = 1_000)
        val state = link.observe(address)
        backgroundScope.launch { state.collect { } }
        runCurrent()

        assertEquals(MonitorConnection.DISCONNECTED, state.value.connection)

        advanceTimeBy(1_001)
        assertEquals(2, source.connectAddresses.size)
    }

    @Test
    fun `sin el servicio 0x180D no reintenta`() = runTest {
        val source = FakeMonitorSource()
        source.connections.addLast(flowOf(MonitorEvent.Connecting, MonitorEvent.MissingHeartRateService))
        val link = MonitorLink(source, backgroundScope, reconnectDelayMillis = 1_000)
        val state = link.observe(address)
        backgroundScope.launch { state.collect { } }
        runCurrent()

        advanceTimeBy(60_000)

        assertEquals(MonitorConnection.MISSING_HEART_RATE_SERVICE, state.value.connection)
        assertEquals(1, source.connectAddresses.size)
    }

    @Test
    fun `sin permiso Bluetooth no reintenta`() = runTest {
        val source = FakeMonitorSource()
        source.connections.addLast(flowOf(MonitorEvent.Connecting, MonitorEvent.PermissionRequired))
        val link = MonitorLink(source, backgroundScope, reconnectDelayMillis = 1_000)
        val state = link.observe(address)
        backgroundScope.launch { state.collect { } }
        runCurrent()

        advanceTimeBy(60_000)

        assertEquals(MonitorConnection.PERMISSION_REQUIRED, state.value.connection)
        assertEquals(1, source.connectAddresses.size)
    }

    @Test
    fun `la pantalla y la ingestion comparten el mismo enlace`() = runTest {
        val source = FakeMonitorSource()
        val link = MonitorLink(source, backgroundScope)
        val first = link.observe(address)

        assertSame(first, link.observe(address))
        backgroundScope.launch { first.collect { } }
        backgroundScope.launch { link.observe(address).collect { } }
        runCurrent()

        assertEquals(1, source.connectAddresses.size)
        assertEquals(MonitorConnection.CONNECTED, first.value.connection)
    }

    @Test
    fun `probe, scan y disponibilidad delegan en la fuente`() = runTest {
        val source = FakeMonitorSource(simulated = true, available = BleAvailability.BLUETOOTH_OFF)
        source.scanFlow = flowOf(SensorsFixtures.monitor())
        source.connections.addLast(flowOf(MonitorEvent.Connecting, MonitorEvent.MissingHeartRateService))
        val link = MonitorLink(source, backgroundScope)

        assertTrue(link.simulated)
        assertEquals(BleAvailability.BLUETOOTH_OFF, link.availability())
        assertEquals(listOf(SensorsFixtures.monitor()), link.scan().toList())
        assertEquals(listOf(MonitorEvent.Connecting, MonitorEvent.MissingHeartRateService), link.probe(address).toList())
        assertFalse(MonitorLink(FakeMonitorSource(simulated = false), backgroundScope).simulated)
    }

    @Test
    fun `reduce acumula el estado visible de la conexion`() {
        val initial = MonitorSnapshot(MonitorConnection.CONNECTING)

        assertEquals(MonitorConnection.CONNECTED, initial.reduce(MonitorEvent.Connected).connection)
        assertEquals(MonitorConnection.DISCONNECTED, initial.reduce(MonitorEvent.Disconnected).connection)
        assertEquals(MonitorConnection.MISSING_HEART_RATE_SERVICE, initial.reduce(MonitorEvent.MissingHeartRateService).connection)
        assertEquals(MonitorConnection.PERMISSION_REQUIRED, initial.reduce(MonitorEvent.PermissionRequired).connection)
        assertEquals(MonitorConnection.UNAVAILABLE, initial.reduce(MonitorEvent.Unavailable).connection)
        assertEquals(MonitorConnection.CONNECTING, initial.reduce(MonitorEvent.Disconnected).reduce(MonitorEvent.Connecting).connection)

        val battery = initial.reduce(MonitorEvent.Battery(64))
        assertEquals(MonitorSnapshot(MonitorConnection.CONNECTING, 64, null), battery)

        val withReading = battery.reduce(MonitorEvent.HeartRate(reading))
        assertEquals(MonitorSnapshot(MonitorConnection.CONNECTED, 64, reading), withReading)

        // La desconexión conserva la última lectura, pero ya no es una lectura actual.
        val disconnected = withReading.reduce(MonitorEvent.Disconnected)
        assertEquals(reading, disconnected.lastReading)
        assertEquals(MonitorConnection.DISCONNECTED, disconnected.connection)
        assertNull(initial.lastReading)
    }

    @Test
    fun `las constantes de GATT son las del perfil estandar`() {
        assertEquals("0000180d-0000-1000-8000-00805f9b34fb", GattUuids.HEART_RATE_SERVICE.toString())
        assertEquals("00002a37-0000-1000-8000-00805f9b34fb", GattUuids.HEART_RATE_MEASUREMENT.toString())
        assertEquals("0000180f-0000-1000-8000-00805f9b34fb", GattUuids.BATTERY_SERVICE.toString())
        assertEquals("00002a19-0000-1000-8000-00805f9b34fb", GattUuids.BATTERY_LEVEL.toString())
        assertEquals("00002902-0000-1000-8000-00805f9b34fb", GattUuids.CLIENT_CONFIG.toString())
    }
}
