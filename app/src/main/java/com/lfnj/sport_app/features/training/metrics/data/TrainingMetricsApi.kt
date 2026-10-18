package com.lfnj.sport_app.features.training.metrics.data

import com.lfnj.sport_app.features.training.metrics.model.MetricInputDefinition
import com.lfnj.sport_app.features.training.metrics.model.MetricInputValue
import com.lfnj.sport_app.features.training.metrics.model.MetricMethod
import com.lfnj.sport_app.features.training.metrics.model.MetricResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import java.time.Instant

/**
 * Operaciones de HU034 en `bff-mobile` (ADR-030, «Contrato HTTP»). La identidad sale del token:
 * el cuerpo nunca lleva `athlete_id` ni un resultado calculado en el dispositivo.
 */
interface TrainingMetricsApi {
    /** `200` con el catálogo y su `ETag`, o `304` si [ifNoneMatch] coincide con el vigente. */
    @GET("v1/training-metrics/catalog")
    suspend fun catalog(
        @Header("Authorization") authorization: String,
        @Header("If-None-Match") ifNoneMatch: String?,
    ): Response<TrainingMetricCatalogDto>

    @POST("v1/training-metrics/calculations")
    suspend fun calculate(
        @Header("Authorization") authorization: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: CalculateTrainingMetricRequestDto,
    ): Response<TrainingMetricCalculationDto>

    /** Historial propio ordenado por `measured_at DESC, calculation_id DESC` (proyección CQRS). */
    @GET("v1/training-metrics/calculations")
    suspend fun calculations(
        @Header("Authorization") authorization: String,
        @Query("limit") limit: Int,
        @Query("cursor") cursor: String?,
    ): Response<TrainingMetricCalculationsDto>
}

/** `TrainingMetricCatalogResponse`. */
@Serializable
data class TrainingMetricCatalogDto(
    @SerialName("catalog_version") val catalogVersion: String,
    val methods: List<CatalogMethodDto>,
)

/** `CatalogMethod`. */
@Serializable
data class CatalogMethodDto(
    @SerialName("metric_code") val metricCode: String,
    @SerialName("label_key") val labelKey: String,
    @SerialName("result_unit") val resultUnit: String,
    @SerialName("result_scale") val resultScale: Int,
    @SerialName("method_code") val methodCode: String,
    @SerialName("method_version") val methodVersion: Int,
    @SerialName("method_label_key") val methodLabelKey: String,
    @SerialName("disclaimer_key") val disclaimerKey: String,
    val inputs: List<CatalogInputDto>,
) {
    fun toModel() = MetricMethod(
        metricCode, labelKey, resultUnit, resultScale, methodCode, methodVersion, methodLabelKey, disclaimerKey,
        inputs.map(CatalogInputDto::toModel),
    )
}

/** `CatalogInput`. */
@Serializable
data class CatalogInputDto(
    val code: String,
    @SerialName("label_key") val labelKey: String,
    @SerialName("value_type") val valueType: String,
    val unit: String,
    val minimum: Long,
    val maximum: Long,
    val scale: Int,
    val required: Boolean,
) {
    fun toModel() = MetricInputDefinition(code, labelKey, valueType, unit, minimum, maximum, scale, required)
}

/** `MetricInputValue`: entrada enviada y conservada por el backend. */
@Serializable
data class MetricInputValueDto(val code: String, val value: Long, val unit: String) {
    override fun toString(): String = "MetricInputValueDto(code=$code)"

    fun toModel() = MetricInputValue(code, value, unit)
}

/** `CalculateTrainingMetricRequest`. */
@Serializable
data class CalculateTrainingMetricRequestDto(
    @SerialName("calculation_id") val calculationId: String,
    @SerialName("metric_code") val metricCode: String,
    @SerialName("method_code") val methodCode: String,
    @SerialName("method_version") val methodVersion: Int,
    @SerialName("measured_at") val measuredAt: String,
    val inputs: List<MetricInputValueDto>,
) {
    override fun toString(): String = "CalculateTrainingMetricRequestDto(<oculto>)"
}

/** `MetricResult`. `value` es un número JSON entero o decimal; se conserva exacto. */
@Serializable
data class MetricResultDto(val value: JsonPrimitive, val unit: String, val scale: Int) {
    override fun toString(): String = "MetricResultDto(<oculto>)"
}

/** `TrainingMetricCalculationResponse`: resultado `CONFIRMED` del backend. */
@Serializable
data class TrainingMetricCalculationDto(
    @SerialName("calculation_id") val calculationId: String,
    @SerialName("athlete_id") val athleteId: String,
    @SerialName("metric_code") val metricCode: String,
    @SerialName("method_code") val methodCode: String,
    @SerialName("method_version") val methodVersion: Int,
    @SerialName("measured_at") val measuredAt: String,
    val inputs: List<MetricInputValueDto>,
    val result: MetricResultDto,
    val status: String,
    @SerialName("calculated_at") val calculatedAt: String,
    @SerialName("disclaimer_key") val disclaimerKey: String,
    val version: Int,
) {
    override fun toString(): String = "TrainingMetricCalculationDto(status=$status, version=$version)"

    /** `null` si no es un resultado confirmado legible: nunca se inventa un valor. */
    fun toModel(): MetricResult? {
        if (status != STATUS_CONFIRMED || result.value.isString) return null
        val value = result.value.content.toBigDecimalOrNull() ?: return null
        return MetricResult(
            calculationId = calculationId,
            metricCode = metricCode,
            methodCode = methodCode,
            methodVersion = methodVersion,
            measuredAt = runCatching { Instant.parse(measuredAt) }.getOrNull() ?: return null,
            calculatedAt = runCatching { Instant.parse(calculatedAt) }.getOrNull() ?: return null,
            inputs = inputs.map(MetricInputValueDto::toModel),
            value = value,
            unit = result.unit,
            scale = result.scale,
            disclaimerKey = disclaimerKey,
        )
    }

    companion object {
        const val STATUS_CONFIRMED = "CONFIRMED"
    }
}

/** `TrainingMetricCalculationsResponse`. */
@Serializable
data class TrainingMetricCalculationsDto(
    val items: List<TrainingMetricCalculationDto>,
    @SerialName("next_cursor") val nextCursor: String? = null,
)
