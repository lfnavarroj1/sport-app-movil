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
            val initial = manager.activeNetwork
            val state = DefaultNetworkState(
                network = initial,
                online = manager.getNetworkCapabilities(initial)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            )
            // El estado se deriva solo de los argumentos del callback: tras `onLost`, `activeNetwork`
            // puede seguir devolviendo la red perdida y el flujo abierto no emitiría «sin red».
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    trySend(state.onAvailable(network))
                }

                override fun onLost(network: Network) {
                    trySend(state.onLost(network))
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    trySend(state.onCapabilitiesChanged(network, capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)))
                }
            }
            trySend(state.online)
            manager.registerDefaultNetworkCallback(callback)
            awaitClose { manager.unregisterNetworkCallback(callback) }
        }.distinctUntilChanged()
    }
}

/**
 * Estado de la red por defecto según los eventos de `registerDefaultNetworkCallback`. Cada evento
 * devuelve el estado resultante; la pérdida de una red que ya no es la vigente no cambia nada.
 */
internal class DefaultNetworkState(private var network: Any?, online: Boolean) {
    var online: Boolean = online
        private set

    fun onAvailable(network: Any): Boolean {
        this.network = network
        online = true
        return online
    }

    fun onCapabilitiesChanged(network: Any, hasInternet: Boolean): Boolean {
        this.network = network
        online = hasInternet
        return online
    }

    fun onLost(network: Any): Boolean {
        if (this.network == null || this.network == network) {
            this.network = null
            online = false
        }
        return online
    }
}
