package com.lfnj.sport_app.features.events.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Estado de la red por defecto del monitor de HU041 (CA10): con el flujo abierto, perder la red
 * vigente debe emitir «sin red» sin volver a consultar `activeNetwork`, que en ese instante todavía
 * puede devolver la red perdida.
 */
class DefaultNetworkStateTest {
    private val wifi = Any()
    private val cellular = Any()

    @Test
    fun enLineaPierdeLaRedVigenteYPasaASinRedYAlVolverQuedaEnLinea() {
        val state = DefaultNetworkState(network = wifi, online = true)

        assertFalse(state.onLost(wifi))
        assertFalse(state.online)
        assertTrue(state.onAvailable(wifi))
        assertTrue(state.onCapabilitiesChanged(wifi, hasInternet = true))
    }

    @Test
    fun perderUnaRedQueYaNoEsLaVigenteNoCambiaElEstado() {
        val state = DefaultNetworkState(network = wifi, online = true)

        assertTrue(state.onAvailable(cellular))
        assertTrue(state.onLost(wifi))
        assertFalse(state.onLost(cellular))
    }

    @Test
    fun capacidadesSinInternetDejanSinRed() {
        val state = DefaultNetworkState(network = wifi, online = true)

        assertFalse(state.onCapabilitiesChanged(wifi, hasInternet = false))
        assertTrue(state.onCapabilitiesChanged(wifi, hasInternet = true))
    }

    @Test
    fun sinRedInicialSigueSinRedHastaQueAparezcaUnaRed() {
        val state = DefaultNetworkState(network = null, online = false)

        assertFalse(state.onLost(wifi))
        assertTrue(state.onAvailable(wifi))
    }
}
