package com.lfnj.sport_app.features.health

import com.lfnj.sport_app.features.health.data.HealthReportsGateway
import com.lfnj.sport_app.features.health.data.HealthSubmitResult
import com.lfnj.sport_app.features.health.model.BodyArea
import com.lfnj.sport_app.features.health.model.HealthReportDetails
import com.lfnj.sport_app.features.health.model.HealthReportType
import com.lfnj.sport_app.features.health.model.HealthSeverity
import com.lfnj.sport_app.features.health.model.HealthSyncState
import com.lfnj.sport_app.features.health.model.LocalHealthReport
import com.lfnj.sport_app.features.health.model.OccurredAtError
import com.lfnj.sport_app.features.health.model.RecentTrainingSession
import com.lfnj.sport_app.features.health.model.RecoveryDaysError
import com.lfnj.sport_app.testing.HealthFixtures
import com.lfnj.sport_app.testing.MainDispatcherRule
import com.lfnj.sport_app.testing.MetricsClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** HU042: formulario de «Mi salud», consentimiento, sin red, encolado y aviso de severidad alta. */
class HealthReportViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = MetricsClock(HealthFixtures.NOW)

    private class FakeGateway(override val consentVersion: String? = HealthFixtures.CONSENT_VERSION) : HealthReportsGateway {
        val reports = MutableStateFlow<List<LocalHealthReport>>(emptyList())
        val submitted = mutableListOf<HealthReportDetails>()
        val dismissed = mutableListOf<String>()
        var result: HealthSubmitResult = HealthSubmitResult.Queued("r-1")

        override fun observe(owner: String): Flow<List<LocalHealthReport>> = reports

        override suspend fun submit(owner: String, details: HealthReportDetails): HealthSubmitResult {
            submitted += details
            return result
        }

        override suspend fun dismiss(owner: String, reportId: String): Boolean {
            dismissed += reportId
            return true
        }
    }

    private val sessions = MutableStateFlow(listOf(RecentTrainingSession(HealthFixtures.SESSION_ID, Instant.parse("2026-10-24T07:00:00Z"))))
    private val online = MutableStateFlow(true)

    private fun vm(gateway: FakeGateway = FakeGateway()) = HealthReportViewModel("owner", gateway, sessions, online, clock)

    private fun HealthReportViewModel.fill() {
        selectType(HealthReportType.INJURY)
        selectBodyArea(BodyArea.ANKLE_FOOT)
        selectSeverity(HealthSeverity.MODERATE)
        setConsent(true)
    }

    @Test
    fun `CA1 un formulario completo con consentimiento se encola con los datos elegidos`() = runTest(mainDispatcherRule.dispatcher) {
        val gateway = FakeGateway()
        val vm = vm(gateway)
        advanceUntilIdle()

        vm.fill()
        vm.selectSession(HealthFixtures.SESSION_ID)
        vm.onRecoveryDaysChange("14")
        vm.submit()
        advanceUntilIdle()

        val sent = gateway.submitted.single()
        assertEquals(HealthReportType.INJURY, sent.type)
        assertEquals(BodyArea.ANKLE_FOOT, sent.bodyArea)
        assertEquals(HealthSeverity.MODERATE, sent.severity)
        assertEquals(HealthFixtures.NOW, sent.occurredAt)
        assertEquals(HealthFixtures.SESSION_ID, sent.sessionId)
        assertEquals(14, sent.expectedRecoveryDays)
        val state = vm.uiState.value
        assertEquals(HealthSubmitNotice.QUEUED, state.submitNotice)
        assertNull("El formulario se reinicia", state.type)
        assertFalse("El consentimiento se acepta en cada reporte", state.consentAccepted)
    }

    @Test
    fun `CA2 sin campos obligatorios o sin consentimiento no se encola nada`() = runTest(mainDispatcherRule.dispatcher) {
        val gateway = FakeGateway()
        val vm = vm(gateway)
        advanceUntilIdle()

        vm.submit()

        assertEquals(setOf(HealthField.TYPE, HealthField.BODY_AREA, HealthField.SEVERITY, HealthField.CONSENT), vm.uiState.value.missing)
        vm.fill()
        vm.setConsent(false)
        vm.submit()
        assertEquals(setOf(HealthField.CONSENT), vm.uiState.value.missing)
        advanceUntilIdle()
        assertTrue(gateway.submitted.isEmpty())
    }

    @Test
    fun `CA2 una fecha fuera de rango o dias de recuperacion invalidos bloquean el envio`() = runTest(mainDispatcherRule.dispatcher) {
        val gateway = FakeGateway()
        val vm = vm(gateway)
        advanceUntilIdle()
        vm.fill()

        vm.setOccurredAt(HealthFixtures.NOW.plus(Duration.ofMinutes(10)))
        vm.submit()
        assertEquals(OccurredAtError.IN_FUTURE, vm.uiState.value.occurredAtError)

        vm.setOccurredAt(HealthFixtures.NOW.minus(Duration.ofDays(366)))
        vm.submit()
        assertEquals(OccurredAtError.TOO_OLD, vm.uiState.value.occurredAtError)

        vm.setOccurredAt(HealthFixtures.NOW.minusSeconds(60))
        vm.onRecoveryDaysChange("400")
        vm.submit()
        assertEquals(RecoveryDaysError.OUT_OF_RANGE, vm.uiState.value.recoveryDaysError)
        advanceUntilIdle()
        assertTrue(gateway.submitted.isEmpty())
    }

    @Test
    fun `los dias de recuperacion solo aceptan digitos`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = vm()
        vm.onRecoveryDaysChange("1a2-3.4")
        assertEquals("123", vm.uiState.value.recoveryDays)
    }

    @Test
    fun `CA6 solo la severidad alta muestra el texto de consulta profesional`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = vm()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.showsProfessionalAdvice)
        vm.selectSeverity(HealthSeverity.HIGH)
        assertTrue(vm.uiState.value.showsProfessionalAdvice)
        vm.selectSeverity(HealthSeverity.LOW)
        assertFalse(vm.uiState.value.showsProfessionalAdvice)
    }

    @Test
    fun `CA5 sin red se informa y el reporte igualmente se guarda en el dispositivo`() = runTest(mainDispatcherRule.dispatcher) {
        online.value = false
        val gateway = FakeGateway()
        val vm = vm(gateway)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.online)
        vm.fill()
        vm.submit()
        advanceUntilIdle()

        assertEquals(1, gateway.submitted.size)
        assertEquals(HealthSubmitNotice.QUEUED, vm.uiState.value.submitNotice)
    }

    @Test
    fun `CA5 la lista muestra el estado real de cada reporte`() = runTest(mainDispatcherRule.dispatcher) {
        val gateway = FakeGateway()
        val vm = vm(gateway)
        val pending = LocalHealthReport("r-1", HealthSyncState.PENDING_SYNC, null, null, null, HealthFixtures.NOW, HealthFixtures.details(), null)
        gateway.reports.value = listOf(pending)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.loading)
        assertEquals(HealthSyncState.PENDING_SYNC, vm.uiState.value.reports.single().state)
        assertNull(vm.uiState.value.reports.single().reportedAt)
    }

    @Test
    fun `sin version de consentimiento el formulario no envia y lo explica`() = runTest(mainDispatcherRule.dispatcher) {
        val gateway = FakeGateway(consentVersion = null)
        val vm = vm(gateway)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.consentAvailable)
        vm.fill()
        vm.submit()
        advanceUntilIdle()

        assertTrue(gateway.submitted.isEmpty())
        assertEquals(HealthSubmitNotice.CONSENT_UNAVAILABLE, vm.uiState.value.submitNotice)
    }

    @Test
    fun `una falla de almacenamiento se informa y conserva el formulario`() = runTest(mainDispatcherRule.dispatcher) {
        val gateway = FakeGateway().apply { result = HealthSubmitResult.StorageFailed }
        val vm = vm(gateway)
        advanceUntilIdle()
        vm.fill()

        vm.submit()
        advanceUntilIdle()

        assertEquals(HealthSubmitNotice.STORAGE, vm.uiState.value.submitNotice)
        assertEquals(HealthReportType.INJURY, vm.uiState.value.type)
        assertFalse(vm.uiState.value.busy)
    }

    @Test
    fun `solo se asocia una sesion ofrecida y se quita si desaparece`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = vm()
        advanceUntilIdle()

        vm.selectSession("ajena")
        assertNull(vm.uiState.value.sessionId)
        vm.selectSession(HealthFixtures.SESSION_ID)
        assertEquals(HealthFixtures.SESSION_ID, vm.uiState.value.sessionId)
        sessions.value = emptyList()
        advanceUntilIdle()
        assertNull(vm.uiState.value.sessionId)
    }

    @Test
    fun `retirar delega en la fuente local`() = runTest(mainDispatcherRule.dispatcher) {
        val gateway = FakeGateway()
        val vm = vm(gateway)
        advanceUntilIdle()

        vm.dismiss("r-9")
        advanceUntilIdle()

        assertEquals(listOf("r-9"), gateway.dismissed)
    }
}
