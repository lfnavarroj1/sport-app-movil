package com.lfnj.sport_app.features.events.model

import com.lfnj.sport_app.core.session.AccessSnapshot
import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.SubscriptionStatus
import com.lfnj.sport_app.features.recommendations.model.ContextSourceStatus
import com.lfnj.sport_app.testing.TrainingFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** HU027: guarda visual, partición local, motivos, tarifa gratuita y antigüedad de la lista. */
class EventSuggestionsModelsTest {
    private fun access(capabilities: Set<String>, status: SubscriptionStatus = SubscriptionStatus.ACTIVE) =
        AccessSnapshot(ActorType.ATHLETE, status, "FREE", emptySet(), capabilities)

    private fun event(fee: EventPrice?, registration: MyRegistration? = null, reasons: List<String> = emptyList()) = EventSuggestion(
        eventId = "3f2a1b0c-9d8e-4f7a-8b6c-5d4e3f2a1b0c", title = "Media maratón", summary = "", discipline = "Atletismo",
        location = EventPlace("Parque", "Bogotá", "CO"), startsAt = Instant.parse("2026-11-15T12:00:00Z"),
        endsAt = Instant.parse("2026-11-15T17:00:00Z"), timeZone = "America/Bogota", capacity = 10, fee = fee,
        myRegistration = registration, reasons = reasons,
    )

    @Test
    fun `solo un deportista activo con events_suggest puede abrir la pantalla`() {
        assertTrue(EventSuggestionsAccess.canView(access(setOf(EVENTS_SUGGEST_CAPABILITY))))
        assertFalse(EventSuggestionsAccess.canView(access(setOf("training.routes.suggest"))))
        assertFalse(EventSuggestionsAccess.canView(access(setOf(EVENTS_SUGGEST_CAPABILITY), SubscriptionStatus.PROVISIONING)))
        assertFalse(EventSuggestionsAccess.canView(null))
        assertEquals("events.suggest", EVENTS_SUGGEST_CAPABILITY)
    }

    @Test
    fun `la particion es una huella estable que no contiene el user_id`() {
        val key = EventSuggestionsOwner.keyOf(TrainingFixtures.USER_ID)

        assertEquals(64, key.length)
        assertEquals(key, EventSuggestionsOwner.keyOf(TrainingFixtures.USER_ID))
        assertNotEquals(key, EventSuggestionsOwner.keyOf(TrainingFixtures.OTHER_USER_ID))
        assertFalse(TrainingFixtures.USER_ID in key)
    }

    @Test
    fun `los motivos conocidos se interpretan y los desconocidos se conservan`() {
        val item = event(fee = null, reasons = listOf("UPCOMING", "NEARBY", "SPORT_MATCH"))

        assertEquals(listOf(EventSuggestionReason.UPCOMING, EventSuggestionReason.SPORT_MATCH), item.knownReasons)
        assertEquals(listOf("UPCOMING", "NEARBY", "SPORT_MATCH"), item.reasons)
        assertNull(EventSuggestionReason.fromWire("sport_match"))
        assertEquals(EventMissingContext.CITY, EventMissingContext.fromWire("city"))
        assertNull(EventMissingContext.fromWire("country_code"))
    }

    @Test
    fun `solo la tarifa cero es gratuita y sin tarifa no se presume gratuito`() {
        assertTrue(event(EventPrice("0.00", "COP")).free)
        assertTrue(event(EventPrice("0", "COP")).free)
        assertFalse(event(EventPrice("45000.00", "COP")).free)
        assertFalse(event(EventPrice("gratis", "COP")).free)
        assertFalse(event(null).free)
    }

    @Test
    fun `ya inscrito solo cuando existe my_registration`() {
        assertFalse(event(null).registered)
        assertTrue(event(null, MyRegistration("r-1", Instant.parse("2026-10-21T10:00:00Z"))).registered)
    }

    @Test
    fun `la lista es STALE pasadas 24 horas desde la ultima confirmacion`() {
        val synced = Instant.parse("2026-10-25T13:00:00Z")
        val snapshot = EventSuggestionsSnapshot(emptyList(), false, listOf("sports", "x"), synced, ContextSourceStatus.UNAVAILABLE, synced)

        assertFalse(snapshot.isStale(synced.plusSeconds(24 * 3600)))
        assertTrue(snapshot.isStale(synced.plusSeconds(24 * 3600 + 1)))
        assertTrue(snapshot.contextUnavailable)
        assertEquals(listOf(EventMissingContext.SPORTS), snapshot.knownMissingContext)
    }
}
