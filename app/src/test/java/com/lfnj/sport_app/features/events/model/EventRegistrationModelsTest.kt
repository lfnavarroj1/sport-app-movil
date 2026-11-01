package com.lfnj.sport_app.features.events.model

import com.lfnj.sport_app.testing.EventRegistrationFixtures
import com.lfnj.sport_app.testing.EventRegistrationFixtures.OTHER_REGISTRATION_ID
import com.lfnj.sport_app.testing.EventRegistrationFixtures.REGISTRATION_ID
import com.lfnj.sport_app.testing.TrainingFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** HU041: orden de «Mis inscripciones», vigencia de la caché, partición e identificadores. */
class EventRegistrationModelsTest {
    private val now = Instant.parse("2026-11-10T15:00:00Z")

    @Test
    fun `ordena por fecha del evento y desempata por registration_id`() {
        val later = EventRegistrationFixtures.confirmed("00000000-0000-4000-8000-000000000001", startsAt = Instant.parse("2026-12-01T12:00:00Z"))
        val tieB = EventRegistrationFixtures.confirmed("bbbbbbbb-0000-4000-8000-000000000000")
        val tieA = EventRegistrationFixtures.confirmed("aaaaaaaa-0000-4000-8000-000000000000")
        val snapshot = RegistrationsSnapshot(listOf(later, tieB, tieA), null, now)

        assertEquals(listOf(tieA, tieB, later), snapshot.ordered)
    }

    @Test
    fun `STALE solo despues de 24 horas`() {
        val snapshot = RegistrationsSnapshot(emptyList(), null, now)

        assertFalse(snapshot.isStale(now.plus(Duration.ofHours(24))))
        assertTrue(snapshot.isStale(now.plus(Duration.ofHours(24)).plusSeconds(1)))
    }

    @Test
    fun `agregar una pagina no repite inscripciones y actualiza el cursor`() {
        val first = EventRegistrationFixtures.confirmed(REGISTRATION_ID)
        val second = EventRegistrationFixtures.confirmed(OTHER_REGISTRATION_ID)
        val appended = RegistrationsSnapshot(listOf(first), "c2", now).append(listOf(first, second), null)

        assertEquals(listOf(REGISTRATION_ID, OTHER_REGISTRATION_ID), appended.items.map { it.registrationId })
        assertEquals(null, appended.nextCursor)
        assertEquals(now, appended.syncedAt)
    }

    @Test
    fun `el evento inicia en su starts_at`() {
        val detail = EventRegistrationFixtures.detail(startsAt = now)

        assertFalse(detail.hasStarted(now.minusSeconds(1)))
        assertTrue(detail.hasStarted(now))
    }

    @Test
    fun `solo los UUID son identificadores de evento`() {
        assertTrue(EventIds.isValid(EventRegistrationFixtures.EVENT_ID))
        assertFalse(EventIds.isValid(null))
        assertFalse(EventIds.isValid(""))
        assertFalse(EventIds.isValid("../v1/me"))
        assertFalse(EventIds.isValid("683f4e38-6bb3-4c47-a6dc"))
    }

    @Test
    fun `la particion es una huella estable y distinta por deportista`() {
        val key = EventRegistrationsOwner.keyOf(TrainingFixtures.USER_ID)

        assertEquals(64, key.length)
        assertFalse(key.contains(TrainingFixtures.USER_ID))
        assertEquals(key, EventRegistrationsOwner.keyOf(TrainingFixtures.USER_ID))
        assertNotEquals(key, EventRegistrationsOwner.keyOf(TrainingFixtures.OTHER_USER_ID))
    }
}
