package com.lfnj.sport_app.features.sensors.ble

import com.lfnj.sport_app.features.sensors.model.DiscoveredMonitor
import com.lfnj.sport_app.features.sensors.model.HeartRateReading
import com.lfnj.sport_app.features.sensors.model.MonitorConnection
import com.lfnj.sport_app.features.sensors.model.MonitorSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.util.UUID

/** UUID de 16 bits del *Bluetooth SIG* expresados sobre la base estándar. */
object GattUuids {
    private fun of(short: Int): UUID = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(short))

    /** *Heart Rate Service* (ADR-044 §1). */
    val HEART_RATE_SERVICE: UUID = of(0x180D)

    /** *Heart Rate Measurement*. */
    val HEART_RATE_MEASUREMENT: UUID = of(0x2A37)

    /** *Battery Service* y *Battery Level*, opcionales: solo se muestran si el monitor los expone. */
    val BATTERY_SERVICE: UUID = of(0x180F)
    val BATTERY_LEVEL: UUID = of(0x2A19)

    /** *Client Characteristic Configuration*, para activar las notificaciones. */
    val CLIENT_CONFIG: UUID = of(0x2902)
}

/** Por qué no puede usarse Bluetooth ahora; [READY] permite escanear y conectar. */
enum class BleAvailability {
    READY,
    /** Android 9–11: el paquete solo define los permisos de Android 12+ (decisión pendiente, sin pedir ubicación). */
    UNSUPPORTED_ANDROID_VERSION,
    NO_BLUETOOTH_LE,
    BLUETOOTH_OFF,
    PERMISSION_REQUIRED,
}

/** Evento de una conexión GATT. */
sealed interface MonitorEvent {
    data object Connecting : MonitorEvent
    /** Servicios descubiertos con el *Heart Rate Service* y notificaciones de `0x2A37` activadas. */
    data object Connected : MonitorEvent
    data object Disconnected : MonitorEvent
    /** El dispositivo no expone `0x180D`: no se vincula (CA2). */
    data object MissingHeartRateService : MonitorEvent
    data object PermissionRequired : MonitorEvent
    data object Unavailable : MonitorEvent
    data class Battery(val percent: Int) : MonitorEvent
    data class HeartRate(val reading: HeartRateReading) : MonitorEvent
}

/**
 * Fuente de monitores de frecuencia cardiaca. Producción usa [AndroidBleHeartRateSource]; las pruebas
 * automatizadas usan una fuente simulada que DEBE declarar [simulated] = `true` y se presenta en
 * pantalla como «fuente simulada de prueba» (ADR-044 §4, R-20).
 */
interface HeartRateMonitorSource {
    val simulated: Boolean

    fun availability(): BleAvailability

    /** Monitores que anuncian el servicio `0x180D`, sin repetir; termina tras [windowMillis]. */
    fun scan(windowMillis: Long): Flow<DiscoveredMonitor>

    /** Conexión al monitor de [address]; termina tras [MonitorEvent.Disconnected] o un error terminal. */
    fun connect(address: String): Flow<MonitorEvent>
}

/** Acumula los eventos en el estado visible de la conexión. */
fun MonitorSnapshot.reduce(event: MonitorEvent): MonitorSnapshot = when (event) {
    MonitorEvent.Connecting -> copy(connection = MonitorConnection.CONNECTING)
    MonitorEvent.Connected -> copy(connection = MonitorConnection.CONNECTED)
    MonitorEvent.Disconnected -> copy(connection = MonitorConnection.DISCONNECTED)
    MonitorEvent.MissingHeartRateService -> copy(connection = MonitorConnection.MISSING_HEART_RATE_SERVICE)
    MonitorEvent.PermissionRequired -> copy(connection = MonitorConnection.PERMISSION_REQUIRED)
    MonitorEvent.Unavailable -> copy(connection = MonitorConnection.UNAVAILABLE)
    is MonitorEvent.Battery -> copy(batteryPercent = event.percent)
    is MonitorEvent.HeartRate -> copy(connection = MonitorConnection.CONNECTED, lastReading = event.reading)
}

/**
 * Conexión compartida con el monitor activo: la pantalla y la ingestión de la sesión observan el
 * mismo enlace GATT, que existe solo mientras alguien lo observa. Una desconexión se detecta
 * localmente y se reintenta cada [reconnectDelayMillis]; un error terminal (sin servicio `0x180D`,
 * sin permiso) no se reintenta.
 */
class MonitorLink(
    private val source: HeartRateMonitorSource,
    private val scope: CoroutineScope,
    private val reconnectDelayMillis: Long = RECONNECT_DELAY_MILLIS,
) {
    private val links = mutableMapOf<String, StateFlow<MonitorSnapshot>>()

    val simulated: Boolean get() = source.simulated

    fun availability(): BleAvailability = source.availability()

    fun scan(windowMillis: Long = SCAN_WINDOW_MILLIS): Flow<DiscoveredMonitor> = source.scan(windowMillis)

    /** Conexión de un solo uso para verificar el servicio `0x180D` antes de vincular (CA2). */
    fun probe(address: String): Flow<MonitorEvent> = source.connect(address)

    fun observe(address: String): StateFlow<MonitorSnapshot> = synchronized(links) {
        links.getOrPut(address) {
            flow {
                var snapshot = MonitorSnapshot(MonitorConnection.CONNECTING)
                emit(snapshot)
                while (true) {
                    source.connect(address).collect { event ->
                        snapshot = snapshot.reduce(event)
                        emit(snapshot)
                    }
                    if (snapshot.connection in TERMINAL) return@flow
                    if (snapshot.connection != MonitorConnection.DISCONNECTED) {
                        snapshot = snapshot.reduce(MonitorEvent.Disconnected)
                        emit(snapshot)
                    }
                    delay(reconnectDelayMillis)
                    snapshot = snapshot.reduce(MonitorEvent.Connecting)
                    emit(snapshot)
                }
            }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS, 0), MonitorSnapshot(MonitorConnection.CONNECTING))
        }
    }

    companion object {
        const val SCAN_WINDOW_MILLIS = 10_000L
        const val RECONNECT_DELAY_MILLIS = 5_000L
        private const val STOP_TIMEOUT_MILLIS = 3_000L
        private val TERMINAL = setOf(MonitorConnection.MISSING_HEART_RATE_SERVICE, MonitorConnection.PERMISSION_REQUIRED)
    }
}
