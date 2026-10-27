package com.lfnj.sport_app.features.nutrition.intake.model

import androidx.annotation.StringRes
import com.lfnj.sport_app.R
import com.lfnj.sport_app.features.training.session.model.TrainingOwner
import java.math.BigDecimal
import java.text.Normalizer
import java.time.Duration
import java.time.Instant

/** Unidad de un alimento (ADR-037 HU043 §2): solo las del contrato. */
enum class FoodUnit(val wire: String, @param:StringRes val label: Int) {
    G("G", R.string.food_unit_g),
    ML("ML", R.string.food_unit_ml),
    UNIT("UNIT", R.string.food_unit_unit),
    CUP("CUP", R.string.food_unit_cup),
    TBSP("TBSP", R.string.food_unit_tbsp);

    companion object {
        fun fromWire(value: String?): FoodUnit? = entries.firstOrNull { it.wire == value }
    }
}

/** Comida del consumo (`meal_type`). */
enum class MealType(val wire: String, @param:StringRes val label: Int) {
    BREAKFAST("BREAKFAST", R.string.food_meal_breakfast),
    LUNCH("LUNCH", R.string.food_meal_lunch),
    DINNER("DINNER", R.string.food_meal_dinner),
    SNACK("SNACK", R.string.food_meal_snack),
    HYDRATION("HYDRATION", R.string.food_meal_hydration);

    companion object {
        fun fromWire(value: String?): MealType? = entries.firstOrNull { it.wire == value }
    }
}

/** Categoría del catálogo cerrado de alimentos (ADR-037 HU043 §1). */
enum class FoodCategory(val wire: String, @param:StringRes val label: Int) {
    GRAIN("GRAIN", R.string.food_category_grain),
    PROTEIN("PROTEIN", R.string.food_category_protein),
    DAIRY("DAIRY", R.string.food_category_dairy),
    FRUIT("FRUIT", R.string.food_category_fruit),
    VEGETABLE("VEGETABLE", R.string.food_category_vegetable),
    LEGUME("LEGUME", R.string.food_category_legume),
    FAT("FAT", R.string.food_category_fat),
    BEVERAGE("BEVERAGE", R.string.food_category_beverage),
    SUPPLEMENT("SUPPLEMENT", R.string.food_category_supplement);

    companion object {
        fun fromWire(value: String?): FoodCategory? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Alimento del catálogo confirmado. Las unidades que el cliente no conoce se descartan; un alimento
 * sin ninguna unidad conocida no se ofrece (no se inventan unidades).
 */
data class FoodCatalogItem(
    val foodCode: String,
    val labelKey: String,
    val category: FoodCategory?,
    val defaultUnit: FoodUnit,
    val allowedUnits: List<FoodUnit>,
) {
    fun allows(unit: FoodUnit): Boolean = unit in allowedUnits
}

/** Último catálogo confirmado por el BFF, con su `ETag` y la fecha de la última confirmación. */
data class FoodCatalog(
    val catalogVersion: String,
    val items: List<FoodCatalogItem>,
    val etag: String?,
    val syncedAt: Instant,
) {
    /** `STALE` a las 24 horas de la última confirmación (ADR-037 HU043 §5, CA6). */
    fun isStale(now: Instant): Boolean = Duration.between(syncedAt, now) > STALE_AFTER

    fun item(foodCode: String): FoodCatalogItem? = items.firstOrNull { it.foodCode == foodCode }

    /**
     * Buscador local: compara [query] con el nombre traducido del `label_key` ([label]) sin
     * distinguir mayúsculas ni tildes. Solo devuelve alimentos de la caché (CA6).
     */
    fun search(query: String, label: (FoodCatalogItem) -> String): List<FoodCatalogItem> {
        val needle = normalize(query)
        val matches = if (needle.isEmpty()) items else items.filter { normalize(label(it)).contains(needle) || normalize(it.foodCode).contains(needle) }
        return matches.sortedBy { normalize(label(it)) }
    }

    companion object {
        val STALE_AFTER: Duration = Duration.ofHours(24)

        private val MARKS = Regex("\\p{M}+")

        fun normalize(text: String): String =
            MARKS.replace(Normalizer.normalize(text.trim().lowercase(), Normalizer.Form.NFD), "").replace('_', ' ')
    }
}

/** Línea de un consumo con la cantidad ya normalizada a un decimal (`150.0`). */
data class FoodIntakeLine(
    val foodCode: String,
    /** Clave de traducción del catálogo con el que se capturó; `null` si no se conoce. */
    val labelKey: String?,
    val quantity: String,
    val unit: FoodUnit,
) {
    override fun toString(): String = "FoodIntakeLine(<oculto>)"
}

/** Datos de negocio de un consumo; en el dispositivo solo existen cifrados. */
data class FoodIntakeDetails(
    val mealType: MealType,
    val consumedAt: Instant,
    val items: List<FoodIntakeLine>,
) {
    override fun toString(): String = "FoodIntakeDetails(<oculto>)"
}

/**
 * Estado de sincronización de un consumo (ADR-026 §2 y ADR-029 offline, reutilizado por ADR-037).
 * Solo [SYNCED] significa que el backend respondió `201` para ese `intake_id`.
 */
enum class FoodSyncState { PENDING_SYNC, SYNCED, SYNC_FAILED }

/** Causa explicable de [FoodSyncState.SYNC_FAILED]; se guarda solo como código, sin datos. */
enum class FoodSyncFailure {
    /** `422` por un alimento o una unidad que el catálogo vigente ya no admite. */
    CATALOG_CHANGED,
    /** Otro `422 validation_error` (campo y causa en el consumo). */
    VALIDATION,
    /** `403 permission_required`: la cuenta no es de deportista. */
    PERMISSION_REQUIRED,
    /** `409 intake_id_reused` o `idempotency_key_reused`: el servidor no confirmó este consumo. */
    CONFLICT,
    /** Vencido a los 30 días sin sincronizarse, o `422 too_old`. */
    EXPIRED,
    /** Otro rechazo del servicio que no se reintenta automáticamente. */
    REJECTED,
    /** La respuesta no corresponde al consumo enviado. */
    INVALID_RESPONSE,
    /** El registro cifrado no pudo leerse (clave del Keystore invalidada o dato alterado). */
    UNREADABLE;

    companion object {
        fun fromWire(value: String?): FoodSyncFailure? = entries.firstOrNull { it.name == value }
    }
}

/**
 * Consumo guardado en el dispositivo. [details] es `null` si el registro cifrado no pudo leerse;
 * entonces el estado es [FoodSyncState.SYNC_FAILED] con [FoodSyncFailure.UNREADABLE].
 */
data class LocalFoodIntake(
    val intakeId: String,
    val state: FoodSyncState,
    val failure: FoodSyncFailure?,
    val failureField: String?,
    val failureIssue: String?,
    val createdAt: Instant,
    val details: FoodIntakeDetails?,
    /** `registered_at` del backend; solo existe en [FoodSyncState.SYNCED]. */
    val registeredAt: Instant?,
) {
    /** Puede retirarse del dispositivo: nunca un consumo pendiente. */
    val dismissible: Boolean get() = state == FoodSyncState.SYNC_FAILED
}

enum class QuantityError { FORMAT, OUT_OF_RANGE }

enum class ConsumedAtError { IN_FUTURE, TOO_OLD }

/**
 * Validaciones de experiencia del formulario (ADR-037 HU043 §2). El backend sigue siendo la
 * autoridad: un rechazo llega como `SYNC_FAILED` con su causa.
 */
object FoodIntakeValidation {
    const val MAX_ITEMS = 20
    val MIN_QUANTITY = BigDecimal("0.1")
    val MAX_QUANTITY = BigDecimal("5000.0")
    val MAX_FUTURE: Duration = Duration.ofMinutes(5)
    val MAX_AGE: Duration = Duration.ofDays(30)

    /** Hasta cuatro enteros y como máximo un decimal; se admite la coma decimal regional. */
    private val QUANTITY = Regex("^\\d{1,4}([.,]\\d)?$")

    sealed interface Quantity {
        /** [normalized] con exactamente un decimal, como lo normaliza el servicio (`150` → `150.0`). */
        data class Valid(val normalized: String) : Quantity
        data class Invalid(val error: QuantityError) : Quantity
    }

    fun parseQuantity(text: String): Quantity {
        val trimmed = text.trim()
        if (!QUANTITY.matches(trimmed)) return Quantity.Invalid(if (trimmed.isEmpty()) QuantityError.FORMAT else formatOrRange(trimmed))
        val value = BigDecimal(trimmed.replace(',', '.'))
        if (value < MIN_QUANTITY || value > MAX_QUANTITY) return Quantity.Invalid(QuantityError.OUT_OF_RANGE)
        return Quantity.Valid(value.setScale(1).toPlainString())
    }

    /** Un número válido con demasiados dígitos se informa como fuera de rango, no como formato. */
    private fun formatOrRange(text: String): QuantityError =
        if (Regex("^\\d+([.,]\\d)?$").matches(text)) QuantityError.OUT_OF_RANGE else QuantityError.FORMAT

    fun validateConsumedAt(consumedAt: Instant, now: Instant): ConsumedAtError? = when {
        consumedAt.isAfter(now.plus(MAX_FUTURE)) -> ConsumedAtError.IN_FUTURE
        consumedAt.isBefore(now.minus(MAX_AGE)) -> ConsumedAtError.TOO_OLD
        else -> null
    }
}

/** Partición local por la huella SHA-256 del `user_id`, la misma de HU032 (ADR-029 offline §4). */
object FoodOwner {
    fun keyOf(userId: String): String = TrainingOwner.keyOf(userId)
}
