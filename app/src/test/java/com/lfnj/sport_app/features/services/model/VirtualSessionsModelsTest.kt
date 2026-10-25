package com.lfnj.sport_app.features.services.model

import com.lfnj.sport_app.core.session.AccessSnapshot
import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.SubscriptionStatus
import com.lfnj.sport_app.testing.SessionFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** HU040: reglas del cliente (antelación, ventana de cancelación, `STALE`), zonas horarias y formatos. */
class VirtualSessionsModelsTest {
    private val now = Instant.parse("2026-10-25T15:00:00Z")
    private val spanish = Locale.forLanguageTag("es-ES")
    private val bogota = ZoneId.of("America/Bogota")
    private val madrid = ZoneId.of("Europe/Madrid")

    private fun appointment(
        startsAt: Instant = now.plus(Duration.ofDays(3)),
        status: AppointmentStatus = AppointmentStatus.BOOKED,
        id: String = "a",
        availabilityId: String = "slot",
    ) = Appointment(
        appointmentId = id,
        serviceId = "s",
        availabilityId = availabilityId,
        providerId = "p",
        serviceName = "Asesoría",
        modality = "VIRTUAL",
        fee = ServiceFee("120000.00", "COP", "SESSION"),
        startsAt = startsAt,
        endsAt = startsAt.plus(Duration.ofHours(1)),
        timeZone = bogota,
        status = status,
        bookedAt = now.minus(Duration.ofDays(1)),
        cancelledAt = null,
        version = 1,
    )

    // ----------------------------------------------------------------- reglas de ADR-046

    @Test
    fun `un intervalo se reserva con 1 h o mas de antelacion`() {
        assertTrue(VirtualSessionsRules.isBookable(now.plus(Duration.ofHours(1)), now))
        assertTrue(VirtualSessionsRules.isBookable(now.plus(Duration.ofHours(5)), now))
        assertFalse(VirtualSessionsRules.isBookable(now.plus(Duration.ofMinutes(59)), now))
        assertFalse(VirtualSessionsRules.isBookable(now.minus(Duration.ofHours(1)), now))
    }

    @Test
    fun `una cita se cancela con 24 h o mas y no a 2 h de su inicio (CA4)`() {
        assertTrue(appointment(now.plus(Duration.ofHours(24))).canCancel(now))
        assertTrue(appointment(now.plus(Duration.ofDays(3))).canCancel(now))
        assertFalse(appointment(now.plus(Duration.ofHours(24)).minusSeconds(1)).canCancel(now))
        assertFalse(appointment(now.plus(Duration.ofHours(2))).canCancel(now))
        assertFalse(appointment(status = AppointmentStatus.CANCELLED_BY_ATHLETE).canCancel(now))
        assertEquals(now.plus(Duration.ofDays(2)), appointment(now.plus(Duration.ofDays(3))).cancellationDeadline)
    }

    @Test
    fun `proximas son las reservadas sin terminar y el historial conserva canceladas y pasadas (CA6)`() {
        val upcoming = appointment(id = "proxima")
        val cancelled = appointment(id = "cancelada", status = AppointmentStatus.CANCELLED_BY_ATHLETE)
        val past = appointment(now.minus(Duration.ofDays(2)), id = "pasada")
        val snapshot = AppointmentsSnapshot(listOf(past, upcoming, cancelled), now)

        assertEquals(listOf("proxima"), snapshot.upcoming(now).map { it.appointmentId })
        assertEquals(setOf("cancelada", "pasada"), snapshot.history(now).map { it.appointmentId }.toSet())
        assertEquals("proxima", snapshot.activeIn("slot")?.appointmentId)
        assertNull(AppointmentsSnapshot(listOf(cancelled), now).activeIn("slot"))
    }

    @Test
    fun `upsert reemplaza la cita por identificador y conserva el orden por inicio`() {
        val later = appointment(now.plus(Duration.ofDays(5)), id = "b")
        val snapshot = AppointmentsSnapshot(listOf(appointment(id = "a"), later), now)

        val updated = snapshot.upsert(appointment(id = "a", status = AppointmentStatus.CANCELLED_BY_ATHLETE), now)
        assertEquals(listOf("a", "b"), updated.items.map { it.appointmentId })
        assertEquals(AppointmentStatus.CANCELLED_BY_ATHLETE, updated.items[0].status)

        val added = snapshot.upsert(appointment(now.plus(Duration.ofDays(4)), id = "c"), now)
        assertEquals(listOf("a", "c", "b"), added.items.map { it.appointmentId })
    }

    @Test
    fun `la informacion confirmada se marca desactualizada despues de 24 h`() {
        val catalog = CatalogSnapshot(emptyList(), truncated = false, syncedAt = now)
        assertFalse(catalog.isStale(now.plus(Duration.ofHours(24))))
        assertTrue(catalog.isStale(now.plus(Duration.ofHours(24)).plusSeconds(1)))
        assertTrue(AppointmentsSnapshot(emptyList(), now).isStale(now.plus(Duration.ofDays(2))))
    }

    @Test
    fun `solo un deportista activo con la capacidad PREMIUM ve la opcion (CA3)`() {
        fun athlete(capabilities: List<String>, status: SubscriptionStatus = SubscriptionStatus.ACTIVE) =
            AccessSnapshot(ActorType.ATHLETE, status, "PREMIUM", emptySet(), capabilities.toSet())

        assertTrue(VirtualSessionsAccess.canView(athlete(SessionFixtures.PREMIUM_CAPABILITIES)))
        assertFalse(VirtualSessionsAccess.canView(athlete(SessionFixtures.INTERMEDIATE_CAPABILITIES)))
        assertFalse(VirtualSessionsAccess.canView(athlete(SessionFixtures.PREMIUM_CAPABILITIES, SubscriptionStatus.PROVISIONING)))
        assertFalse(VirtualSessionsAccess.canView(null))
    }

    @Test
    fun `la particion es una huella estable del usuario`() {
        val key = VirtualSessionsOwner.keyOf("7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b")
        assertEquals(64, key.length)
        assertEquals(key, VirtualSessionsOwner.keyOf("7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b"))
        assertNotEquals(key, VirtualSessionsOwner.keyOf("0f0e0d0c-0b0a-4908-8706-050403020100"))
    }

    // ----------------------------------------------------------------- zonas horarias

    @Test
    fun `el intervalo se presenta en la zona del dispositivo sin alterar el instante`() {
        val start = Instant.parse("2026-10-28T15:00:00Z")
        val end = Instant.parse("2026-10-28T16:00:00Z")

        val inBogota = SessionTimes.range(start, end, bogota, spanish)
        val inMadrid = SessionTimes.range(start, end, madrid, spanish)

        assertTrue(inBogota, "10:00" in inBogota && "11:00" in inBogota)
        assertTrue(inMadrid, "16:00" in inMadrid && "17:00" in inMadrid)
    }

    @Test
    fun `un intervalo que cruza la medianoche local muestra la fecha del fin`() {
        val start = Instant.parse("2026-10-29T04:30:00Z")
        val end = Instant.parse("2026-10-29T05:30:00Z")

        val text = SessionTimes.range(start, end, bogota, spanish)
        assertTrue(text, "23:30" in text && "28" in text && "29" in text)
    }

    @Test
    fun `detecta si la zona del profesional tiene otro desfase en ese instante`() {
        val instant = Instant.parse("2026-10-28T15:00:00Z")
        assertTrue(SessionTimes.sameOffset(instant, bogota, ZoneId.of("America/Lima")))
        assertFalse(SessionTimes.sameOffset(instant, bogota, madrid))
        // Madrid cambia de horario el 25-10-2026: antes y después tiene desfases distintos.
        assertNotEquals(madrid.rules.getOffset(Instant.parse("2026-10-24T12:00:00Z")), madrid.rules.getOffset(instant))
    }

    // ----------------------------------------------------------------- formatos

    @Test
    fun `la tarifa informativa conserva el importe y la moneda del contrato`() {
        assertEquals("120.000 COP", FeeFormat.amount(ServiceFee("120000.00", "COP", "SESSION"), spanish))
        assertEquals("85.000,50 COP", FeeFormat.amount(ServiceFee("85000.5", "COP", "HOUR"), spanish))
        assertEquals("120,000 COP", FeeFormat.amount(ServiceFee("120000.00", "COP", "SESSION"), Locale.US))
        assertEquals("abc COP", FeeFormat.amount(ServiceFee("abc", "COP", "SESSION"), spanish))
    }

    @Test
    fun `los codigos desconocidos no se reinterpretan`() {
        assertNull(ServiceModality.fromWire("IN_PERSON"))
        assertNull(AppointmentStatus.fromWire("CANCELLED_BY_PROVIDER"))
        assertNull(BillingUnit.fromWire("WEEK"))
        assertEquals(ServiceModality.HYBRID, ServiceModality.fromWire("HYBRID"))
    }
}
