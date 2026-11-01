package com.lfnj.sport_app.features.sensors.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** HU037: lectura de *Heart Rate Measurement* `0x2A37` y *Battery Level* `0x2A19` del perfil estándar. */
class HeartRateMeasurementParserTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `con la bandera 0 el valor es UINT8`() {
        assertEquals(72, HeartRateMeasurementParser.parse(bytes(0x00, 72)))
        assertEquals(255, HeartRateMeasurementParser.parse(bytes(0x00, 0xFF)))
    }

    @Test
    fun `con la bandera 1 el valor es UINT16 little-endian`() {
        assertEquals(300, HeartRateMeasurementParser.parse(bytes(0x01, 0x2C, 0x01)))
        assertEquals(80, HeartRateMeasurementParser.parse(bytes(0x01, 0x50, 0x00)))
    }

    @Test
    fun `las demas banderas y los campos opcionales no cambian el valor`() {
        // Contacto con la piel e intervalos RR presentes (bits 1, 2 y 4), valor UINT8.
        assertEquals(80, HeartRateMeasurementParser.parse(bytes(0x16, 80, 0x10, 0x03)))
        // Igual con valor UINT16.
        assertEquals(150, HeartRateMeasurementParser.parse(bytes(0x17, 150, 0x00, 0x10, 0x03)))
    }

    @Test
    fun `un paquete incompleto no produce medicion`() {
        assertNull(HeartRateMeasurementParser.parse(null))
        assertNull(HeartRateMeasurementParser.parse(ByteArray(0)))
        assertNull(HeartRateMeasurementParser.parse(bytes(0x00)))
        assertNull(HeartRateMeasurementParser.parse(bytes(0x01)))
        assertNull(HeartRateMeasurementParser.parse(bytes(0x01, 0x2C)))
    }

    @Test
    fun `un valor 0 sin contacto no produce medicion`() {
        assertNull(HeartRateMeasurementParser.parse(bytes(0x00, 0)))
        assertNull(HeartRateMeasurementParser.parse(bytes(0x01, 0, 0)))
    }

    @Test
    fun `la bateria se acepta de 0 a 100`() {
        assertEquals(0, HeartRateMeasurementParser.parseBattery(bytes(0)))
        assertEquals(57, HeartRateMeasurementParser.parseBattery(bytes(57)))
        assertEquals(100, HeartRateMeasurementParser.parseBattery(bytes(100)))
    }

    @Test
    fun `la bateria fuera de rango o ausente se ignora`() {
        assertNull(HeartRateMeasurementParser.parseBattery(bytes(101)))
        assertNull(HeartRateMeasurementParser.parseBattery(bytes(0xFF)))
        assertNull(HeartRateMeasurementParser.parseBattery(ByteArray(0)))
        assertNull(HeartRateMeasurementParser.parseBattery(null))
    }
}
