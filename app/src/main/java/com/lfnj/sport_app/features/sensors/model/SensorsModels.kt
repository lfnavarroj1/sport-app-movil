package com.lfnj.sport_app.features.sensors.model

import com.lfnj.sport_app.core.session.AccessSnapshot
import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.SubscriptionStatus
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

/** Capacidad `INTERMEDIATE` de la opción 220 «Dispositivos de medición» (HU037, ADR-044 §12). */
const val DEVICES_CAPABILITY = "devices.measurement.connect"

/** Capacidad que exige la ingestión de mediciones (`training.realtime.monitor`, ADR-044 §5). */
const val REALTIME_MONITOR_CAPABILITY = "training.realtime.monitor"

/** Único tipo y protocolo admitidos en el MVP (ADR-044 §1). */
const val DEVICE_TYPE_HEART_RATE_MONITOR = "HEART_RATE_MONITOR"
const val PROTOCOL_BLE_HRS = "BLE_HRS"

/** Estado del dispositivo en `athlete_device` (`DeviceResponse.status`). */
enum class DeviceStatus(val wire: String) {
    ACTIVE("ACTIVE"),
    REVOKED("REVOKED");

    companion object {
        fun fromWire(value: String): DeviceStatus? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Dispositivo del deportista confirmado por el backend. [hasLocalAddress] indica si este teléfono
 * conserva (cifrada) la dirección BLE con la que se vinculó; sin ella no puede conectarse y debe
 * vincularse de nuevo desde este teléfono. La dirección nunca se expone fuera del almacenamiento.
 */
data class LinkedDevice(
    val deviceId: String,
    val displayName: String,
    val status: DeviceStatus,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val revokedAt: Instant?,
    val hasLocalAddress: Boolean,
) {
    val active: Boolean get() = status == DeviceStatus.ACTIVE
    val connectable: Boolean get() = active && hasLocalAddress
}

/** Monitor descubierto por el escaneo filtrado por el *Heart Rate Service* `0x180D`. */
class DiscoveredMonitor(val address: String, val advertisedName: String?) {
    override fun equals(other: Any?): Boolean = other is DiscoveredMonitor && other.address == address
    override fun hashCode(): Int = address.hashCode()

    /** Sin la dirección BLE: nunca debe llegar a registros ni mensajes. */
    override fun toString(): String = "DiscoveredMonitor(advertisedName=$advertisedName)"
}

/** Lectura de la característica *Heart Rate Measurement* `0x2A37`, con el instante de recepción. */
data class HeartRateReading(val bpm: Int, val receivedAt: Instant)

/** Estado de la conexión GATT con el monitor (detección local de desconexión, HU037). */
enum class MonitorConnection { CONNECTING, CONNECTED, DISCONNECTED, MISSING_HEART_RATE_SERVICE, PERMISSION_REQUIRED, UNAVAILABLE }

/**
 * Vista acumulada de la conexión: estado, batería si el monitor la expone (`0x180F`/`0x2A19`) y la
 * última lectura. [lastReading] no se presenta como actual cuando la conexión no está `CONNECTED`.
 */
data class MonitorSnapshot(
    val connection: MonitorConnection,
    val batteryPercent: Int? = null,
    val lastReading: HeartRateReading? = null,
)

/** Resultado del último lote enviado (CA3–CA5): aceptadas, duplicadas y rechazadas por el backend. */
data class BatchReport(val sentAt: Instant, val received: Int, val accepted: Int, val duplicates: Int, val rejected: Int)

/** Situación de la ingestión de la sesión activa, visible para el deportista. */
enum class IngestionState {
    /** Sin sesión activa o sin monitor vinculado en este teléfono. */
    IDLE,
    /** Lotes confirmados por el backend. */
    SENDING,
    /** Sin conexión o servicio no disponible: las muestras quedan cifradas y se reenvían en el siguiente ciclo. */
    PENDING_RETRY,
    /** `409 device_revoked`: el backend dejó de aceptar este dispositivo (CA6). */
    DEVICE_REVOKED,
    /** `409 training_session_already_finished`. */
    SESSION_FINISHED,
    /** `403 capability_required` (`training.realtime.monitor`). */
    CAPABILITY_REQUIRED,
    /** `404 device_not_found` o un lote que el backend no puede validar. */
    REJECTED,
}

data class IngestionStatus(
    val state: IngestionState = IngestionState.IDLE,
    val pendingSamples: Int = 0,
    val lastBatch: BatchReport? = null,
)

/** La opción se muestra y se usa solo con la capacidad vigente (HU003 CA7); el BFF decide al final. */
object DevicesAccess {
    fun canUse(access: AccessSnapshot?): Boolean =
        access != null &&
            access.actorType == ActorType.ATHLETE &&
            access.subscriptionStatus == SubscriptionStatus.ACTIVE &&
            DEVICES_CAPABILITY in access.capabilities
}

/** Reglas del cliente que derivan del contrato `RegisterDeviceRequest`. */
object DeviceRules {
    /** `display_name` de 1 a 60 caracteres (ADR-044 §3). */
    const val MAX_NAME_LENGTH = 60

    /** Nombre recortado y válido, o `null` si queda vacío o supera el máximo. */
    fun normalizeName(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() && it.length <= MAX_NAME_LENGTH }

    /** Nombre propuesto a partir del anuncio BLE, ajustado al máximo del contrato. */
    fun suggestedName(monitor: DiscoveredMonitor): String = monitor.advertisedName?.trim()?.take(MAX_NAME_LENGTH).orEmpty()
}

/**
 * Identidad lógica del dispositivo (ADR-044 §2): SHA-256, en hexadecimal en minúsculas, de la
 * dirección BLE en mayúsculas concatenada con el `user_id`. La dirección no se envía ni se guarda en
 * claro; el mismo monitor tiene identidades distintas para deportistas distintos.
 */
object DeviceIdHasher {
    fun deviceId(bleAddress: String, userId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest((bleAddress.trim().uppercase(Locale.ROOT) + userId).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
