package com.lfnj.sport_app.features.training.metrics.model

import com.lfnj.sport_app.core.session.AccessSnapshot
import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.SubscriptionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.Locale

/** HU034: validación de experiencia, vigencia del catálogo, formato y orden (ADR-030). */
class TrainingMetricsModelsTest {
    private val power = MetricInputDefinition("mean_power_watts", "training_metric.input.mean_power_watts", "INTEGER", "W", 1, 2500, 0, true)
    private val now = Instant.parse("2026-10-12T15:35:00Z")

    @Test
    fun `CA3 la validacion local usa los limites del catalogo con extremos incluidos`() {
        assertEquals(MetricInputValidation.Result.Valid(1), MetricInputValidation.validate(power, "1"))
        assertEquals(MetricInputValidation.Result.Valid(2500), MetricInputValidation.validate(power, " 2500 "))
        assertEquals(MetricInputValidation.Result.Invalid(InputError.OUT_OF_RANGE), MetricInputValidation.validate(power, "0"))
        assertEquals(MetricInputValidation.Result.Invalid(InputError.OUT_OF_RANGE), MetricInputValidation.validate(power, "2501"))
    }

    @Test
    fun `CA3 una entrada obligatoria vacia o no entera se rechaza antes de enviar`() {
        assertEquals(MetricInputValidation.Result.Invalid(InputError.REQUIRED), MetricInputValidation.validate(power, ""))
        assertEquals(MetricInputValidation.Result.Invalid(InputError.NOT_INTEGER), MetricInputValidation.validate(power, "250.5"))
        assertEquals(MetricInputValidation.Result.Invalid(InputError.NOT_INTEGER), MetricInputValidation.validate(power, "-3"))
        assertEquals(MetricInputValidation.Result.Invalid(InputError.NOT_INTEGER), MetricInputValidation.validate(power, "9999999999"))
        assertEquals(MetricInputValidation.Result.Empty, MetricInputValidation.validate(power.copy(required = false), ""))
    }

    @Test
    fun `CA3 measured_at admite 5 minutos en el futuro y 365 dias de antiguedad`() {
        assertNull(MetricInputValidation.validateMeasuredAt(now.plusSeconds(300), now))
        assertEquals(MeasuredAtError.IN_FUTURE, MetricInputValidation.validateMeasuredAt(now.plusSeconds(301), now))
        assertNull(MetricInputValidation.validateMeasuredAt(now.minusSeconds(365L * 86_400), now))
        assertEquals(MeasuredAtError.TOO_OLD, MetricInputValidation.validateMeasuredAt(now.minusSeconds(365L * 86_400 + 1), now))
    }

    @Test
    fun `CA9 el catalogo pasa a STALE despues de 24 horas`() {
        val catalog = MetricCatalog("v1", emptyList(), null, syncedAt = now)

        assertFalse(catalog.isStale(now.plusSeconds(24 * 3600)))
        assertTrue(catalog.isStale(now.plusSeconds(24 * 3600 + 1)))
    }

    @Test
    fun `CA7 un metodo solo de enteros es capturable sin importar su indicador`() {
        val method = MetricMethod("ANY_NEW", "k", "pts", 2, "ANY_V9", 9, "m", "estimated_not_diagnostic", listOf(power.copy(code = "x", unit = "rep")))

        assertTrue(method.supported)
        assertEquals("ANY_V9@9", method.id)
        assertFalse(method.copy(inputs = listOf(power.copy(valueType = "DECIMAL"))).supported)
        assertFalse(method.copy(inputs = emptyList()).supported)
    }

    @Test
    fun `CA1 y CA2 el valor confirmado se muestra con su escala y el formato regional sin redondear`() {
        val es = Locale.forLanguageTag("es-CO")

        assertEquals("238", MetricFormat.value(BigDecimal("238"), 0, es))
        assertEquals("42,4", MetricFormat.value(BigDecimal("42.4"), 1, es))
        assertEquals("42,0", MetricFormat.value(BigDecimal("42"), 1, es))
        assertEquals("2.400", MetricFormat.value(BigDecimal("2400"), 0, es))
        assertEquals("Nunca se redondea un valor del backend", "42,45", MetricFormat.value(BigDecimal("42.45"), 1, es))
    }

    @Test
    fun `CA5 el historial combina proyeccion y confirmados recientes sin duplicar y en el orden del contrato`() {
        fun result(id: String, measuredAt: String) = MetricResult(
            id, "FTP_ESTIMATED", "FTP_20_MIN_95_V1", 1, Instant.parse(measuredAt), Instant.parse(measuredAt),
            emptyList(), BigDecimal.ONE, "W", 0, "estimated_not_diagnostic",
        )
        val cache = MetricResultsCache(
            projected = listOf(result("b", "2026-10-10T10:00:00Z"), result("a", "2026-10-10T10:00:00Z")),
            awaiting = listOf(result("c", "2026-10-12T10:00:00Z"), result("a", "2026-10-10T10:00:00Z")),
            nextCursor = null,
            syncedAt = null,
        )

        assertEquals(listOf("c", "b", "a"), cache.items.map { it.calculationId })
    }

    @Test
    fun `la guarda visual exige deportista activo con la capacidad`() {
        fun access(status: SubscriptionStatus, caps: Set<String>) = AccessSnapshot(ActorType.ATHLETE, status, "FREE", emptySet(), caps)

        assertTrue(TrainingMetricsAccess.canUse(access(SubscriptionStatus.ACTIVE, setOf(TRAINING_METRICS_CAPABILITY))))
        assertFalse(TrainingMetricsAccess.canUse(access(SubscriptionStatus.ACTIVE, emptySet())))
        assertFalse(TrainingMetricsAccess.canUse(access(SubscriptionStatus.PROVISIONING, setOf(TRAINING_METRICS_CAPABILITY))))
        assertFalse(TrainingMetricsAccess.canUse(null))
    }

    @Test
    fun `los modelos con datos sensibles no los exponen al convertirse en texto`() {
        val input = MetricInputValue("mean_power_watts", 250, "W")
        val request = CalculationRequest("id", "FTP_ESTIMATED", "FTP_20_MIN_95_V1", 1, now, listOf(input))

        listOf(input.toString(), request.toString()).forEach { text -> assertFalse(text, "250" in text) }
    }
}
