package com.lfnj.sport_app.features.training.metrics.data.local

import com.lfnj.sport_app.core.security.TokenCipher
import com.lfnj.sport_app.features.training.metrics.data.CalculateTrainingMetricRequestDto
import com.lfnj.sport_app.features.training.metrics.data.CatalogMethodDto
import com.lfnj.sport_app.features.training.metrics.data.MetricInputValueDto
import com.lfnj.sport_app.features.training.metrics.model.CalculationRequest
import com.lfnj.sport_app.features.training.metrics.model.MetricCatalog
import com.lfnj.sport_app.features.training.metrics.model.MetricInputValue
import com.lfnj.sport_app.features.training.metrics.model.MetricResult
import com.lfnj.sport_app.features.training.metrics.model.MetricResultsCache
import com.lfnj.sport_app.features.training.metrics.model.MetricSyncFailure
import com.lfnj.sport_app.features.training.metrics.model.MetricSyncState
import com.lfnj.sport_app.features.training.metrics.model.PendingCalculation
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.Instant

@Serializable
internal data class CatalogPayload(
    @SerialName("catalog_version") val catalogVersion: String,
    val methods: List<CatalogMethodDto>,
    val etag: String? = null,
    @SerialName("last_synced_at") val lastSyncedAt: Long,
)

@Serializable
internal data class CachedResult(
    @SerialName("calculation_id") val calculationId: String,
    @SerialName("metric_code") val metricCode: String,
    @SerialName("method_code") val methodCode: String,
    @SerialName("method_version") val methodVersion: Int,
    @SerialName("measured_at") val measuredAt: Long,
    @SerialName("calculated_at") val calculatedAt: Long,
    val inputs: List<MetricInputValueDto>,
    /** Valor confirmado como texto decimal exacto. */
    val value: String,
    val unit: String,
    val scale: Int,
    @SerialName("disclaimer_key") val disclaimerKey: String,
)

@Serializable
internal data class ResultsPayload(
    /** Primera página de la proyección (`GET /v1/training-metrics/calculations`). */
    val items: List<CachedResult> = emptyList(),
    /** Resultados confirmados por el comando que la proyección todavía no devuelve. */
    val awaiting: List<CachedResult> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    /** Última consulta confirmada del historial; `null` si aún no se consultó. */
    @SerialName("last_synced_at") val lastSyncedAt: Long? = null,
)

/**
 * Cifra y descifra los datos de HU034 con AES-GCM y una clave no exportable de Android Keystore
 * propia (ADR-030 offline §1 y §3). Un registro que no puede descifrarse se informa como ilegible y
 * nunca se reemplaza por valores inventados.
 */
class TrainingMetricsCodec(private val cipher: TokenCipher, private val json: Json) {

    fun encodeCatalog(catalog: MetricCatalog, methods: List<CatalogMethodDto>): ByteArray =
        encrypt(CatalogPayload.serializer(), CatalogPayload(catalog.catalogVersion, methods, catalog.etag, catalog.syncedAt.epochSecond))

    fun decodeCatalog(entity: MetricCatalogCacheEntity): MetricCatalog? = readable {
        val payload = decrypt(CatalogPayload.serializer(), entity.payload)
        MetricCatalog(payload.catalogVersion, payload.methods.map(CatalogMethodDto::toModel), payload.etag, Instant.ofEpochSecond(payload.lastSyncedAt))
    }

    /** Métodos del catálogo cacheado en el formato del contrato, para reescribirlo al confirmar un `304`. */
    fun catalogMethods(entity: MetricCatalogCacheEntity): List<CatalogMethodDto>? = readable {
        decrypt(CatalogPayload.serializer(), entity.payload).methods
    }

    fun encodeResults(cache: MetricResultsCache): ByteArray = encrypt(
        ResultsPayload.serializer(),
        ResultsPayload(cache.projected.map(::cached), cache.awaiting.map(::cached), cache.nextCursor, cache.syncedAt?.epochSecond),
    )

    fun decodeResults(entity: MetricResultCacheEntity): MetricResultsCache? = readable {
        val payload = decrypt(ResultsPayload.serializer(), entity.payload)
        MetricResultsCache(
            projected = payload.items.map { it.toModel() },
            awaiting = payload.awaiting.map { it.toModel() },
            nextCursor = payload.nextCursor,
            syncedAt = payload.lastSyncedAt?.let(Instant::ofEpochSecond),
        )
    }

    fun encodeRequest(body: CalculateTrainingMetricRequestDto): ByteArray = encrypt(CalculateTrainingMetricRequestDto.serializer(), body)

    fun decodeRequest(entity: MetricCommandEntity): CalculateTrainingMetricRequestDto? = readable {
        decrypt(CalculateTrainingMetricRequestDto.serializer(), entity.payload)
    }

    fun toPending(entity: MetricCommandEntity): PendingCalculation {
        val request = decodeRequest(entity)?.let { body ->
            CalculationRequest(
                calculationId = body.calculationId,
                metricCode = body.metricCode,
                methodCode = body.methodCode,
                methodVersion = body.methodVersion,
                measuredAt = runCatching { Instant.parse(body.measuredAt) }.getOrNull() ?: return@let null,
                inputs = body.inputs.map { MetricInputValue(it.code, it.value, it.unit) },
            )
        }
        val failed = entity.syncState == MetricCommandEntity.SYNC_FAILED || request == null
        return PendingCalculation(
            calculationId = entity.calculationId,
            state = if (failed) MetricSyncState.SYNC_FAILED else MetricSyncState.PENDING_SYNC,
            failure = if (request == null) MetricSyncFailure.UNREADABLE else MetricSyncFailure.fromWire(entity.failureReason),
            failureField = entity.failureField,
            failureIssue = entity.failureIssue,
            createdAt = Instant.ofEpochMilli(entity.createdAtMs),
            request = request,
        )
    }

    private fun cached(result: MetricResult) = CachedResult(
        calculationId = result.calculationId,
        metricCode = result.metricCode,
        methodCode = result.methodCode,
        methodVersion = result.methodVersion,
        measuredAt = result.measuredAt.epochSecond,
        calculatedAt = result.calculatedAt.epochSecond,
        inputs = result.inputs.map { MetricInputValueDto(it.code, it.value, it.unit) },
        value = result.value.toPlainString(),
        unit = result.unit,
        scale = result.scale,
        disclaimerKey = result.disclaimerKey,
    )

    private fun CachedResult.toModel() = MetricResult(
        calculationId, metricCode, methodCode, methodVersion,
        Instant.ofEpochSecond(measuredAt), Instant.ofEpochSecond(calculatedAt),
        inputs.map(MetricInputValueDto::toModel), BigDecimal(value), unit, scale, disclaimerKey,
    )

    private fun <T> encrypt(serializer: KSerializer<T>, value: T): ByteArray =
        cipher.encrypt(json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8))

    private fun <T> decrypt(serializer: KSerializer<T>, payload: ByteArray): T =
        json.decodeFromString(serializer, cipher.decrypt(payload).toString(Charsets.UTF_8))

    /** Cualquier falla de descifrado o de formato se trata como registro ilegible. */
    private inline fun <T> readable(block: () -> T): T? = try {
        block()
    } catch (_: Exception) {
        null
    }

    companion object {
        /** Alias de la clave no exportable de Android Keystore para los datos de HU034. */
        const val KEY_ALIAS = "sportapp.training-metrics.v1"
    }
}
