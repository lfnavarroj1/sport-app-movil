package com.lfnj.sport_app.features.calendar.model

import com.lfnj.sport_app.core.session.AccessSnapshot
import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.SubscriptionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** HU035 CA1, CA2, CA4, CA7 y CA8: periodo por zona, agrupación por día, orden, antigüedad y fuentes. */
class CalendarModelsTest {
    private val bogota = ZoneId.of("America/Bogota")
    private val madrid = ZoneId.of("Europe/Madrid")
    private val november = YearMonth.of(2026, 11)

    private fun event(id: String, startsAt: String, endsAt: String = startsAt, occursOn: String = startsAt.take(10)) =
        CalendarItem.Event(
            id, LocalDate.parse(occursOn), "Evento $id", "Running", Instant.parse(startsAt), Instant.parse(endsAt),
            "America/Bogota", EventLocation("Parque", "Bogotá", "CO"),
        )

    private fun session(id: String, date: String) = CalendarItem.PlannedSession(id, LocalDate.parse(date), "Plan $id", "running", 45, 4)

    private fun snapshot(
        items: List<CalendarItem> = emptyList(),
        sources: List<CalendarSourceState> = listOf(
            CalendarSourceState("events", SourceStatus.OK, false),
            CalendarSourceState("training", SourceStatus.OK, false),
        ),
        syncedAt: Instant = Instant.parse("2026-11-10T12:00:00Z"),
    ) = CalendarSnapshot(november, Instant.EPOCH, Instant.EPOCH, items, sources, Instant.EPOCH, syncedAt)

    // ----------------------------------------------------------------- periodo

    @Test
    fun `el periodo es el mes visible completo en la zona del dispositivo como en ADR-031`() {
        val period = CalendarPeriod.forMonth(november, bogota)

        assertEquals("2026-11-01T00:00:00-05:00", period.fromParam)
        assertEquals("2026-11-30T23:59:59-05:00", period.toParam)
    }

    @Test
    fun `cada extremo usa el desfase vigente en su fecha aunque cambie el horario de verano`() {
        val period = CalendarPeriod.forMonth(YearMonth.of(2026, 10), madrid)

        assertEquals("2026-10-01T00:00:00+02:00", period.fromParam)
        assertEquals("2026-10-31T23:59:59+01:00", period.toParam)
    }

    @Test
    fun `ningun mes supera los 92 dias del contrato y el desde nunca es posterior al hasta`() {
        (0 until 48).map { YearMonth.of(2026, 1).plusMonths(it.toLong()) }.forEach { month ->
            listOf(bogota, madrid, ZoneId.of("Pacific/Kiritimati"), ZoneId.of("UTC")).forEach { zone ->
                val period = CalendarPeriod.forMonth(month, zone)
                val days = Duration.between(period.from, period.to)
                assertTrue("$month $zone", !days.isNegative && days <= Duration.ofDays(92))
            }
        }
        assertEquals("2028-02-29T23:59:59Z", CalendarPeriod.forMonth(YearMonth.of(2028, 2), ZoneId.of("UTC")).toParam)
    }

    // ----------------------------------------------------------------- agrupación por día (CA4)

    @Test
    fun `un evento se ubica en el dia local del dispositivo sin alterar su instante ni su zona`() {
        val night = event("e1", "2026-11-15T03:00:00Z", occursOn = "2026-11-14")

        val inBogota = CalendarDays.group(listOf(night), november, bogota)
        val inMadrid = CalendarDays.group(listOf(night), november, madrid)

        assertEquals(LocalDate.of(2026, 11, 14), inBogota.single().date)
        assertEquals(LocalDate.of(2026, 11, 15), inMadrid.single().date)
        assertEquals(night, inMadrid.single().items.single())
        assertEquals("America/Bogota", (inMadrid.single().items.single() as CalendarItem.Event).timeZone)
    }

    @Test
    fun `una sesion planificada conserva su fecha porque no tiene hora`() {
        val days = CalendarDays.group(listOf(session("s1", "2026-11-16")), november, ZoneId.of("Pacific/Kiritimati"))

        assertEquals(LocalDate.of(2026, 11, 16), days.single().date)
    }

    @Test
    fun `un evento que empezo antes del mes y se superpone con el se muestra el primer dia`() {
        val days = CalendarDays.group(listOf(event("e1", "2026-10-30T12:00:00Z", "2026-11-02T12:00:00Z")), november, bogota)

        assertEquals(LocalDate.of(2026, 11, 1), days.single().date)
    }

    @Test
    fun `los dias quedan en orden y dentro del dia primero lo que no tiene hora y luego por inicio`() {
        val late = event("e-late", "2026-11-16T20:00:00Z")
        val early = event("e-early", "2026-11-16T13:00:00Z")
        val planned = session("s1", "2026-11-16")
        val first = event("e-first", "2026-11-03T13:00:00Z")

        val days = CalendarDays.group(listOf(late, planned, early, first), november, bogota)

        assertEquals(listOf(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 16)), days.map { it.date })
        assertEquals(listOf("s1", "e-early", "e-late"), days[1].items.map { it.itemId })
    }

    @Test
    fun `ante empate se conserva el orden estable del BFF`() {
        val a = session("a", "2026-11-16")
        val b = session("b", "2026-11-16")

        assertEquals(listOf("b", "a"), CalendarDays.group(listOf(b, a), november, bogota).single().items.map { it.itemId })
    }

    // ----------------------------------------------------------------- fuentes, vacío y antigüedad

    @Test
    fun `sin elementos y con ambas fuentes confirmadas es un estado vacio`() {
        assertTrue(snapshot().confirmedEmpty)
    }

    @Test
    fun `una fuente caida o ausente no permite afirmar que no hay actividades`() {
        val partial = snapshot(sources = listOf(CalendarSourceState("events", SourceStatus.OK, false), CalendarSourceState("training", SourceStatus.TIMEOUT, false)))
        val missing = snapshot(sources = listOf(CalendarSourceState("events", SourceStatus.OK, false)))
        val unknownStatus = snapshot(sources = listOf(CalendarSourceState("events", null, false), CalendarSourceState("training", SourceStatus.OK, false)))

        assertEquals(listOf("training"), partial.incompleteSources)
        assertEquals(listOf("training"), missing.incompleteSources)
        assertEquals(listOf("events"), unknownStatus.incompleteSources)
        listOf(partial, missing, unknownStatus).forEach { assertFalse(it.confirmedEmpty) }
    }

    @Test
    fun `las fuentes truncadas se identifican`() {
        val truncated = snapshot(sources = listOf(CalendarSourceState("events", SourceStatus.OK, true), CalendarSourceState("training", SourceStatus.OK, false)))

        assertEquals(listOf("events"), truncated.truncatedSources)
        assertTrue(truncated.complete)
    }

    @Test
    fun `el periodo confirmado queda desactualizado despues de 24 horas`() {
        val synced = Instant.parse("2026-11-10T12:00:00Z")
        val cached = snapshot(syncedAt = synced)

        assertFalse(cached.isStale(synced.plus(Duration.ofHours(24))))
        assertTrue(cached.isStale(synced.plus(Duration.ofHours(24)).plusSeconds(1)))
    }

    // ----------------------------------------------------------------- guarda y partición

    @Test
    fun `la guarda exige deportista activo con la capacidad`() {
        fun access(status: SubscriptionStatus, capabilities: Set<String>) =
            AccessSnapshot(ActorType.ATHLETE, status, "FREE", emptySet(), capabilities)

        assertTrue(CalendarAccess.canView(access(SubscriptionStatus.ACTIVE, setOf(CALENDAR_CAPABILITY))))
        assertFalse(CalendarAccess.canView(access(SubscriptionStatus.ACTIVE, setOf("training.session.record"))))
        assertFalse(CalendarAccess.canView(access(SubscriptionStatus.entries.first { it != SubscriptionStatus.ACTIVE }, setOf(CALENDAR_CAPABILITY))))
        assertFalse(CalendarAccess.canView(null))
    }

    @Test
    fun `la particion es una huella estable que no expone el user_id`() {
        val key = CalendarOwner.keyOf("7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b")

        assertEquals(64, key.length)
        assertEquals(key, CalendarOwner.keyOf("7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b"))
        assertNotEquals(key, CalendarOwner.keyOf("0f0e0d0c-0b0a-4908-8706-050403020100"))
        assertFalse(key.contains("7d8f0b8e"))
    }
}
