package com.lfnj.sport_app.features.training.monitoring.model

import com.lfnj.sport_app.core.session.AccessSnapshot
import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.SubscriptionStatus
import com.lfnj.sport_app.features.sensors.model.HeartRateReading
import com.lfnj.sport_app.features.sensors.model.MonitorConnection
import com.lfnj.sport_app.features.sensors.model.MonitorSnapshot
import com.lfnj.sport_app.features.training.monitoring.data.toModel
import com.lfnj.sport_app.features.training.monitoring.data.MonitoringSettingsDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** HU036 CA1, CA3 y CA5 en las reglas puras del cliente (ADR-044 §8, §9 y §11). */
class MonitoringModelsTest {
    private val t0 = Instant.parse("2026-10-24T12:30:00Z")

    private fun connected(bpm: Int, at: Instant) = MonitorSnapshot(MonitorConnection.CONNECTED, lastReading = HeartRateReading(bpm, at))

    // ----------------------------------------------------------------- CA1: lectura en vivo

    @Test
    fun `una lectura reciente y conectada se muestra con su instante y antiguedad`() {
        val live = LiveReadingRules.of(connected(142, t0), t0.plusSeconds(4), since = t0)

        assertEquals(LiveReading.Live(142, t0, 4), live)
    }

    @Test
    fun `sin ninguna muestra se espera hasta 30 s y despues se declara perdida de monitoreo`() {
        val snapshot = MonitorSnapshot(MonitorConnection.CONNECTED)

        assertEquals(LiveReading.Waiting, LiveReadingRules.of(snapshot, t0.plusSeconds(30), since = t0))
        assertEquals(LiveReading.NoData(null), LiveReadingRules.of(snapshot, t0.plusSeconds(31), since = t0))
        assertEquals(LiveReading.Waiting, LiveReadingRules.of(null, t0.plusSeconds(300), since = null))
    }

    // ----------------------------------------------------------------- CA3: NO_DATA local

    @Test
    fun `a los 30 s sigue en vivo y a los 31 s es perdida de monitoreo sin valor actual`() {
        val snapshot = connected(150, t0)

        assertTrue(LiveReadingRules.of(snapshot, t0.plusSeconds(30), since = t0) is LiveReading.Live)
        val lost = LiveReadingRules.of(snapshot, t0.plusSeconds(31), since = t0)
        assertEquals(LiveReading.NoData(t0), lost)
        assertFalse("NO_DATA no conserva el valor", lost.toString().contains("150"))
    }

    @Test
    fun `una desconexion reciente no presenta el ultimo valor como actual`() {
        val snapshot = MonitorSnapshot(MonitorConnection.DISCONNECTED, lastReading = HeartRateReading(150, t0))

        assertEquals(LiveReading.Reconnecting(t0), LiveReadingRules.of(snapshot, t0.plusSeconds(10), since = t0))
        assertEquals(LiveReading.NoData(t0), LiveReadingRules.of(snapshot, t0.plusSeconds(45), since = t0))
    }

    @Test
    fun `los errores terminales del monitor se informan como no disponibles`() {
        listOf(MonitorConnection.MISSING_HEART_RATE_SERVICE, MonitorConnection.PERMISSION_REQUIRED, MonitorConnection.UNAVAILABLE).forEach { connection ->
            val snapshot = MonitorSnapshot(connection, lastReading = HeartRateReading(150, t0))
            assertEquals(LiveReading.Unavailable(connection), LiveReadingRules.of(snapshot, t0.plusSeconds(1), since = t0))
        }
    }

    @Test
    fun `un reloj por detras de la muestra no produce antiguedad negativa`() {
        assertEquals(LiveReading.Live(120, t0, 0), LiveReadingRules.of(connected(120, t0), t0.minusSeconds(2), since = t0))
    }

    // ----------------------------------------------------------------- umbral

    @Test
    fun `el umbral admite solo enteros de 100 a 220`() {
        assertEquals(100, ThresholdRules.parse("100"))
        assertEquals(220, ThresholdRules.parse(" 220 "))
        listOf("99", "221", "", "abc", "150.5", "-150").forEach { assertNull(it, ThresholdRules.parse(it)) }
    }

    @Test
    fun `el umbral por defecto del backend se conserva con su origen`() {
        val fixed = MonitoringSettingsDto(171, "FIXED_DEFAULT", null, "aviso").toModel()
        assertEquals(MonitoringSettings(171, ThresholdSource.FIXED_DEFAULT, null, "aviso"), fixed)

        val byAge = MonitoringSettingsDto(167, "AGE_DEFAULT", null, "aviso").toModel()
        assertEquals(ThresholdSource.AGE_DEFAULT, byAge?.source)
        assertEquals(167, byAge?.maxHeartRateAlertBpm)

        val custom = MonitoringSettingsDto(120, "CUSTOM", "2026-10-24T12:00:00Z", "aviso").toModel()
        assertEquals(Instant.parse("2026-10-24T12:00:00Z"), custom?.updatedAt)
    }

    @Test
    fun `un umbral fuera del contrato no se completa con valores inventados`() {
        assertNull(MonitoringSettingsDto(171, "OTRO", null, "aviso").toModel())
        assertNull(MonitoringSettingsDto(90, "CUSTOM", null, "aviso").toModel())
        assertNull(MonitoringSettingsDto(171, "CUSTOM", "ayer", "aviso").toModel())
    }

    @Test
    fun `los estados de evaluacion son los del contrato`() {
        assertEquals(EvaluationStatus.NO_DATA, EvaluationStatus.fromWire("NO_DATA"))
        assertNull(EvaluationStatus.fromWire("UNKNOWN"))
        assertEquals(setOf("OK", "RISK", "NO_DATA"), EvaluationStatus.entries.map { it.name }.toSet())
    }

    // ----------------------------------------------------------------- CA5: capacidad

    @Test
    fun `solo un deportista activo con training realtime monitor puede usar la opcion`() {
        fun access(capabilities: Set<String>, actor: ActorType = ActorType.ATHLETE, status: SubscriptionStatus = SubscriptionStatus.ACTIVE) =
            AccessSnapshot(actor, status, "INTERMEDIATE", emptySet(), capabilities)

        assertTrue(MonitoringAccess.canUse(access(setOf("training.realtime.monitor"))))
        assertFalse(MonitoringAccess.canUse(access(setOf("devices.measurement.connect"))))
        assertFalse(MonitoringAccess.canUse(access(setOf("training.realtime.monitor"), status = SubscriptionStatus.entries.first { it != SubscriptionStatus.ACTIVE })))
        assertFalse(MonitoringAccess.canUse(null))
    }
}
