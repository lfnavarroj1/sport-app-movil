package com.lfnj.sport_app.features.status

import com.lfnj.sport_app.core.config.AppConfig
import com.lfnj.sport_app.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatusViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val config = AppConfig("http://bff/", "local", "0.3.0", "0.1.0")

    @Test
    fun `muestra carga y luego la disponibilidad del BFF`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = StatusViewModel(config) { BffAvailability.READY }

        assertTrue(viewModel.uiState.value.checking)
        assertNull(viewModel.uiState.value.availability)

        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.checking)
        assertEquals(BffAvailability.READY, viewModel.uiState.value.availability)
    }

    @Test
    fun `expone la configuracion tecnica de la variante`() = runTest(mainDispatcherRule.dispatcher) {
        val state = StatusViewModel(config) { BffAvailability.READY }.uiState.value

        assertEquals("0.1.0", state.appVersion)
        assertEquals("local", state.environment)
        assertEquals("0.3.0", state.contractVersion)
    }

    @Test
    fun `sin conexion permite reintentar y obtener el nuevo estado`() = runTest(mainDispatcherRule.dispatcher) {
        val answers = ArrayDeque(listOf(BffAvailability.NO_CONNECTION, BffAvailability.READY))
        val viewModel = StatusViewModel(config) { answers.removeFirst() }
        advanceUntilIdle()
        assertEquals(BffAvailability.NO_CONNECTION, viewModel.uiState.value.availability)

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(BffAvailability.READY, viewModel.uiState.value.availability)
    }

    @Test
    fun `ignora reintentos mientras hay una verificacion en curso`() = runTest(mainDispatcherRule.dispatcher) {
        var calls = 0
        val gate = CompletableDeferred<BffAvailability>()
        val viewModel = StatusViewModel(config) { calls++; gate.await() }
        advanceUntilIdle()

        viewModel.refresh()
        viewModel.refresh()
        gate.complete(BffAvailability.UNAVAILABLE)
        advanceUntilIdle()

        assertEquals(1, calls)
        assertEquals(BffAvailability.UNAVAILABLE, viewModel.uiState.value.availability)
    }
}
