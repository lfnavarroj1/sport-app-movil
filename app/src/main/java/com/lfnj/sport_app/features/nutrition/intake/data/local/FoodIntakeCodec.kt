package com.lfnj.sport_app.features.nutrition.intake.data.local

import com.lfnj.sport_app.core.security.TokenCipher
import com.lfnj.sport_app.features.nutrition.intake.data.FoodCatalogItemDto
import com.lfnj.sport_app.features.nutrition.intake.data.RegisterFoodIntakeRequestDto
import com.lfnj.sport_app.features.nutrition.intake.model.FoodCatalog
import com.lfnj.sport_app.features.nutrition.intake.model.FoodIntakeDetails
import com.lfnj.sport_app.features.nutrition.intake.model.FoodIntakeLine
import com.lfnj.sport_app.features.nutrition.intake.model.FoodSyncFailure
import com.lfnj.sport_app.features.nutrition.intake.model.FoodSyncState
import com.lfnj.sport_app.features.nutrition.intake.model.FoodUnit
import com.lfnj.sport_app.features.nutrition.intake.model.LocalFoodIntake
import com.lfnj.sport_app.features.nutrition.intake.model.MealType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

@Serializable
internal data class CatalogPayload(
    @SerialName("catalog_version") val catalogVersion: String,
    val items: List<FoodCatalogItemDto>,
    val etag: String? = null,
    @SerialName("last_synced_at") val lastSyncedAt: Long,
)

/**
 * Contenido cifrado de un consumo: el cuerpo enviado, las claves de traducción de sus alimentos
 * (para mostrarlo aunque la caché del catálogo ya no exista) y, al confirmarse, `registered_at`.
 */
@Serializable
internal data class StoredFoodIntake(
    val request: RegisterFoodIntakeRequestDto,
    @SerialName("label_keys") val labelKeys: Map<String, String> = emptyMap(),
    @SerialName("registered_at") val registeredAt: String? = null,
)

/**
 * Cifra y descifra los datos de HU043 con AES-GCM y una clave no exportable de Android Keystore
 * propia. Un registro que no puede descifrarse se informa como ilegible y nunca se reemplaza por
 * valores inventados.
 */
class FoodIntakeCodec(private val cipher: TokenCipher, private val json: Json) {

    fun encodeCatalog(catalogVersion: String, items: List<FoodCatalogItemDto>, etag: String?, syncedAt: Instant): ByteArray =
        encrypt(CatalogPayload.serializer(), CatalogPayload(catalogVersion, items, etag, syncedAt.epochSecond))

    fun decodeCatalog(entity: FoodCatalogCacheEntity): FoodCatalog? = readable {
        val payload = decrypt(CatalogPayload.serializer(), entity.payload)
        FoodCatalog(payload.catalogVersion, payload.items.mapNotNull(FoodCatalogItemDto::toModel), payload.etag, Instant.ofEpochSecond(payload.lastSyncedAt))
    }

    /** Alimentos del catálogo cacheado en el formato del contrato, para reescribirlo al confirmar un `304`. */
    fun catalogItems(entity: FoodCatalogCacheEntity): List<FoodCatalogItemDto>? = readable {
        decrypt(CatalogPayload.serializer(), entity.payload).items
    }

    fun encodeIntake(request: RegisterFoodIntakeRequestDto, labelKeys: Map<String, String>, registeredAt: Instant? = null): ByteArray =
        encrypt(StoredFoodIntake.serializer(), StoredFoodIntake(request, labelKeys, registeredAt?.toString()))

    fun decodeRequest(entity: FoodIntakeEntity): RegisterFoodIntakeRequestDto? = stored(entity)?.request

    fun labelKeys(entity: FoodIntakeEntity): Map<String, String> = stored(entity)?.labelKeys.orEmpty()

    fun toLocal(entity: FoodIntakeEntity): LocalFoodIntake {
        val stored = stored(entity)
        val details = stored?.let(::details)
        val state = when {
            details == null -> FoodSyncState.SYNC_FAILED
            entity.syncState == FoodIntakeEntity.SYNCED -> FoodSyncState.SYNCED
            entity.syncState == FoodIntakeEntity.SYNC_FAILED -> FoodSyncState.SYNC_FAILED
            else -> FoodSyncState.PENDING_SYNC
        }
        return LocalFoodIntake(
            intakeId = entity.intakeId,
            state = state,
            failure = if (details == null) FoodSyncFailure.UNREADABLE else FoodSyncFailure.fromWire(entity.failureReason),
            failureField = entity.failureField,
            failureIssue = entity.failureIssue,
            createdAt = Instant.ofEpochMilli(entity.createdAtMs),
            details = details,
            registeredAt = stored?.registeredAt?.takeIf { state == FoodSyncState.SYNCED }?.let { runCatching { Instant.parse(it) }.getOrNull() },
        )
    }

    /** `null` si el cuerpo guardado ya no corresponde a los catálogos del cliente. */
    private fun details(stored: StoredFoodIntake): FoodIntakeDetails? {
        val request = stored.request
        return FoodIntakeDetails(
            mealType = MealType.fromWire(request.mealType) ?: return null,
            consumedAt = runCatching { Instant.parse(request.consumedAt) }.getOrNull() ?: return null,
            items = request.items.map { item ->
                FoodIntakeLine(item.foodCode, stored.labelKeys[item.foodCode], item.quantity, FoodUnit.fromWire(item.unit) ?: return null)
            },
        )
    }

    private fun stored(entity: FoodIntakeEntity): StoredFoodIntake? = readable {
        decrypt(StoredFoodIntake.serializer(), entity.payload)
    }

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
        /** Alias de la clave no exportable de Android Keystore para los datos de HU043. */
        const val KEY_ALIAS = "sportapp.food-intakes.v1"
    }
}
