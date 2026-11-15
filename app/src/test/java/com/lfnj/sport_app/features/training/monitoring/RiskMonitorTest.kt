package com.lfnj.sport_app.features.training.monitoring

import com.lfnj.sport_app.features.training.monitoring.model.EvaluationStatus
import com.lfnj.sport_app.features.training.monitoring.model.RemoteEvaluation
import com.lfnj.sport_app.features.training.session.foreground.ActiveSession
import com.lfnj.sport_app.testing.FakeRiskAlertNotifier
import com.lfnj.sport_app.testing.MonitoringFixtures
import com.lfnj.sport_app.testing.SensorsFixtures
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** HU036 CA2–CA3: evaluación de cada lote y deduplicación de alertas en el cliente. */
class RiskMonitorTest {
    private val notifier = FakeRiskAlertNotifier()
    private val monitor = RiskMonitor(notifier)
    private val session = ActiveSession(SensorsFixtures.OWNER, SensorsFixtures.SESSION_ID, SensorsFixtures.START, null)
    private val at = SensorsFixtures.START.plusSeconds(75)

    @Test
    fun `una evaluacion RISK publica la alerta y la notifica una vez`() = runTest {
        monitor.onEvaluation(session, MonitoringFixtures.evaluation("RISK", MonitoringFixtures.alertDto(1)), at)

        val state = monitor.state.value
        assertEquals(SensorsFixtures.SESSION_ID, state.sessionId)
        assertEquals(RemoteEvaluation(EvaluationStatus.RISK, at), state.evaluation)
        val alert = state.alerts.single()
        assertEquals(MonitoringFixtures.alertId(1), alert.alertId)
        assertTrue("el texto conserva el aviso no diagnóstico", alert.message.contains("no es un diagnóstico médico"))
        assertEquals(MonitoringFixtures.INSTRUCTION, alert.instruction)
        assertEquals(Instant.parse("2026-10-24T12:31:15Z"), alert.detectedAt)
        assertEquals(listOf(alert), notifier.notified)
    }

    @Test
    fun `el reenvio del mismo lote no repite la alerta ni la notificacion`() = runTest {
        val evaluation = MonitoringFixtures.evaluation("RISK", MonitoringFixtures.alertDto(1))
        monitor.onEvaluation(session, evaluation, at)
        monitor.onEvaluation(session, evaluation, at.plusSeconds(5))
        monitor.onEvaluation(session, MonitoringFixtures.evaluation("RISK", MonitoringFixtures.alertDto(1), MonitoringFixtures.alertDto(1)), at.plusSeconds(10))

        assertEquals(1, monitor.state.value.alerts.size)
        assertEquals(1, notifier.notified.size)
    }

    @Test
    fun `una alerta nueva del backend se agrega primero y se notifica`() = runTest {
        monitor.onEvaluation(session, MonitoringFixtures.evaluation("RISK", MonitoringFixtures.alertDto(1)), at)
        monitor.onEvaluation(session, MonitoringFixtures.evaluation("RISK", MonitoringFixtures.alertDto(2, "2026-10-24T12:32:20Z")), at.plusSeconds(65))

        assertEquals(listOf(MonitoringFixtures.alertId(2), MonitoringFixtures.alertId(1)), monitor.state.value.alerts.map { it.alertId })
        assertEquals(2, notifier.notified.size)
    }

    @Test
    fun `OK y NO_DATA actualizan la evaluacion sin borrar las alertas de la sesion`() = runTest {
        monitor.onEvaluation(session, MonitoringFixtures.evaluation("RISK", MonitoringFixtures.alertDto(1)), at)
        monitor.onEvaluation(session, MonitoringFixtures.evaluation("NO_DATA"), at.plusSeconds(40))

        assertEquals(EvaluationStatus.NO_DATA, monitor.state.value.evaluation?.status)
        assertEquals(1, monitor.state.value.alerts.size)

        monitor.onEvaluation(session, MonitoringFixtures.evaluation("OK"), at.plusSeconds(45))
        assertEquals(EvaluationStatus.OK, monitor.state.value.evaluation?.status)
    }

    @Test
    fun `otra sesion empieza sin alertas y puede notificar de nuevo`() = runTest {
        monitor.onEvaluation(session, MonitoringFixtures.evaluation("RISK", MonitoringFixtures.alertDto(1)), at)
        val next = session.copy(sessionId = java.util.UUID.nameUUIDFromBytes("hu036-otra".toByteArray()).toString())

        monitor.onEvaluation(next, MonitoringFixtures.evaluation("OK"), at.plusSeconds(600))

        assertEquals(next.sessionId, monitor.state.value.sessionId)
        assertTrue(monitor.state.value.alerts.isEmpty())
        assertTrue(monitor.state.value.forSession(session.sessionId).alerts.isEmpty())
        monitor.onEvaluation(next, MonitoringFixtures.evaluation("RISK", MonitoringFixtures.alertDto(1)), at.plusSeconds(605))
        assertEquals(2, notifier.notified.size)
    }

    @Test
    fun `estados o alertas fuera del contrato no se presentan`() = runTest {
        monitor.onEvaluation(session, MonitoringFixtures.evaluation("DESCONOCIDO", MonitoringFixtures.alertDto(1)), at)
        assertEquals(null, monitor.state.value.evaluation)

        monitor.onEvaluation(
            session,
            MonitoringFixtures.evaluation("RISK", MonitoringFixtures.alertDto(1, rule = "OTRA_REGLA"), MonitoringFixtures.alertDto(2, detectedAt = "ayer")),
            at,
        )
        assertEquals(EvaluationStatus.RISK, monitor.state.value.evaluation?.status)
        assertTrue(monitor.state.value.alerts.isEmpty())
        assertTrue(notifier.notified.isEmpty())
    }

    @Test
    fun `sin permiso de notificaciones la alerta sigue visible en pantalla`() = runTest {
        notifier.status = com.lfnj.sport_app.features.notifications.model.SystemNotificationsStatus.PERMISSION_REQUIRED

        monitor.onEvaluation(session, MonitoringFixtures.evaluation("RISK", MonitoringFixtures.alertDto(1)), at)

        assertEquals(1, monitor.state.value.alerts.size)
    }

    @Test
    fun `conserva como maximo las alertas recientes`() = runTest {
        (1..RiskMonitor.MAX_ALERTS + 3).forEach { n ->
            monitor.onEvaluation(session, MonitoringFixtures.evaluation("RISK", MonitoringFixtures.alertDto(n, at.plusSeconds(60L * n).toString())), at.plusSeconds(60L * n))
        }

        assertEquals(RiskMonitor.MAX_ALERTS, monitor.state.value.alerts.size)
        assertEquals(MonitoringFixtures.alertId(RiskMonitor.MAX_ALERTS + 3), monitor.state.value.alerts.first().alertId)
    }
}
