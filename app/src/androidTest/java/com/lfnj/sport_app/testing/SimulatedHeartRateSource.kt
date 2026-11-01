package com.lfnj.sport_app.testing

import com.lfnj.sport_app.features.sensors.ble.BleAvailability
import com.lfnj.sport_app.features.sensors.ble.HeartRateMonitorSource
import com.lfnj.sport_app.features.sensors.ble.MonitorEvent
import com.lfnj.sport_app.features.sensors.model.DiscoveredMonitor
import com.lfnj.sport_app.features.sensors.model.HeartRateReading
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * **Fuente SIMULADA marcada como prueba** de monitores de frecuencia cardiaca (ADR-044 §4, riesgo
 * R-20). Declara [simulated] = `true`, por lo que la pantalla «Dispositivos de medición» muestra
 * siempre el aviso «Fuente simulada de prueba…» (`devices_simulated_source`). No usa Bluetooth: sus
 * lecturas son sintéticas y **no constituyen evidencia de un monitor BLE real**; la evidencia con un
 * monitor físico queda pendiente (R-20).
 *
 * Comportamiento configurable desde la prueba:
 * - [availability]: lo que devolvería el sistema (permisos, Bluetooth, versión de Android).
 * - [monitors]: monitores «anunciados» por el escaneo, con direcciones sintéticas.
 * - [outcome]: resultado de la conexión GATT (servicio `0x180D` presente, ausente, sin permiso o sin respuesta).
 * - [batteryPercent]: batería opcional (`null` = el monitor no expone `0x180F`).
 * - Lecturas a 1 Hz ([readingIntervalMillis], espera real) con el instante de recepción `Instant.now()`.
 * - [forceDisconnect]: simula la pérdida de la conexión (detección local de desconexión).
 */
class SimulatedHeartRateSource(
    @Volatile var availability: BleAvailability = BleAvailability.READY,
    @Volatile var monitors: List<DiscoveredMonitor> = listOf(DEFAULT_MONITOR),
    @Volatile var outcome: Outcome = Outcome.CONNECTED,
    @Volatile var batteryPercent: Int? = DEFAULT_BATTERY,
    @Volatile var bpm: Int = DEFAULT_BPM,
    @Volatile var readingIntervalMillis: Long = 1_000L,
    @Volatile var scanDelayMillis: Long = 300L,
) : HeartRateMonitorSource {

    /** Resultado de la conexión GATT simulada. */
    enum class Outcome { CONNECTED, MISSING_HEART_RATE_SERVICE, PERMISSION_REQUIRED, NO_RESPONSE }

    /** Siempre `true`: nunca se presenta como un monitor real. */
    override val simulated: Boolean = true

    /** Conexiones abiertas (verificación previa y enlace compartido). */
    val connectAttempts = AtomicInteger()

    private val disconnects = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    override fun availability(): BleAvailability = availability

    override fun scan(windowMillis: Long): Flow<DiscoveredMonitor> = flow {
        delay(minOf(scanDelayMillis, windowMillis))
        monitors.distinct().forEach { emit(it) }
    }

    override fun connect(address: String): Flow<MonitorEvent> = channelFlow {
        connectAttempts.incrementAndGet()
        send(MonitorEvent.Connecting)
        when (outcome) {
            Outcome.MISSING_HEART_RATE_SERVICE -> {
                send(MonitorEvent.MissingHeartRateService)
                return@channelFlow
            }
            Outcome.PERMISSION_REQUIRED -> {
                send(MonitorEvent.PermissionRequired)
                return@channelFlow
            }
            Outcome.NO_RESPONSE -> awaitCancellation()
            Outcome.CONNECTED -> Unit
        }
        send(MonitorEvent.Connected)
        batteryPercent?.let { send(MonitorEvent.Battery(it)) }
        val ticker = launch {
            var tick = 0
            while (true) {
                // Valores sintéticos alrededor de [bpm]; el instante es el de recepción, como en producción.
                send(MonitorEvent.HeartRate(HeartRateReading(bpm + tick % 3, Instant.now())))
                tick++
                delay(readingIntervalMillis)
            }
        }
        disconnects.first()
        ticker.cancel()
        send(MonitorEvent.Disconnected)
    }

    /** Corta las conexiones abiertas: emiten [MonitorEvent.Disconnected] y terminan. */
    fun forceDisconnect() {
        disconnects.tryEmit(Unit)
    }

    companion object {
        /** Dirección BLE sintética (no corresponde a ningún dispositivo real). */
        const val DEFAULT_ADDRESS = "00:11:22:33:44:55"
        const val DEFAULT_NAME = "Banda simulada HR"
        const val DEFAULT_BATTERY = 87
        const val DEFAULT_BPM = 128
        val DEFAULT_MONITOR = DiscoveredMonitor(DEFAULT_ADDRESS, DEFAULT_NAME)
    }
}
