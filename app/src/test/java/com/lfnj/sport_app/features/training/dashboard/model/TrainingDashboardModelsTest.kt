package com.lfnj.sport_app.features.training.dashboard.model

import com.lfnj.sport_app.core.session.AccessSnapshot
import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.SubscriptionStatus
import com.lfnj.sport_app.testing.DashboardFixtures
import com.lfnj.sport_app.testing.SessionFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** HU033: periodos con desfase, estados del tablero y agregación visual (CA2, CA3). */
class TrainingDashboardModelsTest {
    private val bogota = ZoneId.of("America/Bogota")
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val today = LocalDate.of(2026, 10, 25)

    // ----------------------------------------------------------------- periodos (CA3)

    @Test
    fun `los periodos predefinidos cubren dias completos hasta hoy con el desfase del dispositivo`() {
        val week = DashboardPeriod.preset(DashboardRange.LAST_7, today, bogota)
        assertEquals("2026-10-19T00:00:00-05:00", week.fromParam)
        assertEquals("2026-10-26T00:00:00-05:00", week.toParam)

        val month = DashboardPeriod.preset(DashboardRange.LAST_30, today, bogota)
        assertEquals("2026-09-26T00:00:00-05:00", month.fromParam)
        assertEquals(Duration.ofDays(30), Duration.between(month.from, month.to))

        val quarter = DashboardPeriod.preset(DashboardRange.LAST_90, today, tokyo)
        assertEquals("2026-07-28T00:00:00+09:00", quarter.fromParam)
        assertEquals("2026-10-26T00:00:00+09:00", quarter.toParam)
    }

    @Test
    fun `en UTC el desfase viaja como Z`() {
        val period = DashboardPeriod.preset(DashboardRange.LAST_7, today, ZoneId.of("UTC"))
        assertEquals("2026-10-19T00:00:00Z", period.fromParam)
    }

    @Test
    fun `el rango personalizado incluye ambos extremos y admite hasta 366 dias`() {
        val period = checkNotNull(DashboardPeriod.custom(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), bogota))
        assertEquals(DashboardRange.CUSTOM, period.range)
        assertEquals("2026-01-01T00:00:00-05:00", period.fromParam)
        assertEquals("2026-02-01T00:00:00-05:00", period.toParam)

        val longest = DashboardPeriod.custom(LocalDate.of(2025, 10, 25), LocalDate.of(2026, 10, 25), bogota)
        assertEquals(Duration.ofDays(366), Duration.between(checkNotNull(longest).from, longest.to))
        assertEquals(DashboardRange.CUSTOM, DashboardPeriod.custom(today, today, bogota)?.range)
    }

    @Test
    fun `un rango personalizado invertido o de mas de 366 dias no se consulta`() {
        assertEquals(CustomRangeError.END_BEFORE_START, DashboardPeriod.validateCustom(today, today.minusDays(1)))
        assertEquals(CustomRangeError.TOO_LONG, DashboardPeriod.validateCustom(LocalDate.of(2025, 10, 24), today))
        assertNull(DashboardPeriod.custom(LocalDate.of(2025, 10, 24), today, bogota))
        assertNull(DashboardPeriod.validateCustom(today, today))
    }

    @Test
    fun `el periodo compara instantes sin depender del desfase`() {
        val period = DashboardPeriod.preset(DashboardRange.LAST_7, today, bogota)
        assertTrue(period.sameInstants(Instant.parse("2026-10-19T05:00:00Z"), Instant.parse("2026-10-26T05:00:00Z")))
        assertFalse(period.sameInstants(Instant.parse("2026-10-19T00:00:00Z"), Instant.parse("2026-10-26T05:00:00Z")))
    }

    // ----------------------------------------------------------------- tablero

    @Test
    fun `los dias del periodo se muestran en la zona del dispositivo`() {
        val snapshot = DashboardFixtures.snapshot()
        assertEquals(LocalDate.of(2026, 9, 26), snapshot.firstDay(bogota))
        assertEquals(LocalDate.of(2026, 10, 25), snapshot.lastDay(bogota))
    }

    @Test
    fun `sin datos y con todas las fuentes es un vacio confirmado (CA2)`() {
        assertTrue(DashboardFixtures.snapshot(hasData = false).confirmedEmpty)
        assertFalse(DashboardFixtures.snapshot(hasData = true).confirmedEmpty)
    }

    @Test
    fun `una fuente caida o ausente nunca se presenta como vacio confirmado`() {
        val down = DashboardFixtures.snapshot(hasData = false, sessions = DashboardSourceStatus.UNAVAILABLE)
        assertFalse(down.confirmedEmpty)
        assertEquals(listOf(DashboardSnapshot.SOURCE_SESSIONS), down.incompleteSources)

        val missing = DashboardFixtures.snapshot().copy(sources = listOf(DashboardSourceState(DashboardSnapshot.SOURCE_SESSIONS, DashboardSourceStatus.OK)))
        assertEquals(listOf(DashboardSnapshot.SOURCE_METRICS), missing.incompleteSources)

        val unknown = DashboardFixtures.snapshot().copy(sources = listOf(DashboardSourceState("training_sessions", null), DashboardSourceState("training_metrics", DashboardSourceStatus.OK)))
        assertFalse(unknown.complete)
    }

    @Test
    fun `el tablero es STALE despues de 24 horas`() {
        val synced = Instant.parse("2026-10-25T14:00:00Z")
        val snapshot = DashboardFixtures.snapshot(syncedAt = synced)
        assertFalse(snapshot.isStale(synced.plus(Duration.ofHours(24))))
        assertTrue(snapshot.isStale(synced.plus(Duration.ofHours(24)).plusSeconds(1)))
    }

    // ----------------------------------------------------------------- agregación visual

    @Test
    fun `las barras son proporcionales al maximo sin alterar el valor`() {
        val bars = DashboardBars.weeks(DashboardFixtures.snapshot().byWeek.reversed())

        assertEquals(listOf(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 19)), bars.map { it.item.weekStart })
        assertEquals(listOf(45L, 50L), bars.map { it.value })
        assertEquals(0.9f, bars[0].fraction, 0.0001f)
        assertEquals(1f, bars[1].fraction, 0.0001f)
    }

    @Test
    fun `los deportes se ordenan por duracion y una serie sin minutos no dibuja barras`() {
        val sports = DashboardBars.sports(DashboardFixtures.snapshot().bySport)
        assertEquals(listOf("cycling", "running"), sports.map { it.item.sport })

        val zeros = DashboardBars.of(listOf(0L, 0L)) { it }
        assertEquals(listOf(0f, 0f), zeros.map { it.fraction })
        assertTrue(DashboardBars.weeks(emptyList()).isEmpty())
    }

    // ----------------------------------------------------------------- acceso

    @Test
    fun `la ruta exige deportista con suscripcion activa y la capacidad del plan`() {
        fun access(capabilities: List<String>, status: SubscriptionStatus = SubscriptionStatus.ACTIVE) =
            AccessSnapshot(ActorType.ATHLETE, status, "FREE", emptySet(), capabilities.toSet())

        assertTrue(TrainingDashboardAccess.canView(access(SessionFixtures.FREE_CAPABILITIES)))
        assertFalse(TrainingDashboardAccess.canView(access(SessionFixtures.FREE_CAPABILITIES - TRAINING_DASHBOARD_CAPABILITY)))
        assertFalse(TrainingDashboardAccess.canView(access(SessionFixtures.FREE_CAPABILITIES, SubscriptionStatus.entries.first { it != SubscriptionStatus.ACTIVE })))
        assertFalse(TrainingDashboardAccess.canView(null))
    }
}
