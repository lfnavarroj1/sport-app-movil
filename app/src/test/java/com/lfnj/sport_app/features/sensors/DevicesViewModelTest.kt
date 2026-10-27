package com.lfnj.sport_app.features.sensors

import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.sensors.ble.BleAvailability
import com.lfnj.sport_app.features.sensors.ble.MonitorEvent
import com.lfnj.sport_app.features.sensors.ble.MonitorLink
import com.lfnj.sport_app.features.sensors.data.ConnectableDevice
import com.lfnj.sport_app.features.sensors.data.DevicesGateway
import com.lfnj.sport_app.features.sensors.data.DevicesProblem
import com.lfnj.sport_app.features.sensors.data.DevicesResult
import com.lfnj.sport_app.features.sensors.model.DeviceStatus
import com.lfnj.sport_app.features.sensors.model.DiscoveredMonitor
import com.lfnj.sport_app.features.sensors.model.IngestionState
import com.lfnj.sport_app.features.sensors.model.IngestionStatus
import com.lfnj.sport_app.features.sensors.model.LinkedDevice
import com.lfnj.sport_app.features.sensors.model.MonitorConnection
import com.lfnj.sport_app.testing.FakeConnectableDevices
import com.lfnj.sport_app.testing.FakeMonitorSource
import com.lfnj.sport_app.testing.MainDispatcherRule
import com.lfnj.sport_app.testing.SensorsFixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** HU037: estados de «Dispositivos de medición» (búsqueda, verificación `0x180D`, vincular, revocar). */
@OptIn(ExperimentalCoroutinesApi::class)
class DevicesViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val user = SensorsFixtures.user()
    private val monitor = SensorsFixtures.monitor()

    /** Vinculaciones observables y respuestas del BFF programadas por la prueba. */
    private class FakeGateway(initial: List<LinkedDevice> = emptyList()) : DevicesGateway {
        val devices = MutableStateFlow(initial)
        val refreshResponses = ArrayDeque<DevicesResult<Unit>>()
        val linkResponses = ArrayDeque<DevicesResult<LinkedDevice>>()
        val revokeResponses = ArrayDeque<DevicesResult<LinkedDevice>>()
        val linkCalls = mutableListOf<Triple<SessionUser, DiscoveredMonitor, String>>()
        val linkKeys = mutableListOf<String>()
        val revoked = mutableListOf<String>()
        var refreshCalls = 0
        private var generated = 0

        override fun observe(owner: String): Flow<List<LinkedDevice>> = devices

        override suspend fun refresh(owner: String): DevicesResult<Unit> {
            refreshCalls++
            return refreshResponses.removeFirstOrNull() ?: DevicesResult.Done(Unit)
        }

        override suspend fun link(user: SessionUser, monitor: DiscoveredMonitor, displayName: String, idempotencyKey: String): DevicesResult<LinkedDevice> {
            linkCalls += Triple(user, monitor, displayName)
            linkKeys += idempotencyKey
            val result = linkResponses.removeFirst()
            if (result is DevicesResult.Done) devices.value = listOf(result.value) + devices.value
            return result
        }

        override suspend fun revoke(owner: String, deviceId: String): DevicesResult<LinkedDevice> {
            revoked += deviceId
            return revokeResponses.removeFirst()
        }

        override fun newIdempotencyKey(): String = UUID.nameUUIDFromBytes("hu037-intento-${++generated}".toByteArray()).toString()
    }

    private val gateway = FakeGateway()
    private val connectable = FakeConnectableDevices()
    private val source = FakeMonitorSource()
    private val ingestion = MutableStateFlow(IngestionStatus())

    private fun TestScope.viewModel(): DevicesViewModel {
        val vm = DevicesViewModel(user, gateway, connectable, MonitorLink(source, backgroundScope), ingestion, probeTimeoutMillis = 1_000)
        advanceUntilIdle()
        return vm
    }

    private fun TestScope.openDraft(vm: DevicesViewModel, name: String = SensorsFixtures.DISPLAY_NAME) {
        vm.select(monitor)
        vm.onNameChange(name)
        advanceUntilIdle()
    }

    // ----------------------------------------------------------------- carga

    @Test
    fun `el estado inicial refleja la fuente y actualiza la lista con el BFF`() = runTest {
        gateway.devices.value = listOf(SensorsFixtures.linked(hasLocalAddress = false))

        val vm = viewModel()

        val state = vm.uiState.value
        assertEquals(BleAvailability.READY, state.availability)
        assertTrue(state.simulatedSource)
        assertEquals(1, gateway.refreshCalls)
        assertFalse(state.refreshing)
        assertFalse(state.loading)
        assertEquals(1, state.devices?.size)
        assertNull(state.connected)
        assertTrue(state.canSearch)
    }

    @Test
    fun `una fuente real no se declara simulada`() = runTest {
        val vm = DevicesViewModel(user, gateway, connectable, MonitorLink(FakeMonitorSource(simulated = false), backgroundScope), ingestion)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.simulatedSource)
    }

    @Test
    fun `sin red la lista explica el problema y sin sesion la marca expirada`() = runTest {
        gateway.refreshResponses.addLast(DevicesResult.Failed(DevicesProblem.OFFLINE))
        gateway.refreshResponses.addLast(DevicesResult.Failed(DevicesProblem.SIGNED_OUT))

        val vm = viewModel()
        assertEquals(ListProblem.OFFLINE, vm.uiState.value.listProblem)

        vm.refresh()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.sessionExpired)
    }

    @Test
    fun `Bluetooth apagado no permite buscar`() = runTest {
        source.available = BleAvailability.BLUETOOTH_OFF

        val vm = viewModel()
        vm.search()
        advanceUntilIdle()

        assertEquals(BleAvailability.BLUETOOTH_OFF, vm.uiState.value.availability)
        assertFalse(vm.uiState.value.canSearch)
        assertEquals(ScanState.Idle, vm.uiState.value.scan)
    }

    // ----------------------------------------------------------------- búsqueda

    @Test
    fun `una busqueda sin monitores compatibles termina vacia (CA2)`() = runTest {
        source.scanFlow = emptyFlow()
        val vm = viewModel()

        vm.search()
        advanceUntilIdle()

        assertEquals(ScanState.Finished(emptyList()), vm.uiState.value.scan)
        assertTrue(vm.uiState.value.canSearch)
    }

    @Test
    fun `la busqueda muestra los monitores encontrados sin repetir`() = runTest {
        source.scanFlow = flow {
            emit(monitor)
            emit(monitor)
            delay(5_000)
            emit(SensorsFixtures.monitor(SensorsFixtures.OTHER_ADDRESS, "Banda"))
        }
        val vm = viewModel()

        vm.search()
        runCurrent()
        assertEquals(ScanState.Scanning(listOf(monitor)), vm.uiState.value.scan)
        assertFalse(vm.uiState.value.canSearch)

        advanceUntilIdle()
        assertEquals(2, (vm.uiState.value.scan as ScanState.Finished).found.size)
    }

    @Test
    fun `detener la busqueda conserva lo encontrado`() = runTest {
        source.scanFlow = flow {
            emit(monitor)
            awaitCancellation()
        }
        val vm = viewModel()
        vm.search()
        runCurrent()

        vm.stopSearch()
        advanceUntilIdle()

        assertEquals(ScanState.Finished(listOf(monitor)), vm.uiState.value.scan)
    }

    @Test
    fun `seleccionar abre la confirmacion con el nombre sugerido y una clave nueva`() = runTest {
        val vm = viewModel()

        vm.select(monitor)

        val draft = checkNotNull(vm.uiState.value.linking)
        assertEquals(monitor, draft.monitor)
        assertEquals(SensorsFixtures.ADVERTISED_NAME, draft.name)
        assertEquals(LinkPhase.EDITING, draft.phase)
        assertNull(draft.problem)
        assertTrue(draft.idempotencyKey.isNotBlank())
        assertEquals(ScanState.Idle, vm.uiState.value.scan)
        assertFalse(vm.uiState.value.canSearch)

        vm.cancelLink()
        assertNull(vm.uiState.value.linking)
    }

    @Test
    fun `el nombre se limita a 60 caracteres al escribir`() = runTest {
        val vm = viewModel()
        vm.select(monitor)

        vm.onNameChange("x".repeat(80))

        assertEquals(60, vm.uiState.value.linking?.name?.length)
    }

    // ----------------------------------------------------------------- vincular

    @Test
    fun `un nombre vacio no se envia`() = runTest {
        val vm = viewModel()
        openDraft(vm, "   ")

        vm.confirmLink()
        advanceUntilIdle()

        assertEquals(LinkProblem.INVALID_NAME, vm.uiState.value.linking?.problem)
        assertTrue(source.connectAddresses.isEmpty())
        assertTrue(gateway.linkCalls.isEmpty())
    }

    @Test
    fun `un dispositivo sin el servicio 0x180D no se vincula (CA2)`() = runTest {
        source.connections.addLast(flowOf(MonitorEvent.Connecting, MonitorEvent.MissingHeartRateService))
        val vm = viewModel()
        openDraft(vm)

        vm.confirmLink()
        advanceUntilIdle()

        val draft = checkNotNull(vm.uiState.value.linking)
        assertEquals(LinkProblem.MISSING_HEART_RATE_SERVICE, draft.problem)
        assertEquals(LinkPhase.EDITING, draft.phase)
        assertEquals(listOf(SensorsFixtures.ADDRESS), source.connectAddresses)
        assertTrue(gateway.linkCalls.isEmpty())
    }

    @Test
    fun `sin permiso Bluetooth no se vincula (CA2)`() = runTest {
        source.connections.addLast(flowOf(MonitorEvent.PermissionRequired))
        val vm = viewModel()
        openDraft(vm)

        vm.confirmLink()
        advanceUntilIdle()

        assertEquals(LinkProblem.PERMISSION_REQUIRED, vm.uiState.value.linking?.problem)
        assertTrue(gateway.linkCalls.isEmpty())
    }

    @Test
    fun `un monitor que no responde no se vincula`() = runTest {
        source.connections.addLast(
            flow {
                emit(MonitorEvent.Connecting)
                awaitCancellation()
            }
        )
        source.connections.addLast(flowOf(MonitorEvent.Connecting, MonitorEvent.Disconnected))
        val vm = viewModel()
        openDraft(vm)

        vm.confirmLink()
        advanceUntilIdle()
        assertEquals(LinkProblem.NOT_REACHABLE, vm.uiState.value.linking?.problem)

        vm.confirmLink()
        advanceUntilIdle()
        assertEquals(LinkProblem.NOT_REACHABLE, vm.uiState.value.linking?.problem)
        assertTrue(gateway.linkCalls.isEmpty())
    }

    @Test
    fun `verificado el servicio vincula con el nombre recortado y avisa`() = runTest {
        val linked = SensorsFixtures.linked()
        gateway.linkResponses.addLast(DevicesResult.Done(linked))
        val vm = viewModel()
        openDraft(vm, "  ${SensorsFixtures.DISPLAY_NAME}  ")
        val key = checkNotNull(vm.uiState.value.linking).idempotencyKey

        vm.confirmLink()
        advanceUntilIdle()

        assertEquals(listOf(Triple(user, monitor, SensorsFixtures.DISPLAY_NAME)), gateway.linkCalls)
        assertEquals(listOf(key), gateway.linkKeys)
        assertNull(vm.uiState.value.linking)
        assertEquals(DevicesNotice.Linked(linked), vm.uiState.value.notice)
        assertEquals(linked, vm.uiState.value.connected)

        vm.onNoticeShown()
        assertNull(vm.uiState.value.notice)
    }

    @Test
    fun `idempotency_key_reused renueva la clave para el siguiente intento`() = runTest {
        gateway.linkResponses.addLast(DevicesResult.Failed(DevicesProblem.KEY_REUSED))
        val vm = viewModel()
        openDraft(vm)
        val key = checkNotNull(vm.uiState.value.linking).idempotencyKey

        vm.confirmLink()
        advanceUntilIdle()

        val draft = checkNotNull(vm.uiState.value.linking)
        assertEquals(LinkProblem.KEY_RENEWED, draft.problem)
        assertEquals(LinkPhase.EDITING, draft.phase)
        assertNotEquals(key, draft.idempotencyKey)
    }

    @Test
    fun `un resultado desconocido conserva la clave y el reintento la reutiliza`() = runTest {
        gateway.linkResponses.addLast(DevicesResult.Failed(DevicesProblem.UNAVAILABLE))
        gateway.linkResponses.addLast(DevicesResult.Done(SensorsFixtures.linked()))
        val vm = viewModel()
        openDraft(vm)
        val key = checkNotNull(vm.uiState.value.linking).idempotencyKey

        vm.confirmLink()
        advanceUntilIdle()
        assertEquals(LinkProblem.UNKNOWN_OUTCOME, vm.uiState.value.linking?.problem)
        assertEquals(key, vm.uiState.value.linking?.idempotencyKey)

        vm.confirmLink()
        advanceUntilIdle()
        assertEquals(listOf(key, key), gateway.linkKeys)
        assertNull(vm.uiState.value.linking)
    }

    @Test
    fun `los rechazos del BFF al vincular tienen su propio mensaje`() = runTest {
        val cases = listOf(
            DevicesProblem.OFFLINE to LinkProblem.OFFLINE,
            DevicesProblem.ALREADY_REGISTERED to LinkProblem.ALREADY_REGISTERED,
            DevicesProblem.CAPABILITY_REQUIRED to LinkProblem.CAPABILITY_REQUIRED,
            DevicesProblem.PERMISSION_REQUIRED to LinkProblem.CAPABILITY_REQUIRED,
            DevicesProblem.INVALID to LinkProblem.INVALID,
            DevicesProblem.NOT_FOUND to LinkProblem.INVALID,
        )
        cases.forEach { (problem, _) -> gateway.linkResponses.addLast(DevicesResult.Failed(problem)) }
        val vm = viewModel()
        openDraft(vm)

        cases.forEach { (problem, expected) ->
            vm.confirmLink()
            advanceUntilIdle()
            assertEquals(problem.name, expected, vm.uiState.value.linking?.problem)
        }
        assertTrue(gateway.devices.value.isEmpty())
    }

    @Test
    fun `una sesion expirada al vincular cierra la confirmacion`() = runTest {
        gateway.linkResponses.addLast(DevicesResult.Failed(DevicesProblem.SIGNED_OUT))
        val vm = viewModel()
        openDraft(vm)

        vm.confirmLink()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.sessionExpired)
        assertNull(vm.uiState.value.linking)
        vm.onSessionExpiredHandled()
        assertFalse(vm.uiState.value.sessionExpired)
    }

    // ----------------------------------------------------------------- revocar

    @Test
    fun `revocar confirmado por el BFF avisa al deportista`() = runTest {
        val device = SensorsFixtures.linked()
        val revoked = SensorsFixtures.linked(status = DeviceStatus.REVOKED)
        gateway.revokeResponses.addLast(DevicesResult.Done(revoked))
        val vm = viewModel()

        vm.askRevoke(device)
        assertEquals(RevokeDraft(device), vm.uiState.value.revoking)
        vm.confirmRevoke()
        advanceUntilIdle()

        assertEquals(listOf(device.deviceId), gateway.revoked)
        assertNull(vm.uiState.value.revoking)
        assertEquals(DevicesNotice.Revoked(revoked), vm.uiState.value.notice)
    }

    @Test
    fun `revocar sin red no cambia nada y lo explica`() = runTest {
        val device = SensorsFixtures.linked()
        gateway.revokeResponses.addLast(DevicesResult.Failed(DevicesProblem.OFFLINE))
        gateway.revokeResponses.addLast(DevicesResult.Failed(DevicesProblem.NOT_FOUND))
        val vm = viewModel()
        vm.askRevoke(device)

        vm.confirmRevoke()
        advanceUntilIdle()
        assertEquals(RevokeDraft(device, sending = false, problem = RevokeProblem.OFFLINE), vm.uiState.value.revoking)

        vm.confirmRevoke()
        advanceUntilIdle()
        assertEquals(RevokeProblem.NOT_FOUND, vm.uiState.value.revoking?.problem)
        assertNull(vm.uiState.value.notice)

        vm.cancelRevoke()
        assertNull(vm.uiState.value.revoking)
    }

    @Test
    fun `una sesion expirada al revocar cierra la confirmacion`() = runTest {
        gateway.revokeResponses.addLast(DevicesResult.Failed(DevicesProblem.SIGNED_OUT))
        val vm = viewModel()
        vm.askRevoke(SensorsFixtures.linked())

        vm.confirmRevoke()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.sessionExpired)
        assertNull(vm.uiState.value.revoking)
    }

    // ----------------------------------------------------------------- monitor conectado

    @Test
    fun `con un monitor conectado no se busca otro y se muestra su conexion`() = runTest {
        val device = SensorsFixtures.linked()
        gateway.devices.value = listOf(device)
        connectable.connectable.value = ConnectableDevice(device.deviceId, SensorsFixtures.ADDRESS)
        source.scanFlow = flowOf(monitor)

        val vm = viewModel()
        vm.search()
        advanceUntilIdle()
        // advanceUntilIdle no ejecuta lo pendiente solo en backgroundScope (la conexión compartida de MonitorLink).
        runCurrent()

        val state = vm.uiState.value
        assertEquals(device, state.connected)
        assertEquals(device, state.activeDevice)
        assertFalse(state.canSearch)
        assertEquals(ScanState.Idle, state.scan)
        assertEquals(MonitorConnection.CONNECTED, state.monitor?.connection)
        assertEquals(listOf(SensorsFixtures.ADDRESS), source.connectAddresses)
    }

    @Test
    fun `un activo vinculado desde otro telefono no bloquea la busqueda`() = runTest {
        gateway.devices.value = listOf(SensorsFixtures.linked(hasLocalAddress = false))

        val vm = viewModel()

        assertNull(vm.uiState.value.connected)
        assertTrue(vm.uiState.value.canSearch)
        assertNull(vm.uiState.value.monitor)
    }

    @Test
    fun `el estado de la ingestion se refleja en la pantalla`() = runTest {
        val vm = viewModel()

        ingestion.value = IngestionStatus(IngestionState.DEVICE_REVOKED, 0, null)
        advanceUntilIdle()

        assertEquals(IngestionState.DEVICE_REVOKED, vm.uiState.value.ingestion.state)
    }
}
