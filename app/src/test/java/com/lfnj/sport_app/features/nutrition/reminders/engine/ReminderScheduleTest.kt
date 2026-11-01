package com.lfnj.sport_app.features.nutrition.reminders.engine

import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderSetting
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderType
import com.lfnj.sport_app.testing.SessionRemindersFixtures.HYDRATION
import com.lfnj.sport_app.testing.SessionRemindersFixtures.NUTRITION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** HU038 CA1: cálculo puro de ocurrencias con tiempo simulado (ADR-045 §4). */
class ReminderScheduleTest {
    private fun minutes(value: Long) = value * 60_000

    @Test
    fun `HYDRATION se repite cada intervalo desde el inicio de la sesion (CA1)`() {
        val occurrences = ReminderSchedule.occurrencesWithin(HYDRATION, ReminderSetting(true, 15), minutes(70))

        assertEquals(listOf(1, 2, 3, 4), occurrences.map { it.n })
        assertEquals(listOf(15L, 30L, 45L, 60L).map(::minutes), occurrences.map { it.dueAtElapsedMillis })
        assertTrue(occurrences.all { it.type == ReminderType.HYDRATION })
    }

    @Test
    fun `NUTRITION solo existe cuando la sesion supera 60 minutos`() {
        assertEquals(emptyList<Any>(), ReminderSchedule.occurrencesWithin(NUTRITION, ReminderSetting(true, 45), minutes(70)))
        val occurrences = ReminderSchedule.occurrencesWithin(NUTRITION, ReminderSetting(true, 45), minutes(140))
        assertEquals(listOf(2, 3), occurrences.map { it.n })
        assertEquals(listOf(minutes(90), minutes(135)), occurrences.map { it.dueAtElapsedMillis })
    }

    @Test
    fun `con el minimo de 30 minutos la ocurrencia exacta de 60 no aplica y la primera es 90`() {
        val setting = ReminderSetting(true, 30)
        assertNull(ReminderSchedule.latestDue(NUTRITION, setting, minutes(60)))
        assertNull(ReminderSchedule.latestDue(NUTRITION, setting, minutes(89)))
        assertEquals(3, ReminderSchedule.latestDue(NUTRITION, setting, minutes(90))?.n)
        assertEquals(minutes(90), ReminderSchedule.nextDueAt(NUTRITION, setting, 0))
    }

    @Test
    fun `antes del primer intervalo no hay ocurrencia vencida y la siguiente es el primer intervalo`() {
        val setting = ReminderSetting(true, 10)
        assertNull(ReminderSchedule.latestDue(HYDRATION, setting, minutes(10) - 1))
        assertEquals(1, ReminderSchedule.latestDue(HYDRATION, setting, minutes(10))?.n)
        assertEquals(minutes(10), ReminderSchedule.nextDueAt(HYDRATION, setting, 0))
        assertEquals(minutes(20), ReminderSchedule.nextDueAt(HYDRATION, setting, minutes(10)))
    }

    @Test
    fun `tras una pausa larga solo la ocurrencia mas reciente esta vencida`() {
        val latest = ReminderSchedule.latestDue(HYDRATION, ReminderSetting(true, 15), minutes(52))
        assertEquals(3, latest?.n)
        assertEquals(minutes(45), latest?.dueAtElapsedMillis)
    }

    @Test
    fun `un tipo deshabilitado no genera ocurrencias (CA4)`() {
        val disabled = ReminderSetting(false, 15)
        assertNull(ReminderSchedule.latestDue(HYDRATION, disabled, minutes(120)))
        assertNull(ReminderSchedule.nextDueAt(HYDRATION, disabled, 0))
        assertTrue(ReminderSchedule.occurrencesWithin(HYDRATION, disabled, minutes(120)).isEmpty())
    }

    @Test
    fun `un intervalo fuera del rango de la regla no genera ocurrencias ni se corrige`() {
        listOf(9, 61).forEach { interval ->
            assertNull(ReminderSchedule.latestDue(HYDRATION, ReminderSetting(true, interval), minutes(200)))
        }
        listOf(29, 121).forEach { interval ->
            assertNull(ReminderSchedule.nextDueAt(NUTRITION, ReminderSetting(true, interval), 0))
        }
        assertEquals(1, ReminderSchedule.latestDue(HYDRATION, ReminderSetting(true, 60), minutes(60))?.n)
        assertEquals(1, ReminderSchedule.latestDue(NUTRITION, ReminderSetting(true, 120), minutes(120))?.n)
    }
}
