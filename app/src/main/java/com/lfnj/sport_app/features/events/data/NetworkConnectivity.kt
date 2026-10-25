package com.lfnj.sport_app.features.events.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/**
 * Conectividad observada por la pantalla de detalle de HU041: sin red el botón «Inscribirme» se
 * deshabilita con explicación (CA10). Solo orienta la experiencia; la falta real de conexión
 * también se detecta al enviar.
 */
fun interface ConnectivityMonitor {
    fun online(): Flow<Boolean>
}

/** Monitor sobre la red por defecto del sistema (`ACCESS_NETWORK_STATE`, ya declarado por WorkManager). */
class AndroidConnectivityMonitor(context: Context) : ConnectivityMonitor {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun online(): Flow<Boolean> {
        val manager = manager ?: return flowOf(true)
        return callbackFlow {
            fun current(): Boolean = manager.getNetworkCapabilities(manager.activeNetwork)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    trySend(true)
                }

                override fun onLost(network: Network) {
                    trySend(current())
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    trySend(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                }
            }
            trySend(current())
            manager.registerDefaultNetworkCallback(callback)
            awaitClose { manager.unregisterNetworkCallback(callback) }
        }.distinctUntilChanged()
    }
}
