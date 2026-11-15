package com.lfnj.sport_app.features.training.monitoring

import com.lfnj.sport_app.features.notifications.model.SystemNotificationsStatus
import com.lfnj.sport_app.features.sensors.data.ConnectableDevice
import com.lfnj.sport_app.features.sensors.model.HeartRateReading
import com.lfnj.sport_app.features.sensors.model.IngestionState
import com.lfnj.sport_app.features.sensors.model.IngestionStatus
import com.lfnj.sport_app.features.sensors.model.MonitorConnection
import com.lfnj.sport_app.features.sensors.model.MonitorSnapshot
import com.lfnj.sport_app.features.training.monitoring.data.SettingsProblem
import com.lfnj.sport_app.features.training.monitoring.data.SettingsResult
import com.lfnj.sport_app.features.training.monitoring.model.LiveReading
import com.lfnj.sport_app.features.training.monitoring.model.ThresholdSource
import com.lfnj.sport_app.features.training.session.data.FinishResult
import com.lfnj.sport_app.features.training.session.foreground.ActiveSession
import com.lfnj.sport_app.testing.FakeConnectableDevices
import com.lfnj.sport_app.testing.FakeConnectivity
import com.lfnj.sport_app.testing.FakeMonitoringGateway
import com.lfnj.sport_app.testing.FakePracticeStopper
import com.lfnj.sport_app.testing.FakeRiskAlertNotifier
import com.lfnj.sport_app.testing.MainDispatcherRule
import com.lfnj.sport_app.testing.MonitoringFixtures
import com.lfnj.sport_app.testing.SensorsFixtures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** HU036 CA1–CA5 en el ViewModel de «Monitoreo en tiempo real». */
@OptIn(ExperimentalCoroutinesApi::class)
class MonitoringViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    /** Reloj de pared controlado por la prueba. */
    private class MutableClock(var instant: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = instant
    }

    private val start = SensorsFixtures.START
    private val clock = MutableClock(start.plusSeconds(60))
    private val owner = SensorsFixtures.OWNER
    private val session = ActiveSession(owner, SensorsFixtures.SESSION_ID, start, null)
    private val active = MutableStateFlow<ActiveSession?>(session)
    private val device = ConnectableDevice(SensorsFixtures.deviceId(), SensorsFixtures.ADDRESS)
    private val connectable = FakeConnectableDevices(device)
    private val monitor = MutableStateFlow(MonitorSnapshot(MonitorConnection.CONNECTING))
    private val observed = mutableListOf<String>()
    private val ingestion = MutableStateFlow(IngestionStatus())
    private val notifier = FakeRiskAlertNotifier()
    private val risk = RiskMonitor(notifier)
    private val connectivity = FakeConnectivity()
    private val gateway = FakeMonitoringGateway()
    private val stopper = FakePracticeStopper()
    private val ticks = MutableStateFlow(clock.instant)

    private fun TestScope.create(canUse: Boolean = true): MonitoringViewModel {
        val vm = MonitoringViewModel(
            owner = owner,
            canUse = canUse,
            active = active,
            connectable = connectable,
            observeMonitor = { address -> observed += address; monitor as StateFlow<MonitorSnapshot> },
            simulatedSource = true,
            ingestion = ingestion,
            risk = risk.state,
            connectivity = connectivity,
            gateway = gateway,
            stopper = stopper,
            notifier = notifier,
            clock = clock,
            ticks = ticks,
        )
        runCurrent()
        return vm
    }

    private fun reading(bpm: Int, at: Instant) {
        monitor.value = MonitorSnapshot(MonitorConnection.CONNECTED, batteryPercent = 80, lastReading = HeartRateReading(bpm, at))
    }

    /** Avanza el reloj de pared y el temporizador de la pantalla. */
    private fun TestScope.tick(seconds: Long) {
        clock.instant = clock.instant.plusSeconds(seconds)
        ticks.value = clock.instant
        runCurrent()
    }

    private val MonitoringViewModel.state get() = uiState.value

    // ----------------------------------------------------------------- CA1

    @Test
    fun `CA1 muestra el valor mas reciente con su instante y la conexion del monitor`() = runTest(main.dispatcher) {
        val vm = create()
        assertEquals(LiveReading.Waiting, vm.state.live)
        assertTrue(vm.state.simulatedSource)

        reading(138, clock.instant.minusSeconds(2))
        runCurrent()

        assertEquals(listOf(SensorsFixtures.ADDRESS), observed)
        assertEquals(true, vm.state.hasDevice)
        assertEquals(LiveReading.Live(138, clock.instant.minusSeconds(2), 2), vm.state.live)
        assertEquals(MonitorConnection.CONNECTED, vm.state.monitor?.connection)
        assertEquals(80, vm.state.monitor?.batteryPercent)
        assertEquals(RemoteStatus.WAITING, vm.state.remote)

        tick(3)
        assertEquals(5L, (vm.state.live as LiveReading.Live).ageSeconds)
    }

    // ----------------------------------------------------------------- CA2

    @Test
    fun `CA2 la alerta del backend se muestra con su instruccion y la evaluacion RISK`() = runTest(main.dispatcher) {
        val vm = create()
        reading(181, clock.instant)

        risk.onEvaluation(session, MonitoringFixtures.evaluation("RISK", MonitoringFixtures.alertDto(1)), clock.instant)
        runCurrent()

        assertEquals(RemoteStatus.RISK, vm.state.remote)
        val alert = vm.state.alerts.single()
        assertEquals(MonitoringFixtures.MESSAGE, alert.message)
        assertEquals(MonitoringFixtures.INSTRUCTION, alert.instruction)
        assertEquals(1, notifier.notified.size)
    }

    @Test
    fun `las alertas de otra sesion no se muestran`() = runTest(main.dispatcher) {
        val other = session.copy(sessionId = java.util.UUID.nameUUIDFromBytes("hu036-anterior".toByteArray()).toString())
        risk.onEvaluation(other, MonitoringFixtures.evaluation("RISK", MonitoringFixtures.alertDto(1)), clock.instant)

        val vm = create()

        assertTrue(vm.state.alerts.isEmpty())
        assertNull(vm.state.evaluation)
    }

    // ----------------------------------------------------------------- CA3

    @Test
    fun `CA3 a los 30 s sin datos informa perdida de monitoreo sin presentar el ultimo valor`() = runTest(main.dispatcher) {
        val vm = create()
        reading(150, clock.instant)
        runCurrent()

        tick(30)
        assertTrue(vm.state.live is LiveReading.Live)
        tick(1)
        assertEquals(LiveReading.NoData(clock.instant.minusSeconds(31)), vm.state.live)
    }

    @Test
    fun `CA3 NO_DATA del backend se distingue de la ausencia de riesgo`() = runTest(main.dispatcher) {
        val vm = create()

        risk.onEvaluation(session, MonitoringFixtures.evaluation("NO_DATA"), clock.instant)
        runCurrent()

        assertEquals(RemoteStatus.NO_DATA, vm.state.remote)
    }

    @Test
    fun `sin ninguna lectura durante 30 s se declara perdida de monitoreo`() = runTest(main.dispatcher) {
        val vm = create()
        monitor.value = MonitorSnapshot(MonitorConnection.CONNECTED)

        tick(31)

        assertEquals(LiveReading.NoData(null), vm.state.live)
    }

    // ----------------------------------------------------------------- sin red y reintento

    @Test
    fun `sin red sigue mostrando los datos locales y declara sin evaluacion remota`() = runTest(main.dispatcher) {
        val vm = create()
        risk.onEvaluation(session, MonitoringFixtures.evaluation("OK"), clock.instant)
        reading(132, clock.instant)
        connectivity.state.value = false
        runCurrent()

        assertFalse(vm.state.online)
        assertTrue(vm.state.live is LiveReading.Live)
        assertEquals(RemoteStatus.UNAVAILABLE, vm.state.remote)

        connectivity.state.value = true
        runCurrent()
        assertEquals(RemoteStatus.OK, vm.state.remote)
    }

    @Test
    fun `un lote pendiente de reintento tambien declara sin evaluacion remota`() = runTest(main.dispatcher) {
        val vm = create()
        risk.onEvaluation(session, MonitoringFixtures.evaluation("OK"), clock.instant)

        ingestion.value = IngestionStatus(IngestionState.PENDING_RETRY, pendingSamples = 10)
        runCurrent()

        assertEquals(RemoteStatus.UNAVAILABLE, vm.state.remote)
    }

    // ----------------------------------------------------------------- sin sesión o sin dispositivo

    @Test
    fun `sin sesion activa no abre la conexion con el monitor`() = runTest(main.dispatcher) {
        active.value = null

        val vm = create()

        assertNull(vm.state.session)
        assertTrue(observed.isEmpty())
        assertFalse(vm.state.canStop)
    }

    @Test
    fun `sin monitor vinculado en este telefono lo informa`() = runTest(main.dispatcher) {
        connectable.connectable.value = null

        val vm = create()

        assertEquals(false, vm.state.hasDevice)
        assertTrue(observed.isEmpty())
    }

    // ----------------------------------------------------------------- CA5

    @Test
    fun `CA5 sin capacidad explica el plan y no consulta nada`() = runTest(main.dispatcher) {
        val vm = create(canUse = false)

        assertTrue(vm.state.blockedByPlan)
        assertEquals(0, gateway.settingsCalls)
        assertTrue(observed.isEmpty())
    }

    @Test
    fun `CA5 un 403 capability_required del BFF tambien explica el plan`() = runTest(main.dispatcher) {
        gateway.settingsResults += SettingsResult.Failed(SettingsProblem.CAPABILITY_REQUIRED)

        val vm = create()

        assertTrue(vm.state.capabilityRequired)
        assertTrue(vm.state.blockedByPlan)
    }

    @Test
    fun `CA5 la ingestion rechazada por capacidad bloquea la pantalla`() = runTest(main.dispatcher) {
        val vm = create()

        ingestion.value = IngestionStatus(IngestionState.CAPABILITY_REQUIRED)
        runCurrent()

        assertTrue(vm.state.blockedByPlan)
    }

    // ----------------------------------------------------------------- CA4

    @Test
    fun `CA4 detener la practica finaliza la sesion una sola vez`() = runTest(main.dispatcher) {
        val vm = create()
        val gate = CompletableDeferred<Unit>()
        stopper.gate = gate

        vm.askStop()
        assertEquals(StopPhase.CONFIRMING, vm.state.stop)
        vm.confirmStop()
        vm.confirmStop()
        vm.askStop()
        runCurrent()
        assertEquals(StopPhase.STOPPING, vm.state.stop)
        assertFalse(vm.state.canStop)

        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf(owner to SensorsFixtures.SESSION_ID), stopper.stops)
        assertEquals(StopPhase.STOPPED, vm.state.stop)
        vm.askStop()
        vm.confirmStop()
        runCurrent()
        assertEquals(1, stopper.stops.size)
    }

    @Test
    fun `CA4 una sesion que ya no estaba abierta tambien queda detenida sin repetir`() = runTest(main.dispatcher) {
        stopper.result = FinishResult.NOT_OPEN
        val vm = create()

        vm.askStop()
        vm.confirmStop()
        runCurrent()

        assertEquals(StopPhase.STOPPED, vm.state.stop)
    }

    @Test
    fun `una falla local al detener permite reintentar`() = runTest(main.dispatcher) {
        stopper.result = FinishResult.STORAGE_FAILED
        val vm = create()

        vm.askStop()
        vm.confirmStop()
        runCurrent()
        assertEquals(StopPhase.FAILED, vm.state.stop)
        assertTrue(vm.state.canStop)

        stopper.result = FinishResult.FINISHED
        vm.askStop()
        vm.confirmStop()
        runCurrent()
        assertEquals(StopPhase.STOPPED, vm.state.stop)
        assertEquals(2, stopper.stops.size)
    }

    @Test
    fun `cancelar la confirmacion no detiene la practica`() = runTest(main.dispatcher) {
        val vm = create()

        vm.askStop()
        vm.cancelStop()
        vm.confirmStop()
        runCurrent()

        assertEquals(StopPhase.IDLE, vm.state.stop)
        assertTrue(stopper.stops.isEmpty())
    }

    // ----------------------------------------------------------------- umbral

    @Test
    fun `muestra el umbral por defecto del backend y su aviso`() = runTest(main.dispatcher) {
        val vm = create()

        val threshold = vm.state.threshold
        assertFalse(threshold.loading)
        assertEquals(171, threshold.settings?.maxHeartRateAlertBpm)
        assertEquals(ThresholdSource.FIXED_DEFAULT, threshold.settings?.source)
        assertEquals(MonitoringFixtures.DISCLAIMER, threshold.settings?.disclaimer)
        assertEquals("171", threshold.draft)
    }

    @Test
    fun `un umbral fuera de rango no se envia y uno valido se confirma`() = runTest(main.dispatcher) {
        val vm = create()

        vm.onDraftChange("99")
        vm.saveThreshold()
        runCurrent()
        assertTrue(vm.state.threshold.invalidDraft)
        assertTrue(gateway.updates.isEmpty())

        vm.onDraftChange("1a2x0")
        assertEquals("120", vm.state.threshold.draft)
        vm.saveThreshold()
        runCurrent()

        assertEquals(listOf(120), gateway.updates)
        assertTrue(vm.state.threshold.saved)
        assertEquals(120, vm.state.threshold.settings?.maxHeartRateAlertBpm)
        assertEquals(ThresholdSource.CUSTOM, vm.state.threshold.settings?.source)
    }

    @Test
    fun `sin red el cambio de umbral no se confirma y conserva el vigente`() = runTest(main.dispatcher) {
        val vm = create()
        gateway.updateResults += SettingsResult.Failed(SettingsProblem.OFFLINE)

        vm.onDraftChange("130")
        vm.saveThreshold()
        runCurrent()

        assertEquals(SettingsProblem.OFFLINE, vm.state.threshold.saveProblem)
        assertEquals(171, vm.state.threshold.settings?.maxHeartRateAlertBpm)
        assertFalse(vm.state.threshold.saved)
    }

    @Test
    fun `una consulta fallida del umbral permite reintentar`() = runTest(main.dispatcher) {
        gateway.settingsResults += SettingsResult.Failed(SettingsProblem.UNAVAILABLE)
        val vm = create()
        assertEquals(SettingsProblem.UNAVAILABLE, vm.state.threshold.problem)
        assertNull(vm.state.threshold.settings)

        vm.retrySettings()
        runCurrent()

        assertNull(vm.state.threshold.problem)
        assertEquals(171, vm.state.threshold.settings?.maxHeartRateAlertBpm)
    }

    @Test
    fun `una sesion vencida pide iniciar sesion de nuevo`() = runTest(main.dispatcher) {
        gateway.settingsResults += SettingsResult.Failed(SettingsProblem.SIGNED_OUT)

        val vm = create()

        assertTrue(vm.state.sessionExpired)
        vm.onSessionExpiredHandled()
        assertFalse(vm.state.sessionExpired)
    }

    @Test
    fun `refleja si las notificaciones de alerta estan desactivadas`() = runTest(main.dispatcher) {
        notifier.status = SystemNotificationsStatus.PERMISSION_REQUIRED
        val vm = create()
        assertEquals(SystemNotificationsStatus.PERMISSION_REQUIRED, vm.state.notifications)

        notifier.status = SystemNotificationsStatus.ENABLED
        vm.refreshNotifications()
        assertEquals(SystemNotificationsStatus.ENABLED, vm.state.notifications)
    }
}
