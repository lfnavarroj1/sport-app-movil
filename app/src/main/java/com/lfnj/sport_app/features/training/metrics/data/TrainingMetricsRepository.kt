package com.lfnj.sport_app.features.training.metrics.data

import com.lfnj.sport_app.core.network.ApiResult
import com.lfnj.sport_app.core.network.apiCall
import com.lfnj.sport_app.core.network.apiCallWithHeaders
import com.lfnj.sport_app.core.session.AuthorizedResult
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.features.training.metrics.data.local.MetricCatalogCacheEntity
import com.lfnj.sport_app.features.training.metrics.data.local.MetricCommandEntity
import com.lfnj.sport_app.features.training.metrics.data.local.MetricResultCacheEntity
import com.lfnj.sport_app.features.training.metrics.data.local.TrainingMetricsCodec
import com.lfnj.sport_app.features.training.metrics.data.local.TrainingMetricsDao
import com.lfnj.sport_app.features.training.metrics.model.MetricCatalog
import com.lfnj.sport_app.features.training.metrics.model.MetricInputValue
import com.lfnj.sport_app.features.training.metrics.model.MetricMethod
import com.lfnj.sport_app.features.training.metrics.model.MetricResult
import com.lfnj.sport_app.features.training.metrics.model.MetricResultsCache
import com.lfnj.sport_app.features.training.metrics.model.PendingCalculation
import com.lfnj.sport_app.features.training.session.data.TrainingSyncScheduler
import com.lfnj.sport_app.features.training.session.data.local.TransactionRunner
import com.lfnj.sport_app.features.training.session.data.toWireInstant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Lo que la pantalla observa de la fuente local del deportista. */
data class MetricsLocalState(
    /** Último catálogo confirmado, o `null` si nunca se confirmó o es ilegible. */
    val catalog: MetricCatalog?,
    val results: MetricResultsCache,
    /** Cálculos capturados sin resultado confirmado, del más reciente al más antiguo. */
    val pending: List<PendingCalculation>,
)

/** Resultado de consultar el BFF. Ninguna variante contiene entradas ni resultados. */
sealed interface MetricsRefresh {
    data object Updated : MetricsRefresh
    data object NoConnection : MetricsRefresh
    /** `502`/`503`/`504`, tiempo agotado o renovación de sesión no disponible: reintentar luego. */
    data class Unavailable(val correlationId: String?) : MetricsRefresh
    /** `403 capability_required`: el plan vigente no incluye `training.metrics.ftp_vo2max`. */
    data object CapabilityRequired : MetricsRefresh
    /** `403 permission_required`: la cuenta no es de deportista. */
    data object PermissionRequired : MetricsRefresh
    data object InvalidResponse : MetricsRefresh
    /** La sesión ya no es válida: volver al acceso. */
    data object SignedOut : MetricsRefresh
    /** La respuesta llegó, pero no pudo guardarse en el dispositivo. */
    data object StorageFailed : MetricsRefresh
}

/** Página adicional del historial, solo en memoria (la caché conserva la primera). */
sealed interface MoreResults {
    data class Loaded(val items: List<MetricResult>, val nextCursor: String?) : MoreResults
    data class Failed(val refresh: MetricsRefresh) : MoreResults
}

sealed interface SubmitResult {
    /** El cálculo quedó cifrado en la cola como `PENDING_SYNC` y se programó la sincronización. */
    data class Queued(val calculationId: String) : SubmitResult
    /** El método no está en el catálogo confirmado: no se habilita un método desconocido (CA9). */
    data object MethodUnavailable : SubmitResult
    data object StorageFailed : SubmitResult
}

/** Operaciones de la pantalla `/entrenamientos/metricas`; las implementa [TrainingMetricsRepository]. */
interface TrainingMetricsGateway {
    fun observe(owner: String): Flow<MetricsLocalState>
    suspend fun refreshCatalog(owner: String): MetricsRefresh
    suspend fun refreshResults(owner: String): MetricsRefresh
    suspend fun loadMore(owner: String, cursor: String): MoreResults
    suspend fun submit(owner: String, methodId: String, inputs: List<MetricInputValue>, measuredAt: Instant): SubmitResult
    suspend fun dismiss(owner: String, calculationId: String): Boolean
}

/**
 * Indicadores FTP y VO₂max offline first (ADR-026 y ADR-030). La pantalla observa la fuente local
 * cifrada; el catálogo y el historial se reemplazan con cada respuesta confirmada del BFF, y un
 * cálculo se guarda primero en la cola (`PENDING_SYNC`) y lo envía el trabajo durable. El móvil
 * nunca aplica las fórmulas: el resultado siempre lo devuelve el backend.
 */
class TrainingMetricsRepository(
    private val dao: TrainingMetricsDao,
    private val transactions: TransactionRunner,
    private val codec: TrainingMetricsCodec,
    private val api: TrainingMetricsApi?,
    private val sessionManager: SessionManager,
    private val scheduler: TrainingSyncScheduler,
    private val json: Json,
    private val clock: Clock = Clock.systemUTC(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : TrainingMetricsGateway {

    override fun observe(owner: String): Flow<MetricsLocalState> = combine(
        dao.observeCatalog(owner),
        dao.observeResults(owner),
        dao.observeCommands(owner),
    ) { catalog, results, commands ->
        MetricsLocalState(
            catalog = catalog?.let(codec::decodeCatalog),
            results = results?.let(codec::decodeResults) ?: MetricResultsCache.EMPTY,
            pending = commands.map(codec::toPending),
        )
    }.distinctUntilChanged()

    suspend fun hasPending(owner: String): Boolean = dao.countPending(owner) > 0

    override suspend fun refreshCatalog(owner: String): MetricsRefresh {
        val api = api ?: return MetricsRefresh.Unavailable(null)
        val cached = storage { dao.catalog(owner) }
        val cachedCatalog = cached?.let(codec::decodeCatalog)
        val outcome = sessionManager.authorized { authorization ->
            apiCallWithHeaders(json) { api.catalog(authorization, cachedCatalog?.etag) }
        }
        val now = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        return when (val result = outcome.unwrap() ?: return outcome.failure()) {
            is ApiResult.Success -> {
                val (body, headers) = result.value
                val catalog = MetricCatalog(body.catalogVersion, body.methods.map(CatalogMethodDto::toModel), headers[ETAG_HEADER], now)
                persist { dao.replaceCatalog(MetricCatalogCacheEntity(owner, codec.encodeCatalog(catalog, body.methods))) }
            }
            is ApiResult.Failure -> if (result is ApiResult.HttpFailure && result.status == NOT_MODIFIED) {
                // 304: el catálogo cacheado sigue vigente; se renueva su confirmación (`last_synced_at`).
                val methods = cached?.let(codec::catalogMethods)
                if (cachedCatalog == null || methods == null) {
                    MetricsRefresh.InvalidResponse
                } else {
                    persist { dao.replaceCatalog(MetricCatalogCacheEntity(owner, codec.encodeCatalog(cachedCatalog.copy(syncedAt = now), methods))) }
                }
            } else {
                result.toRefresh()
            }
        }
    }

    override suspend fun refreshResults(owner: String): MetricsRefresh {
        val api = api ?: return MetricsRefresh.Unavailable(null)
        val outcome = sessionManager.authorized { authorization ->
            apiCall(json) { api.calculations(authorization, PAGE_SIZE, cursor = null) }
        }
        return when (val result = outcome.unwrap() ?: return outcome.failure()) {
            is ApiResult.Success -> {
                val items = result.value.items.map { it.toModel() ?: return MetricsRefresh.InvalidResponse }
                val now = clock.instant().truncatedTo(ChronoUnit.SECONDS)
                persist {
                    transactions.run {
                        val previous = dao.results(owner)?.let(codec::decodeResults) ?: MetricResultsCache.EMPTY
                        // Un resultado confirmado por el comando sale de «a la espera» cuando la proyección lo devuelve
                        // o, por vencimiento, a las 24 horas de calculado.
                        val awaiting = previous.awaiting.filter { a ->
                            items.none { it.calculationId == a.calculationId } && Duration.between(a.calculatedAt, now) < AWAITING_PROJECTION
                        }
                        dao.replaceResults(MetricResultCacheEntity(owner, codec.encodeResults(MetricResultsCache(items, awaiting, result.value.nextCursor, now))))
                    }
                }
            }
            is ApiResult.Failure -> result.toRefresh()
        }
    }

    override suspend fun loadMore(owner: String, cursor: String): MoreResults {
        val api = api ?: return MoreResults.Failed(MetricsRefresh.Unavailable(null))
        val outcome = sessionManager.authorized { authorization -> apiCall(json) { api.calculations(authorization, PAGE_SIZE, cursor) } }
        return when (val result = outcome.unwrap() ?: return MoreResults.Failed(outcome.failure())) {
            is ApiResult.Success -> {
                val items = result.value.items.map { it.toModel() ?: return MoreResults.Failed(MetricsRefresh.InvalidResponse) }
                MoreResults.Loaded(items, result.value.nextCursor)
            }
            is ApiResult.Failure -> MoreResults.Failed(result.toRefresh())
        }
    }

    /**
     * Guarda el cálculo cifrado con su `calculation_id` (UUID v4) y su `Idempotency-Key` antes de
     * cualquier envío, y programa la sincronización. Solo acepta un método del catálogo confirmado.
     */
    override suspend fun submit(owner: String, methodId: String, inputs: List<MetricInputValue>, measuredAt: Instant): SubmitResult {
        val catalog = storage { dao.catalog(owner) }?.let(codec::decodeCatalog) ?: return SubmitResult.MethodUnavailable
        val method: MetricMethod = catalog.method(methodId)?.takeIf(MetricMethod::supported) ?: return SubmitResult.MethodUnavailable
        val calculationId = newId()
        val body = CalculateTrainingMetricRequestDto(
            calculationId = calculationId,
            metricCode = method.metricCode,
            methodCode = method.methodCode,
            methodVersion = method.methodVersion,
            measuredAt = measuredAt.toWireInstant(),
            inputs = inputs.map { MetricInputValueDto(it.code, it.value, it.unit) },
        )
        val stored = storage {
            dao.insertCommand(
                MetricCommandEntity(
                    calculationId = calculationId,
                    ownerKey = owner,
                    idempotencyKey = newId(),
                    syncState = MetricCommandEntity.PENDING_SYNC,
                    createdAtMs = clock.millis(),
                    payload = codec.encodeRequest(body),
                )
            )
            true
        } ?: return SubmitResult.StorageFailed
        if (stored) scheduler.schedule(owner)
        return SubmitResult.Queued(calculationId)
    }

    /** Retira del dispositivo un cálculo rechazado o vencido ya informado; nunca uno pendiente. */
    override suspend fun dismiss(owner: String, calculationId: String): Boolean = storage {
        transactions.run {
            val command = dao.command(owner, calculationId)
            val failed = command != null &&
                (command.syncState == MetricCommandEntity.SYNC_FAILED || codec.decodeRequest(command) == null)
            if (failed) dao.deleteCommand(owner, calculationId)
            failed
        }
    } ?: false

    // ---------------------------------------------------------------- internos

    private fun <T> AuthorizedResult<T>.unwrap(): ApiResult<T>? = (this as? AuthorizedResult.Completed)?.result

    private fun AuthorizedResult<*>.failure(): MetricsRefresh = when (this) {
        AuthorizedResult.SignedOut -> MetricsRefresh.SignedOut
        AuthorizedResult.RenewalUnavailable -> MetricsRefresh.Unavailable(null)
        is AuthorizedResult.Completed -> MetricsRefresh.InvalidResponse
    }

    private fun ApiResult.Failure.toRefresh(): MetricsRefresh = when (this) {
        is ApiResult.HttpFailure -> when {
            status == 403 && code == CODE_CAPABILITY_REQUIRED -> MetricsRefresh.CapabilityRequired
            status == 403 -> MetricsRefresh.PermissionRequired
            else -> MetricsRefresh.Unavailable(correlationId)
        }
        ApiResult.NoConnection -> MetricsRefresh.NoConnection
        ApiResult.Timeout, ApiResult.NotConfigured -> MetricsRefresh.Unavailable(null)
        ApiResult.InvalidResponse -> MetricsRefresh.InvalidResponse
    }

    private suspend fun persist(block: suspend () -> Unit): MetricsRefresh =
        if (storage { block(); true } == true) MetricsRefresh.Updated else MetricsRefresh.StorageFailed

    /** Errores de Room o del Keystore se informan como falla de almacenamiento, sin detalles. */
    private suspend fun <T> storage(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        /** Tamaño de página del historial (1–100 en el contrato). */
        const val PAGE_SIZE = 20

        /** Plazo para que la proyección devuelva un resultado confirmado por el comando. */
        val AWAITING_PROJECTION: Duration = Duration.ofHours(24)

        private const val CODE_CAPABILITY_REQUIRED = "capability_required"
        private const val ETAG_HEADER = "ETag"
        private const val NOT_MODIFIED = 304
    }
}
