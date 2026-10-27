package com.lfnj.sport_app.features.events.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Monitor real de HU041 (CA10) con la red real del dispositivo: un único flujo abierto debe pasar de
 * «en línea» a «sin red» al cortar Wi-Fi y datos, y volver a «en línea» al restaurarlos. La red se
 * restaura siempre.
 */
@RunWith(AndroidJUnit4::class)
class AndroidConnectivityMonitorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun elFlujoAbiertoEmiteSinRedAlCortarLaRedRealYEnLineaAlRestaurarla() {
        val monitor = AndroidConnectivityMonitor(context)
        val scope = CoroutineScope(Dispatchers.Default)
        val latest = MutableStateFlow<Boolean?>(null)
        try {
            monitor.online().onEach { latest.value = it }.launchIn(scope)
            runBlocking { withTimeout(TIMEOUT) { latest.first { it != null } } }
            assumeTrue("El dispositivo debe empezar con red", latest.value == true)

            try {
                shell("svc wifi disable")
                shell("svc data disable")
                runBlocking { withTimeout(TIMEOUT) { latest.first { it == false } } }
            } finally {
                shell("svc wifi enable")
                shell("svc data enable")
            }
            runBlocking { withTimeout(RESTORE_TIMEOUT) { latest.first { it == true } } }
        } finally {
            scope.cancel()
        }
    }

    /** Ejecuta un comando de shell del dispositivo con los permisos de la instrumentación y espera su fin. */
    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
        }
    }

    private companion object {
        const val TIMEOUT = 20_000L
        const val RESTORE_TIMEOUT = 60_000L
    }
}
