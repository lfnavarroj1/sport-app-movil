package com.lfnj.sport_app.features.calendar.data.local

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.features.calendar.model.CalendarItem
import com.lfnj.sport_app.features.calendar.model.CalendarSnapshot
import com.lfnj.sport_app.features.calendar.model.CalendarSourceState
import com.lfnj.sport_app.features.calendar.model.EventLocation
import com.lfnj.sport_app.features.calendar.model.SourceStatus
import com.lfnj.sport_app.testing.FakeCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/**
 * HU041 amplía la caché de HU035 con el `event_id` opcional de cada evento, sin migración de
 * `sportapp_calendar.db` ni cambio de su clave: las cachés guardadas antes siguen legibles.
 */
class CalendarCacheCodecCompatibilityTest {
    private val cipher = FakeCipher()
    private val codec = CalendarCacheCodec(cipher, BffMobileClient.json)

    /** Contenido exacto que escribía la versión de HU035, sin `event_id`. */
    private val legacyPayload =
        """{"month":"2026-11","from":1793509200,"to":1796187599,"items":[""" +
            """{"kind":"event","item_id":"EVENT_PUBLISHED:683f4e38-6bb3-4c47-a6dc-9bb1588c67ef","occurs_on":"2026-11-15",""" +
            """"title":"Carrera 10K Bogotá","sport":"Running","starts_at":1794657600,"ends_at":1794668400,""" +
            """"time_zone":"America/Bogota","location_name":"Parque","location_city":"Bogotá","location_country":"CO"},""" +
            """{"kind":"session","item_id":"TRAINING_PLAN_SESSION:1","occurs_on":"2026-11-16","title":"Base",""" +
            """"sport":"running","duration_minutes":45,"rpe":4}],""" +
            """"sources":[{"name":"events","status":"OK","truncated":false},{"name":"training","status":"OK","truncated":false}],""" +
            """"evaluated_at":1792792800,"last_synced_at":1792792800}"""

    @Test
    fun `una cache anterior a HU041 se lee sin event_id y conserva todos sus datos`() {
        val snapshot = checkNotNull(codec.decode(CalendarCacheEntity("owner", cipher.encrypt(legacyPayload.toByteArray(Charsets.UTF_8)))))

        val event = snapshot.items[0] as CalendarItem.Event
        assertNull(event.eventId)
        assertEquals("Carrera 10K Bogotá", event.title)
        assertEquals(Instant.ofEpochSecond(1794657600), event.startsAt)
        assertEquals(EventLocation("Parque", "Bogotá", "CO"), event.location)
        assertEquals(45, (snapshot.items[1] as CalendarItem.PlannedSession).durationMinutes)
        assertEquals(YearMonth.of(2026, 11), snapshot.month)
        assertEquals(Instant.ofEpochSecond(1792792800), snapshot.syncedAt)
    }

    @Test
    fun `el event_id se guarda y se recupera cifrado junto al evento`() {
        val event = CalendarItem.Event(
            "EVENT_PUBLISHED:683f4e38-6bb3-4c47-a6dc-9bb1588c67ef", LocalDate.of(2026, 11, 15), "Carrera 10K", "Running",
            Instant.parse("2026-11-15T12:00:00Z"), Instant.parse("2026-11-15T15:00:00Z"), "America/Bogota",
            EventLocation("Parque", "Bogotá", "CO"), eventId = "683f4e38-6bb3-4c47-a6dc-9bb1588c67ef",
        )
        val session = CalendarItem.PlannedSession("TRAINING_PLAN_SESSION:1", LocalDate.of(2026, 11, 16), "Base", "running", 45, 4)
        val snapshot = CalendarSnapshot(
            YearMonth.of(2026, 11), Instant.EPOCH, Instant.EPOCH, listOf(event, session),
            listOf(CalendarSourceState("events", SourceStatus.OK, false)), Instant.EPOCH, Instant.EPOCH,
        )

        val decoded = checkNotNull(codec.decode(CalendarCacheEntity("owner", codec.encode(snapshot))))

        assertEquals(listOf(event, session), decoded.items)
    }
}
