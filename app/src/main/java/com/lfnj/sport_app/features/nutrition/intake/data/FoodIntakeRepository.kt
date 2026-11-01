package com.lfnj.sport_app.features.nutrition.intake.data

import com.lfnj.sport_app.core.network.ApiResult
import com.lfnj.sport_app.core.network.apiCallWithHeaders
import com.lfnj.sport_app.core.session.AuthorizedResult
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.features.nutrition.intake.data.local.FoodCatalogCacheEntity
import com.lfnj.sport_app.features.nutrition.intake.data.local.FoodIntakeCodec
import com.lfnj.sport_app.features.nutrition.intake.data.local.FoodIntakeDao
import com.lfnj.sport_app.features.nutrition.intake.data.local.FoodIntakeEntity
import com.lfnj.sport_app.features.nutrition.intake.model.FoodCatalog
import com.lfnj.sport_app.features.nutrition.intake.model.FoodUnit
import com.lfnj.sport_app.features.nutrition.intake.model.LocalFoodIntake
import com.lfnj.sport_app.features.nutrition.intake.model.MealType
import com.lfnj.sport_app.features.training.session.data.TrainingSyncScheduler
import com.lfnj.sport_app.features.training.session.data.local.TransactionRunner
import com.lfnj.sport_app.features.training.session.data.toWireInstant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Lo que la pantalla observa de la fuente local del deportista. */
data class FoodLocalState(
    /** Último catálogo confirmado, o `null` si nunca se confirmó o es ilegible. */
    val catalog: FoodCatalog?,
    /** Consumos guardados en el dispositivo, del más reciente al más antiguo. */
    val intakes: List<LocalFoodIntake>,
)

/** Resultado de consultar el catálogo en el BFF. Ninguna variante contiene datos del deportista. */
sealed interface CatalogRefresh {
    data object Updated : CatalogRefresh
    data object NoConnection : CatalogRefresh
    /** `502`/`503`/`504`, tiempo agotado o renovación de sesión no disponible: reintentar luego. */
    data class Unavailable(val correlationId: String?) : CatalogRefresh
    /** `403 permission_required`: la cuenta no es de deportista. */
    data object PermissionRequired : CatalogRefresh
    data object InvalidResponse : CatalogRefresh
    /** La sesión ya no es válida: volver al acceso. */
    data object SignedOut : CatalogRefresh
    /** La respuesta llegó, pero no pudo guardarse en el dispositivo. */
    data object StorageFailed : CatalogRefresh
}

/** Alimento elegido en el formulario, con la cantidad ya validada y normalizada. */
data class FoodIntakeDraftItem(val foodCode: String, val quantity: String, val unit: FoodUnit) {
    override fun toString(): String = "FoodIntakeDraftItem(<oculto>)"
}

sealed interface FoodSubmitResult {
    /** El consumo quedó cifrado como `PENDING_SYNC` y se programó la sincronización (CA5). */
    data class Queued(val intakeId: String) : FoodSubmitResult
    /** Sin catálogo en caché, o un alimento o unidad ausente de él: no se inventan alimentos (CA5, CA6). */
    data object CatalogUnavailable : FoodSubmitResult
    data object StorageFailed : FoodSubmitResult
}

/** Operaciones de la pantalla «Mi alimentación»; las implementa [FoodIntakeRepository]. */
interface FoodIntakeGateway {
    fun observe(owner: String): Flow<FoodLocalState>
    suspend fun refreshCatalog(owner: String): CatalogRefresh
    suspend fun submit(owner: String, mealType: MealType, consumedAt: Instant, items: List<FoodIntakeDraftItem>): FoodSubmitResult
    suspend fun dismiss(owner: String, intakeId: String): Boolean
}

/**
 * Registro de consumos offline first (ADR-026 y ADR-037 HU043 §5). La pantalla observa la fuente
 * local cifrada; el catálogo se reemplaza con cada respuesta confirmada del BFF (`ETag`/`304`) y un
 * consumo se guarda primero como `PENDING_SYNC` con su `intake_id` (UUID v4) y su `Idempotency-Key`,
 * y lo envía el trabajo durable. Nunca se presenta como confirmado antes del `201`.
 */
class FoodIntakeRepository(
    private val dao: FoodIntakeDao,
    private val transactions: TransactionRunner,
    private val codec: FoodIntakeCodec,
    private val api: FoodIntakeApi?,
    private val sessionManager: SessionManager,
    private val scheduler: TrainingSyncScheduler,
    private val json: Json,
    private val clock: Clock = Clock.systemUTC(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : FoodIntakeGateway {

    override fun observe(owner: String): Flow<FoodLocalState> = combine(
        dao.observeCatalog(owner),
        dao.observeIntakes(owner),
    ) { catalog, intakes ->
        FoodLocalState(catalog = catalog?.let(codec::decodeCatalog), intakes = intakes.map(codec::toLocal))
    }.distinctUntilChanged()

    suspend fun hasPending(owner: String): Boolean = dao.countPending(owner) > 0

    override suspend fun refreshCatalog(owner: String): CatalogRefresh {
        val api = api ?: return CatalogRefresh.Unavailable(null)
        val cached = storage { dao.catalog(owner) }
        val cachedCatalog = cached?.let(codec::decodeCatalog)
        val outcome = sessionManager.authorized { authorization ->
            apiCallWithHeaders(json) { api.catalog(authorization, cachedCatalog?.etag) }
        }
        val result = when (outcome) {
            AuthorizedResult.SignedOut -> return CatalogRefresh.SignedOut
            AuthorizedResult.RenewalUnavailable -> return CatalogRefresh.Unavailable(null)
            is AuthorizedResult.Completed -> outcome.result
        }
        val now = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        return when (result) {
            is ApiResult.Success -> {
                val (body, headers) = result.value
                persist { dao.replaceCatalog(FoodCatalogCacheEntity(owner, codec.encodeCatalog(body.catalogVersion, body.items, headers[ETAG_HEADER], now))) }
            }
            is ApiResult.HttpFailure -> when (result.status) {
                NOT_MODIFIED -> {
                    // 304: el catálogo cacheado sigue vigente; se renueva su confirmación (`last_synced_at`).
                    val items = cached?.let(codec::catalogItems)
                    if (cachedCatalog == null || items == null) {
                        CatalogRefresh.InvalidResponse
                    } else {
                        persist { dao.replaceCatalog(FoodCatalogCacheEntity(owner, codec.encodeCatalog(cachedCatalog.catalogVersion, items, cachedCatalog.etag, now))) }
                    }
                }
                403 -> CatalogRefresh.PermissionRequired
                else -> CatalogRefresh.Unavailable(result.correlationId)
            }
            ApiResult.NoConnection -> CatalogRefresh.NoConnection
            ApiResult.Timeout, ApiResult.NotConfigured -> CatalogRefresh.Unavailable(null)
            ApiResult.InvalidResponse -> CatalogRefresh.InvalidResponse
        }
    }

    /**
     * Guarda el consumo cifrado con su `intake_id` y su `Idempotency-Key` antes de cualquier envío
     * y programa la sincronización. Solo acepta alimentos y unidades del catálogo en caché (CA5, CA6).
     */
    override suspend fun submit(owner: String, mealType: MealType, consumedAt: Instant, items: List<FoodIntakeDraftItem>): FoodSubmitResult {
        val catalog = storage { dao.catalog(owner) }?.let(codec::decodeCatalog) ?: return FoodSubmitResult.CatalogUnavailable
        if (items.isEmpty()) return FoodSubmitResult.CatalogUnavailable
        val labelKeys = mutableMapOf<String, String>()
        items.forEach { draft ->
            val food = catalog.item(draft.foodCode)?.takeIf { it.allows(draft.unit) } ?: return FoodSubmitResult.CatalogUnavailable
            labelKeys[food.foodCode] = food.labelKey
        }
        val intakeId = newId()
        val body = RegisterFoodIntakeRequestDto(
            intakeId = intakeId,
            consumedAt = consumedAt.toWireInstant(),
            mealType = mealType.wire,
            items = items.map { FoodIntakeItemDto(it.foodCode, it.quantity, it.unit.wire) },
        )
        storage {
            dao.insertIntake(
                FoodIntakeEntity(
                    intakeId = intakeId,
                    ownerKey = owner,
                    idempotencyKey = newId(),
                    syncState = FoodIntakeEntity.PENDING_SYNC,
                    createdAtMs = clock.millis(),
                    payload = codec.encodeIntake(body, labelKeys),
                )
            )
        } ?: return FoodSubmitResult.StorageFailed
        scheduler.schedule(owner)
        return FoodSubmitResult.Queued(intakeId)
    }

    /** Retira del dispositivo un consumo rechazado, vencido o ilegible ya informado; nunca uno pendiente. */
    override suspend fun dismiss(owner: String, intakeId: String): Boolean = storage {
        transactions.run {
            val entity = dao.intake(owner, intakeId)
            val failed = entity != null &&
                (entity.syncState == FoodIntakeEntity.SYNC_FAILED || codec.decodeRequest(entity) == null)
            if (failed) dao.deleteIntake(owner, intakeId)
            failed
        }
    } ?: false

    /**
     * Limpieza al terminar la sesión de [owner]: borra la caché del catálogo y los consumos ya
     * resueltos, y conserva cifrados los pendientes, que solo reanuda el mismo deportista (ADR-029
     * offline §4, la misma decisión que HU042).
     */
    suspend fun clearOnSessionEnd(owner: String) {
        transactions.run {
            dao.deleteCatalog(owner)
            dao.deleteSettled(owner)
        }
    }

    private suspend fun persist(block: suspend () -> Unit): CatalogRefresh =
        if (storage { block(); true } == true) CatalogRefresh.Updated else CatalogRefresh.StorageFailed

    /** Errores de Room o del Keystore se informan como falla de almacenamiento, sin detalles. */
    private suspend fun <T> storage(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val ETAG_HEADER = "ETag"
        private const val NOT_MODIFIED = 304
    }
}
