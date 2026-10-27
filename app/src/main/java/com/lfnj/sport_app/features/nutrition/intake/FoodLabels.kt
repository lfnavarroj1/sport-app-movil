package com.lfnj.sport_app.features.nutrition.intake

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lfnj.sport_app.R

/**
 * Textos del `label_key` de cada alimento del catálogo `food-catalog-2026-10-13.v1`. Son los mismos
 * de la web (`public/i18n/es.json`, sección `food`, frontend `develop` 7e40434). Una clave que el
 * cliente no conoce se muestra con su `food_code`, igual que la web; nunca se inventa un nombre.
 */
object FoodLabels {
    private val labels: Map<String, Int> = mapOf(
        "food.oats" to R.string.food_name_oats,
        "food.white_rice" to R.string.food_name_white_rice,
        "food.brown_rice" to R.string.food_name_brown_rice,
        "food.quinoa" to R.string.food_name_quinoa,
        "food.whole_wheat_bread" to R.string.food_name_whole_wheat_bread,
        "food.pasta" to R.string.food_name_pasta,
        "food.corn_arepa" to R.string.food_name_corn_arepa,
        "food.chicken_breast" to R.string.food_name_chicken_breast,
        "food.beef" to R.string.food_name_beef,
        "food.pork_loin" to R.string.food_name_pork_loin,
        "food.salmon" to R.string.food_name_salmon,
        "food.tuna" to R.string.food_name_tuna,
        "food.shrimp" to R.string.food_name_shrimp,
        "food.egg" to R.string.food_name_egg,
        "food.tofu" to R.string.food_name_tofu,
        "food.milk" to R.string.food_name_milk,
        "food.greek_yogurt" to R.string.food_name_greek_yogurt,
        "food.fresh_cheese" to R.string.food_name_fresh_cheese,
        "food.banana" to R.string.food_name_banana,
        "food.apple" to R.string.food_name_apple,
        "food.orange" to R.string.food_name_orange,
        "food.berries" to R.string.food_name_berries,
        "food.spinach" to R.string.food_name_spinach,
        "food.broccoli" to R.string.food_name_broccoli,
        "food.carrot" to R.string.food_name_carrot,
        "food.tomato" to R.string.food_name_tomato,
        "food.black_beans" to R.string.food_name_black_beans,
        "food.lentils" to R.string.food_name_lentils,
        "food.chickpeas" to R.string.food_name_chickpeas,
        "food.olive_oil" to R.string.food_name_olive_oil,
        "food.avocado" to R.string.food_name_avocado,
        "food.peanut_butter" to R.string.food_name_peanut_butter,
        "food.almonds" to R.string.food_name_almonds,
        "food.tahini" to R.string.food_name_tahini,
        "food.water" to R.string.food_name_water,
        "food.coffee" to R.string.food_name_coffee,
        "food.orange_juice" to R.string.food_name_orange_juice,
        "food.sports_drink" to R.string.food_name_sports_drink,
        "food.whey_protein" to R.string.food_name_whey_protein,
        "food.plant_protein" to R.string.food_name_plant_protein,
        "food.electrolytes" to R.string.food_name_electrolytes,
    )

    @StringRes
    fun resourceOf(labelKey: String?): Int? = labelKey?.let(labels::get)

    /** Nombre del alimento: su texto traducido o, si la clave no se conoce, el `food_code`. */
    @Composable
    fun name(labelKey: String?, foodCode: String): String = resourceOf(labelKey)?.let { stringResource(it) } ?: foodCode
}
