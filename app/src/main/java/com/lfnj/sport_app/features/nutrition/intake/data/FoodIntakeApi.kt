package com.lfnj.sport_app.features.nutrition.intake.data

import com.lfnj.sport_app.features.nutrition.intake.model.FoodCatalogItem
import com.lfnj.sport_app.features.nutrition.intake.model.FoodCategory
import com.lfnj.sport_app.features.nutrition.intake.model.FoodUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import java.time.Instant

/**
 * Operaciones de HU043 en `bff-mobile` 0.21.0 (ADR-037). La identidad sale del token: el cuerpo
 * nunca lleva `athlete_id`, texto libre ni calorías.
 */
interface FoodIntakeApi {
    /** `200` con el catálogo y su `ETag`, o `304` si [ifNoneMatch] coincide con el vigente. */
    @GET("v1/food-catalog")
    suspend fun catalog(
        @Header("Authorization") authorization: String,
        @Header("If-None-Match") ifNoneMatch: String?,
    ): Response<FoodCatalogDto>

    @POST("v1/me/food-intakes")
    suspend fun register(
        @Header("Authorization") authorization: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: RegisterFoodIntakeRequestDto,
    ): Response<FoodIntakeResponseDto>
}

/** `FoodCatalogResponse`: catálogo cerrado y versionado. */
@Serializable
data class FoodCatalogDto(
    @SerialName("catalog_version") val catalogVersion: String,
    val items: List<FoodCatalogItemDto>,
)

/** `FoodCatalogItem`. */
@Serializable
data class FoodCatalogItemDto(
    @SerialName("food_code") val foodCode: String,
    @SerialName("label_key") val labelKey: String,
    val category: String,
    @SerialName("default_unit") val defaultUnit: String,
    @SerialName("allowed_units") val allowedUnits: List<String>,
    val allergens: List<String> = emptyList(),
    @SerialName("incompatible_with") val incompatibleWith: List<String> = emptyList(),
) {
    /** `null` si el alimento no tiene ninguna unidad que el cliente conozca. */
    fun toModel(): FoodCatalogItem? {
        val units = allowedUnits.mapNotNull(FoodUnit::fromWire).distinct()
        if (units.isEmpty()) return null
        val default = FoodUnit.fromWire(defaultUnit)?.takeIf { it in units } ?: units.first()
        return FoodCatalogItem(foodCode, labelKey, FoodCategory.fromWire(category), default, units)
    }
}

/** `FoodIntakeItem`: el mismo esquema en la solicitud y en la respuesta. */
@Serializable
data class FoodIntakeItemDto(
    @SerialName("food_code") val foodCode: String,
    val quantity: String,
    val unit: String,
) {
    override fun toString(): String = "FoodIntakeItemDto(<oculto>)"
}

/** `RegisterFoodIntakeRequest` (cuerpo en línea de `POST /v1/me/food-intakes`). */
@Serializable
data class RegisterFoodIntakeRequestDto(
    @SerialName("intake_id") val intakeId: String,
    @SerialName("consumed_at") val consumedAt: String,
    @SerialName("meal_type") val mealType: String,
    val items: List<FoodIntakeItemDto>,
) {
    override fun toString(): String = "RegisterFoodIntakeRequestDto(<oculto>)"
}

/** `FoodIntakeResponse`: consumo normalizado en la versión 1, sin calorías ni nutrientes. */
@Serializable
data class FoodIntakeResponseDto(
    @SerialName("intake_id") val intakeId: String,
    @SerialName("athlete_id") val athleteId: String,
    @SerialName("consumed_at") val consumedAt: String,
    @SerialName("meal_type") val mealType: String,
    val items: List<FoodIntakeItemDto>,
    @SerialName("catalog_version") val catalogVersion: String,
    val version: Int,
    @SerialName("registered_at") val registeredAt: String,
) {
    override fun toString(): String = "FoodIntakeResponseDto(version=$version)"

    /**
     * `registered_at` si la respuesta confirma exactamente el consumo enviado ([sent]); `null` en
     * otro caso, para no presentar como confirmado algo distinto de lo capturado (CA1).
     */
    fun confirms(sent: RegisterFoodIntakeRequestDto): Instant? {
        if (version != CONFIRMED_VERSION || intakeId != sent.intakeId || mealType != sent.mealType) return null
        val consumed = runCatching { Instant.parse(consumedAt) }.getOrNull() ?: return null
        if (consumed != Instant.parse(sent.consumedAt) || items.size != sent.items.size) return null
        val same = items.zip(sent.items).all { (got, expected) ->
            got.foodCode == expected.foodCode && got.unit == expected.unit && sameQuantity(got.quantity, expected.quantity)
        }
        if (!same) return null
        return runCatching { Instant.parse(registeredAt) }.getOrNull()
    }

    private fun sameQuantity(a: String, b: String): Boolean {
        val left = a.toBigDecimalOrNull() ?: return false
        val right = b.toBigDecimalOrNull() ?: return false
        return left.compareTo(right) == 0
    }

    companion object {
        const val CONFIRMED_VERSION = 1
    }
}
