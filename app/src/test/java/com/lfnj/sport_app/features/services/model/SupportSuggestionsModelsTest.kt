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

/** HU029: guarda visual, partición local, códigos del contrato y antigüedad de la lista. */
class SupportSuggestionsModelsTest {
    private val now = Instant.parse("2026-10-25T15:00:00Z")

    private fun access(status: SubscriptionStatus, capabilities: List<String>) =
        AccessSnapshot(ActorType.ATHLETE, status, "FREE", setOf(), capabilities.toSet())

    private fun snapshot(
        missing: List<String> = emptyList(),
        events: SupportSourceStatus? = SupportSourceStatus.OK,
        syncedAt: Instant = now,
        items: List<SupportServiceSuggestion> = emptyList(),
    ) = SupportSuggestionsSnapshot(items, missing.isEmpty(), missing, now, events, SupportSourceStatus.OK, syncedAt)

    private fun service(id: String, reasons: List<String>) = SupportServiceSuggestion(
        serviceId = id,
        title = "Servicio",
        summary = "Descripción",
        category = "TRANSPORT",
        modality = "IN_PERSON",
        fee = ServiceFee("80000.00", "COP", "SERVICE"),
        nextInterval = SupportInterval(now, now.plus(Duration.ofHours(2)), ZoneId.of("America/Bogota")),
        authorName = null,
        reasons = reasons,
    )

    @Test
    fun `solo un deportista con suscripcion activa y la capacidad ve la pantalla`() {
        assertTrue(SupportSuggestionsAccess.canView(access(SubscriptionStatus.ACTIVE, SessionFixtures.FREE_CAPABILITIES)))
        assertFalse(SupportSuggestionsAccess.canView(access(SubscriptionStatus.ACTIVE, SessionFixtures.FREE_CAPABILITIES - SERVICES_SUPPORT_SUGGEST_CAPABILITY)))
        assertFalse(SupportSuggestionsAccess.canView(access(SubscriptionStatus.PROVISIONING, SessionFixtures.FREE_CAPABILITIES)))
        assertFalse(SupportSuggestionsAccess.canView(null))
        assertEquals("services.support.suggest", SERVICES_SUPPORT_SUGGEST_CAPABILITY)
    }

    @Test
    fun `la particion es una huella estable que no contiene el identificador`() {
        val userId = "0b4e7c1a-2d3f-4a5b-8c6d-7e8f9a0b1c2d"
        val partition = SupportSuggestionsOwner.keyOf(userId)

        assertEquals(64, partition.length)
        assertEquals(partition, SupportSuggestionsOwner.keyOf(userId))
        assertFalse(userId in partition)
        assertNotEquals(partition, SupportSuggestionsOwner.keyOf("1b4e7c1a-2d3f-4a5b-8c6d-7e8f9a0b1c2d"))
    }

    @Test
    fun `los codigos conocidos se reconocen y los desconocidos no se reinterpretan`() {
        assertEquals(SupportCategory.ACCOMPANIMENT, SupportCategory.fromWire("ACCOMPANIMENT"))
        assertNull("medicina deportiva nunca es una categoría sugerible", SupportCategory.fromWire("SPORTS_MEDICINE"))
        assertEquals(SupportModality.IN_PERSON, SupportModality.fromWire("IN_PERSON"))
        assertNull(SupportModality.fromWire("OUTDOOR"))
        assertEquals(SupportSource.EVENTS, SupportSource.fromWire("events"))
        assertNull(SupportSourceStatus.fromWire("DEGRADED"))
        assertEquals(
            listOf(SupportReason.UPCOMING_AVAILABILITY),
            service("a", listOf("UPCOMING_AVAILABILITY", "CITY_MATCH")).knownReasons,
        )
    }

    @Test
    fun `distingue la falta de inscripciones de la caida de Eventos (CA2, CA5)`() {
        val withoutRegistrations = snapshot(missing = listOf("event_registrations"))
        assertTrue(withoutRegistrations.missingRegistrations)
        assertFalse(withoutRegistrations.eventsUnavailable)

        val eventsDown = snapshot(missing = listOf("event_registrations"), events = SupportSourceStatus.UNAVAILABLE)
        assertTrue(eventsDown.eventsUnavailable)

        val complete = snapshot()
        assertFalse(complete.missingRegistrations)
        assertFalse(complete.eventsUnavailable)
        assertFalse(snapshot(events = null).eventsUnavailable)
    }

    @Test
    fun `la lista es STALE despues de 24 horas`() {
        assertFalse(snapshot(syncedAt = now.minus(Duration.ofHours(24))).isStale(now))
        assertTrue(snapshot(syncedAt = now.minus(Duration.ofHours(24)).minusSeconds(1)).isStale(now))
    }

    @Test
    fun `busca un servicio confirmado por su identificador`() {
        val list = snapshot(items = listOf(service("a", emptyList()), service("b", emptyList())))

        assertEquals("b", list.find("b")?.serviceId)
        assertNull(list.find("c"))
    }
}
