package com.lfnj.sport_app.features.training.metrics.model

import com.lfnj.sport_app.core.session.AccessSnapshot
import com.lfnj.sport_app.core.session.SubscriptionStatus
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.util.Locale

/** Capacidad del plan que habilita `/entrenamientos/metricas` (HU034, ADR-030 §1). */
const val TRAINING_METRICS_CAPABILITY = "training.metrics.ftp_vo2max"

/** Guarda visual de la ruta; la autorización la repiten el BFF y el servicio. */
object TrainingMetricsAccess {
    fun canUse(access: AccessSnapshot?): Boolean =
        access != null && access.subscriptionStatus == SubscriptionStatus.ACTIVE && TRAINING_METRICS_CAPABILITY in access.capabilities
}

/**
 * Entrada numérica escalar de un método del catálogo (ADR-030, «Catálogo extensible»). El
 * formulario se construye solo con estos metadatos: no hay lógica por indicador en el cliente.
 */
data class MetricInputDefinition(
    val code: String,
    val labelKey: String,
    val valueType: String,
    val unit: String,
    val minimum: Long,
    val maximum: Long,
    val scale: Int,
    val required: Boolean,
)

/** Método versionado e inmutable del catálogo confirmado por el backend. */
data class MetricMethod(
    val metricCode: String,
    val labelKey: String,
    val resultUnit: String,
    val resultScale: Int,
    val methodCode: String,
    val methodVersion: Int,
    val methodLabelKey: String,
    val disclaimerKey: String,
    val inputs: List<MetricInputDefinition>,
) {
    /** Identifica el método dentro del catálogo (código y versión). */
    val id: String get() = "$methodCode@$methodVersion"

    /** El cliente solo sabe capturar enteros (`INTEGER`, la única variante del contrato). */
    val supported: Boolean get() = inputs.isNotEmpty() && inputs.all { it.valueType == VALUE_TYPE_INTEGER && it.scale == 0 }

    companion object {
        const val VALUE_TYPE_INTEGER = "INTEGER"
    }
}

/** Último catálogo confirmado por el BFF, con su `ETag` y la fecha de la última confirmación. */
data class MetricCatalog(
    val catalogVersion: String,
    val methods: List<MetricMethod>,
    val etag: String?,
    val syncedAt: Instant,
) {
    /** `STALE` a las 24 horas de la última confirmación (ADR-030, política offline §1). */
    fun isStale(now: Instant): Boolean = Duration.between(syncedAt, now) > STALE_AFTER

    fun method(id: String): MetricMethod? = methods.firstOrNull { it.id == id }

    companion object {
        val STALE_AFTER: Duration = Duration.ofHours(24)
    }
}

/** Valor de una entrada enviada o confirmada. */
data class MetricInputValue(val code: String, val value: Long, val unit: String) {
    override fun toString(): String = "MetricInputValue(code=$code)"
}

/** Resultado confirmado por el backend (`CONFIRMED`): siempre una estimación, nunca un diagnóstico. */
data class MetricResult(
    val calculationId: String,
    val metricCode: String,
    val methodCode: String,
    val methodVersion: Int,
    val measuredAt: Instant,
    val calculatedAt: Instant,
    val inputs: List<MetricInputValue>,
    val value: BigDecimal,
    val unit: String,
    val scale: Int,
    val disclaimerKey: String,
) {
    override fun toString(): String = "MetricResult(metricCode=$metricCode, methodCode=$methodCode)"
}

/**
 * Historial confirmado en el dispositivo: la primera página de la proyección ([projected]) y los
 * resultados que el comando ya confirmó pero la proyección aún no devuelve ([awaiting]), porque la
 * lectura es eventual (ADR-030 §9). [items] combina ambos sin duplicados en el orden del contrato.
 */
data class MetricResultsCache(
    val projected: List<MetricResult>,
    val awaiting: List<MetricResult>,
    val nextCursor: String?,
    /** Última consulta confirmada del historial (`last_synced_at`); `null` si nunca se consultó. */
    val syncedAt: Instant?,
) {
    val items: List<MetricResult>
        get() = (awaiting.filter { a -> projected.none { it.calculationId == a.calculationId } } + projected).sortedWith(ORDER)

    companion object {
        /** `measured_at DESC, calculation_id DESC` (ADR-030, «Consultar resultados»). */
        val ORDER: Comparator<MetricResult> =
            compareByDescending<MetricResult> { it.measuredAt }.thenByDescending { it.calculationId }

        val EMPTY = MetricResultsCache(emptyList(), emptyList(), null, null)
    }
}

/**
 * Estado de un cálculo capturado en el dispositivo (ADR-030, política offline). Solo un resultado
 * del historial (`SYNCED`) representa confirmación del backend.
 */
enum class MetricSyncState { PENDING_SYNC, SYNC_FAILED }

/** Causa explicable de [MetricSyncState.SYNC_FAILED]. Se guarda como código técnico, sin valores. */
enum class MetricSyncFailure {
    /** `422 validation_error`: el campo y la causa vienen en el cálculo. */
    VALIDATION,
    /** `403 capability_required`: el plan ya no incluye `training.metrics.ftp_vo2max`. */
    CAPABILITY_REQUIRED,
    /** `403 permission_required`: la cuenta no es de deportista. */
    PERMISSION_REQUIRED,
    /** `409 idempotency_key_reused` o `calculation_id_reused`. */
    CONFLICT,
    /** Vencimiento local a los 30 días (ADR-030, política offline §4). */
    EXPIRED,
    /** Otro rechazo que no se reintenta automáticamente. */
    REJECTED,
    /** La respuesta no corresponde al cálculo enviado. */
    INVALID_RESPONSE,
    /** El registro cifrado no pudo leerse (clave invalidada o dato alterado). */
    UNREADABLE;

    companion object {
        fun fromWire(value: String?): MetricSyncFailure? = entries.firstOrNull { it.name == value }
    }
}

/** Cálculo capturado que aún no tiene resultado confirmado. Sin valor calculado localmente. */
data class PendingCalculation(
    val calculationId: String,
    val state: MetricSyncState,
    val failure: MetricSyncFailure?,
    /** Campo y causa de un `422` (p. ej. `inputs[0].value` / `out_of_range`). */
    val failureField: String?,
    val failureIssue: String?,
    val createdAt: Instant,
    /** `null` si el registro cifrado no pudo leerse. */
    val request: CalculationRequest?,
) {
    override fun toString(): String = "PendingCalculation(state=$state, failure=$failure)"
}

/** Solicitud de cálculo tal como se envía al BFF; el móvil nunca agrega un resultado. */
data class CalculationRequest(
    val calculationId: String,
    val metricCode: String,
    val methodCode: String,
    val methodVersion: Int,
    val measuredAt: Instant,
    val inputs: List<MetricInputValue>,
) {
    override fun toString(): String = "CalculationRequest(metricCode=$metricCode, methodCode=$methodCode)"
}

/** Error de experiencia de una entrada; el backend conserva la validación autoritativa. */
enum class InputError { REQUIRED, NOT_INTEGER, OUT_OF_RANGE }

/** Error de experiencia del instante de la prueba (ADR-030 §5). */
enum class MeasuredAtError { IN_FUTURE, TOO_OLD }

/** Validación local de experiencia con los límites del catálogo; nunca calcula el resultado. */
object MetricInputValidation {
    /** Futuro máximo y antigüedad máxima de `measured_at` (ADR-030 §5). */
    val MAX_FUTURE: Duration = Duration.ofMinutes(5)
    val MAX_AGE: Duration = Duration.ofDays(365)

    fun validate(definition: MetricInputDefinition, text: String): Result {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return if (definition.required) Result.Invalid(InputError.REQUIRED) else Result.Empty
        if (!trimmed.all(Char::isDigit) || trimmed.length > MAX_DIGITS) return Result.Invalid(InputError.NOT_INTEGER)
        val value = trimmed.toLong()
        if (value < definition.minimum || value > definition.maximum) return Result.Invalid(InputError.OUT_OF_RANGE)
        return Result.Valid(value)
    }

    fun validateMeasuredAt(measuredAt: Instant, now: Instant): MeasuredAtError? = when {
        measuredAt.isAfter(now.plus(MAX_FUTURE)) -> MeasuredAtError.IN_FUTURE
        measuredAt.isBefore(now.minus(MAX_AGE)) -> MeasuredAtError.TOO_OLD
        else -> null
    }

    sealed interface Result {
        data class Valid(val value: Long) : Result
        data object Empty : Result
        data class Invalid(val error: InputError) : Result
    }

    private const val MAX_DIGITS = 9
}

/**
 * Presentación de números con la escala del contrato y el formato regional del dispositivo. Nunca
 * redondea el valor confirmado: si trae más decimales que la escala, se muestran todos.
 */
object MetricFormat {
    fun value(value: BigDecimal, scale: Int, locale: Locale = Locale.getDefault()): String {
        val digits = maxOf(scale, value.stripTrailingZeros().scale(), 0)
        val format = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = scale.coerceAtLeast(0)
            maximumFractionDigits = digits
            roundingMode = RoundingMode.UNNECESSARY
            isGroupingUsed = true
        }
        return format.format(value)
    }
}
