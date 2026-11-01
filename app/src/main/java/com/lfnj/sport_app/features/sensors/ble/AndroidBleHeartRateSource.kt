package com.lfnj.sport_app.features.sensors.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.lfnj.sport_app.features.sensors.model.DiscoveredMonitor
import com.lfnj.sport_app.features.sensors.model.HeartRateReading
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.time.Clock

/** Permisos de Android 12+ que exige HU037 (ADR-044 §2): `BLUETOOTH_SCAN` con `neverForLocation` y `BLUETOOTH_CONNECT`. */
object BluetoothPermissions {
    /** Android 9–11 queda fuera mientras el responsable no defina sus permisos (ubicación); aislado aquí. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val required: Array<String>
        get() = if (supported) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()

    fun granted(context: Context): Boolean =
        supported && required.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
}

/**
 * Monitores reales por Bluetooth LE con el *Heart Rate Service* estándar, sin SDK de fabricante.
 * Cada operación comprueba los permisos antes de llamar a la pila Bluetooth y traduce cualquier
 * `SecurityException` a un evento: nunca registra direcciones, nombres ni valores.
 */
@SuppressLint("MissingPermission")
class AndroidBleHeartRateSource(context: Context, private val clock: Clock = Clock.systemUTC()) : HeartRateMonitorSource {
    private val context = context.applicationContext
    private val manager: BluetoothManager? = this.context.getSystemService(BluetoothManager::class.java)

    override val simulated: Boolean = false

    override fun availability(): BleAvailability = when {
        !BluetoothPermissions.supported -> BleAvailability.UNSUPPORTED_ANDROID_VERSION
        !context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) || manager?.adapter == null -> BleAvailability.NO_BLUETOOTH_LE
        !BluetoothPermissions.granted(context) -> BleAvailability.PERMISSION_REQUIRED
        manager?.adapter?.isEnabled != true -> BleAvailability.BLUETOOTH_OFF
        else -> BleAvailability.READY
    }

    override fun scan(windowMillis: Long): Flow<DiscoveredMonitor> = callbackFlow {
        val scanner = manager?.adapter?.bluetoothLeScanner
        if (availability() != BleAvailability.READY || scanner == null) {
            close()
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(DiscoveredMonitor(result.device.address, safeName(result)))
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { trySend(DiscoveredMonitor(it.device.address, safeName(it))) }
            }

            override fun onScanFailed(errorCode: Int) {
                close()
            }
        }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(GattUuids.HEART_RATE_SERVICE)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(filters, settings, callback)
        } catch (_: SecurityException) {
            close()
        }
        launch {
            delay(windowMillis)
            close()
        }
        awaitClose {
            try {
                scanner.stopScan(callback)
            } catch (_: SecurityException) {
                // Permiso revocado durante el escaneo: el sistema ya lo detuvo.
            } catch (_: IllegalStateException) {
                // Bluetooth apagado durante el escaneo.
            }
        }
    }.distinctUntilChanged()

    override fun connect(address: String): Flow<MonitorEvent> = callbackFlow {
        when (availability()) {
            BleAvailability.READY -> Unit
            BleAvailability.PERMISSION_REQUIRED, BleAvailability.UNSUPPORTED_ANDROID_VERSION -> {
                send(MonitorEvent.PermissionRequired)
                close()
                return@callbackFlow
            }
            else -> {
                send(MonitorEvent.Unavailable)
                close()
                return@callbackFlow
            }
        }
        val device: BluetoothDevice = try {
            checkNotNull(manager?.adapter).getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            send(MonitorEvent.Unavailable)
            close()
            return@callbackFlow
        }
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    guarded { if (!gatt.discoverServices()) disconnected() }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    disconnected()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val measurement = gatt.getService(GattUuids.HEART_RATE_SERVICE)?.getCharacteristic(GattUuids.HEART_RATE_MEASUREMENT)
                if (status != BluetoothGatt.GATT_SUCCESS || measurement == null) {
                    trySend(MonitorEvent.MissingHeartRateService)
                    close()
                    return
                }
                guarded {
                    gatt.setCharacteristicNotification(measurement, true)
                    val config = measurement.getDescriptor(GattUuids.CLIENT_CONFIG)
                    if (config == null) {
                        trySend(MonitorEvent.MissingHeartRateService)
                        close()
                    } else {
                        writeDescriptor(gatt, config, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    }
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    disconnected()
                    return
                }
                trySend(MonitorEvent.Connected)
                // La batería es opcional: solo se lee si el monitor expone 0x180F/0x2A19.
                gatt.getService(GattUuids.BATTERY_SERVICE)?.getCharacteristic(GattUuids.BATTERY_LEVEL)?.let { battery ->
                    guarded { gatt.readCharacteristic(battery) }
                }
            }

            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) onValue(characteristic, value)
            }

            @Deprecated("Android 12 y anteriores entregan el valor en la característica")
            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                @Suppress("DEPRECATION")
                if (status == BluetoothGatt.GATT_SUCCESS && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) onValue(characteristic, characteristic.value)
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                onValue(characteristic, value)
            }

            @Deprecated("Android 12 y anteriores entregan el valor en la característica")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) onValue(characteristic, characteristic.value)
            }

            private fun onValue(characteristic: BluetoothGattCharacteristic, value: ByteArray?) {
                when (characteristic.uuid) {
                    GattUuids.HEART_RATE_MEASUREMENT -> HeartRateMeasurementParser.parse(value)?.let { bpm ->
                        trySend(MonitorEvent.HeartRate(HeartRateReading(bpm, clock.instant())))
                    }
                    GattUuids.BATTERY_LEVEL -> HeartRateMeasurementParser.parseBattery(value)?.let { trySend(MonitorEvent.Battery(it)) }
                }
            }

            private fun disconnected() {
                trySend(MonitorEvent.Disconnected)
                close()
            }

            private inline fun guarded(block: () -> Unit) {
                try {
                    block()
                } catch (_: SecurityException) {
                    trySend(MonitorEvent.PermissionRequired)
                    close()
                }
            }
        }
        send(MonitorEvent.Connecting)
        val gatt: BluetoothGatt? = try {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: SecurityException) {
            send(MonitorEvent.PermissionRequired)
            null
        }
        if (gatt == null) close()
        awaitClose {
            try {
                gatt?.disconnect()
                gatt?.close()
            } catch (_: SecurityException) {
                // Permiso revocado: el sistema ya cerró la conexión.
            }
        }
    }

    private fun writeDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun safeName(result: ScanResult): String? = try {
        result.scanRecord?.deviceName ?: result.device.name
    } catch (_: SecurityException) {
        null
    }
}
