package com.lfnj.sport_app.features.sensors.ble

/**
 * Interpreta la característica *Heart Rate Measurement* `0x2A37` del perfil estándar: el bit 0 de
 * las banderas indica si el valor es `UINT8` (0) o `UINT16` en little-endian (1). Los campos
 * opcionales posteriores (energía gastada, intervalos RR) no se usan. Devuelve `null` para un
 * paquete incompleto o un valor 0 (sin contacto): nunca se fabrica una medición.
 */
object HeartRateMeasurementParser {
    private const val FLAG_UINT16 = 0x01

    fun parse(value: ByteArray?): Int? {
        if (value == null || value.isEmpty()) return null
        val flags = value[0].toInt() and 0xFF
        val bpm = if (flags and FLAG_UINT16 != 0) {
            if (value.size < 3) return null
            (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
        } else {
            if (value.size < 2) return null
            value[1].toInt() and 0xFF
        }
        return bpm.takeIf { it > 0 }
    }

    /** *Battery Level* `0x2A19`: un byte de 0 a 100; fuera de rango se ignora. */
    fun parseBattery(value: ByteArray?): Int? = value?.firstOrNull()?.toInt()?.and(0xFF)?.takeIf { it in 0..100 }
}
