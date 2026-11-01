package com.lfnj.sport_app.features.training.session.foreground

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** HU038: el wake lock parcial solo se mantiene con una sesión activa y siempre se libera. */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveSessionRunnerTest {
    private class FakeHandle : WakeLockHandle {
        override var held = false
        val timeouts = mutableListOf<Long>()
        var releases = 0
        override fun acquire(timeoutMillis: Long) {
            timeouts += timeoutMillis
            held = true
        }
        override fun release() {
            releases++
            held = false
        }
    }

    private val handle = FakeHandle()
    private val wakeLock = SessionWakeLock(handle)
    private val active = MutableStateFlow<ActiveSession?>(null)
    private val started = mutableListOf<String>()
    private val cancelled = mutableListOf<String>()
    private var ended = 0

    private val task = ActiveSessionTask { session ->
        started += session.sessionId
        try {
            awaitCancellation()
        } finally {
            cancelled += session.sessionId
        }
    }

    private fun session(id: String) = ActiveSession("owner", id, Instant.parse("2026-10-26T13:00:00Z"), null)

    @Test
    fun `con sesion activa toma el wake lock con tiempo maximo y lo renueva mientras dura`() = runTest {
        active.value = session("s-1")
        val job = launch { ActiveSessionRunner(listOf(task), wakeLock).follow(active, {}, { ended++ }) }
        testScheduler.runCurrent()

        assertTrue(handle.held)
        assertEquals(listOf(SessionWakeLock.TIMEOUT_MILLIS), handle.timeouts)
        assertEquals(listOf("s-1"), started)

        testScheduler.advanceTimeBy(SessionWakeLock.RENEW_MILLIS * 3)
        testScheduler.runCurrent()
        assertEquals(4, handle.timeouts.size)
        assertTrue(SessionWakeLock.RENEW_MILLIS < SessionWakeLock.TIMEOUT_MILLIS)
        job.cancel()
    }

    @Test
    fun `al terminar la sesion libera el wake lock, cancela las tareas y finaliza el servicio`() = runTest {
        active.value = session("s-1")
        val job = launch { ActiveSessionRunner(listOf(task), wakeLock).follow(active, {}, { ended++ }) }
        testScheduler.runCurrent()

        active.value = null
        testScheduler.runCurrent()

        assertFalse(handle.held)
        assertEquals(listOf("s-1"), cancelled)
        assertEquals(1, ended)
        val acquisitions = handle.timeouts.size
        testScheduler.advanceTimeBy(SessionWakeLock.RENEW_MILLIS * 2)
        testScheduler.runCurrent()
        assertEquals("sin sesión no se renueva", acquisitions, handle.timeouts.size)
        job.cancel()
    }

    @Test
    fun `al detener el servicio se libera aunque la sesion siga activa`() = runTest {
        active.value = session("s-1")
        val job = launch { ActiveSessionRunner(listOf(task), wakeLock).follow(active, {}, { ended++ }) }
        testScheduler.runCurrent()

        job.cancel()
        testScheduler.runCurrent()

        assertFalse(handle.held)
        assertEquals(listOf("s-1"), cancelled)
    }

    @Test
    fun `un cambio de sesion reinicia las tareas con la nueva`() = runTest {
        active.value = session("s-1")
        val job = launch { ActiveSessionRunner(listOf(task), wakeLock).follow(active, {}, { ended++ }) }
        testScheduler.runCurrent()

        active.value = session("s-2")
        testScheduler.runCurrent()

        assertEquals(listOf("s-1", "s-2"), started)
        assertEquals(listOf("s-1"), cancelled)
        assertTrue(handle.held)
        job.cancel()
    }

    @Test
    fun `una tarea que falla no detiene las demas ni el wake lock de la sesion`() = runTest {
        active.value = session("s-1")
        val failing = ActiveSessionTask { error("falla") }
        val job = launch { ActiveSessionRunner(listOf(failing, task), wakeLock).follow(active, {}, { ended++ }) }
        testScheduler.runCurrent()

        assertEquals(listOf("s-1"), started)
        assertTrue(handle.held)
        job.cancel()
    }

    @Test
    fun `sin sesion al arrancar finaliza sin tomar el wake lock`() = runTest {
        val job = launch { ActiveSessionRunner(listOf(task), wakeLock).follow(active, {}, { ended++ }) }
        testScheduler.runCurrent()

        assertEquals(1, ended)
        assertTrue(handle.timeouts.isEmpty())
        job.cancel()
    }
}
