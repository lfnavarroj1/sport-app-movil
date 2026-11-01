package com.lfnj.sport_app.contract

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.features.calendar.data.CalendarApi
import com.lfnj.sport_app.features.calendar.data.CalendarResponseDto
import com.lfnj.sport_app.features.calendar.data.toSnapshot
import com.lfnj.sport_app.features.calendar.model.CALENDAR_CAPABILITY
import com.lfnj.sport_app.features.calendar.model.CalendarItem
import com.lfnj.sport_app.features.calendar.model.CalendarSnapshot
import com.lfnj.sport_app.features.calendar.model.SourceStatus
import com.lfnj.sport_app.testing.CalendarFixtures
import com.lfnj.sport_app.testing.SessionFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import java.time.Instant
import java.time.YearMonth

/**
 * HU035 frente a la sección «Contrato público (`bff-mobile`)» de ADR-031 (aceptado el 2026-10-12):
 * operación y ejemplo del ADR. La correspondencia con la copia OpenAPI está en [CalendarContractTest].
 */
class CalendarAdrContractTest {

    @Test
    fun `la operacion es GET v1 me calendar con token y periodo`() {
        val method = CalendarApi::class.java.declaredMethods.single()

        assertEquals("v1/me/calendar", method.getAnnotation(GET::class.java)?.value)
        val annotations = method.parameterAnnotations.flatten()
        assertEquals(listOf("Authorization"), annotations.filterIsInstance<Header>().map { it.value })
        assertEquals(listOf("from", "to"), annotations.filterIsInstance<Query>().map { it.value })
    }

    @Test
    fun `el ejemplo 200 de ADR-031 se interpreta con ambas fuentes`() {
        val dto = BffMobileClient.json.decodeFromString(CalendarResponseDto.serializer(), CalendarFixtures.body())

        val snapshot = dto.toSnapshot(YearMonth.of(2026, 11), Instant.parse("2026-10-12T22:00:05Z"))

        assertEquals(Instant.parse("2026-11-01T05:00:00Z"), snapshot.from)
        assertEquals(Instant.parse("2026-10-12T22:00:00Z"), snapshot.evaluatedAt)
        val event = snapshot.items[0] as CalendarItem.Event
        assertEquals("Carrera 10K Bogotá", event.title)
        assertEquals("Running", event.sport)
        assertEquals("CO", event.location.countryCode)
        val session = snapshot.items[1] as CalendarItem.PlannedSession
        assertEquals(4, session.rpe)
        assertEquals(CalendarSnapshot.EXPECTED_SOURCES, snapshot.sources.map { it.name })
        assertTrue(snapshot.sources.all { it.status == SourceStatus.OK && !it.truncated })
    }

    @Test
    fun `los estados de fuente son los del contrato`() {
        assertEquals(setOf("OK", "UNAVAILABLE", "TIMEOUT"), SourceStatus.entries.map { it.name }.toSet())
    }

    @Test
    fun `la capacidad es la del plan FREE`() {
        assertTrue(CALENDAR_CAPABILITY in SessionFixtures.FREE_CAPABILITIES)
    }

    @Test
    fun `el cliente no expone actividades al registrar un modelo por accidente`() {
        val dto = BffMobileClient.json.decodeFromString(CalendarResponseDto.serializer(), CalendarFixtures.body())

        listOf(dto.toString(), dto.items.joinToString(), dto.items.first().location.toString()).forEach { text ->
            listOf("Carrera", "Bogotá", "2026-11", "Parque").forEach { assertTrue("«$it» en $text", it !in text) }
        }
    }
}
