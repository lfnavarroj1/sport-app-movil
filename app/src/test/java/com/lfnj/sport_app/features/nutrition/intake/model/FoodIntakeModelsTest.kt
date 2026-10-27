package com.lfnj.sport_app.features.nutrition.intake.model

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.features.nutrition.intake.data.FoodCatalogDto
import com.lfnj.sport_app.features.nutrition.intake.data.FoodCatalogItemDto
import com.lfnj.sport_app.testing.FoodFixtures
import com.lfnj.sport_app.testing.TrainingFixtures
import com.lfnj.sport_app.features.training.session.model.TrainingOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** HU043: validadores, unidades permitidas y vigencia del catálogo (ADR-037 §1, §2 y §5). */
class FoodIntakeModelsTest {
    private val catalogDto = BffMobileClient.json.decodeFromString(FoodCatalogDto.serializer(), FoodFixtures.CATALOG)
    private val catalog = FoodCatalog(catalogDto.catalogVersion, catalogDto.items.mapNotNull { it.toModel() }, FoodFixtures.ETAG, FoodFixtures.NOW)
    private val names = mapOf("food.oats" to "Avena", "food.egg" to "Huevo", "food.milk" to "Leche", "food.water" to "Agua")

    private fun valid(text: String) = (FoodIntakeValidation.parseQuantity(text) as FoodIntakeValidation.Quantity.Valid).normalized
    private fun invalid(text: String) = (FoodIntakeValidation.parseQuantity(text) as FoodIntakeValidation.Quantity.Invalid).error

    @Test
    fun `la cantidad es una cadena decimal normalizada a un decimal entre 0_1 y 5000_0`() {
        assertEquals("150.0", valid("150"))
        assertEquals("0.1", valid("0.1"))
        assertEquals("5000.0", valid("5000"))
        assertEquals("12.5", valid("12,5"))
        assertEquals("7.0", valid(" 7 "))
    }

    @Test
    fun `cantidades fuera de rango o con formato invalido se rechazan`() {
        assertEquals(QuantityError.OUT_OF_RANGE, invalid("0"))
        assertEquals(QuantityError.OUT_OF_RANGE, invalid("0.0"))
        assertEquals(QuantityError.OUT_OF_RANGE, invalid("5000.1"))
        assertEquals(QuantityError.OUT_OF_RANGE, invalid("12345"))
        assertEquals(QuantityError.FORMAT, invalid(""))
        assertEquals(QuantityError.FORMAT, invalid("1.25"))
        assertEquals(QuantityError.FORMAT, invalid("-3"))
        assertEquals(QuantityError.FORMAT, invalid("abc"))
        assertEquals(QuantityError.FORMAT, invalid("1e3"))
    }

    @Test
    fun `consumed_at admite hasta 5 minutos en el futuro y 30 dias en el pasado`() {
        val now = FoodFixtures.NOW
        assertNull(FoodIntakeValidation.validateConsumedAt(now.plus(Duration.ofMinutes(5)), now))
        assertEquals(ConsumedAtError.IN_FUTURE, FoodIntakeValidation.validateConsumedAt(now.plus(Duration.ofMinutes(6)), now))
        assertNull(FoodIntakeValidation.validateConsumedAt(now.minus(Duration.ofDays(30)), now))
        assertEquals(ConsumedAtError.TOO_OLD, FoodIntakeValidation.validateConsumedAt(now.minus(Duration.ofDays(30)).minusSeconds(1), now))
    }

    @Test
    fun `cada alimento solo admite sus unidades permitidas`() {
        val oats = checkNotNull(catalog.item("OATS"))
        assertEquals(listOf(FoodUnit.G, FoodUnit.CUP, FoodUnit.TBSP), oats.allowedUnits)
        assertEquals(FoodUnit.G, oats.defaultUnit)
        assertTrue(oats.allows(FoodUnit.TBSP))
        assertFalse(oats.allows(FoodUnit.ML))
        assertFalse(checkNotNull(catalog.item("MILK")).allows(FoodUnit.G))
    }

    @Test
    fun `una unidad desconocida se descarta y un alimento sin unidades conocidas no se ofrece`() {
        val mixed = FoodCatalogItemDto("X", "food.x", "GRAIN", "OUNCE", listOf("OUNCE", "G"))
        val unknown = FoodCatalogItemDto("Y", "food.y", "GRAIN", "OUNCE", listOf("OUNCE"))

        assertEquals(FoodUnit.G, mixed.toModel()?.defaultUnit)
        assertEquals(listOf(FoodUnit.G), mixed.toModel()?.allowedUnits)
        assertNull(unknown.toModel())
    }

    @Test
    fun `CA6 el catalogo pasa a STALE despues de 24 horas`() {
        assertFalse(catalog.isStale(FoodFixtures.NOW.plus(Duration.ofHours(24))))
        assertTrue(catalog.isStale(FoodFixtures.NOW.plus(Duration.ofHours(24)).plusSeconds(1)))
    }

    @Test
    fun `el buscador local compara el nombre traducido sin tildes ni mayusculas y solo devuelve la cache`() {
        val label = { item: FoodCatalogItem -> names[item.labelKey] ?: item.foodCode }

        assertEquals(listOf("WATER"), catalog.search("AGUA", label).map { it.foodCode })
        assertEquals(listOf("MILK"), catalog.search("lé", label).map { it.foodCode })
        assertEquals(listOf("WATER", "OATS", "EGG", "MILK"), catalog.search("", label).map { it.foodCode })
        assertTrue(catalog.search("pizza", label).isEmpty())
    }

    @Test
    fun `las categorias y comidas corresponden al contrato`() {
        assertEquals(FoodCategory.BEVERAGE, catalog.item("WATER")?.category)
        assertEquals(MealType.HYDRATION, MealType.fromWire("HYDRATION"))
        assertNull(MealType.fromWire("BRUNCH"))
    }

    @Test
    fun `la particion es la huella del user_id de HU032 y los modelos no exponen datos`() {
        assertEquals(TrainingOwner.keyOf(TrainingFixtures.USER_ID), FoodOwner.keyOf(TrainingFixtures.USER_ID))
        assertFalse(TrainingFixtures.USER_ID in FoodOwner.keyOf(TrainingFixtures.USER_ID))
        val details = FoodIntakeDetails(MealType.LUNCH, Instant.EPOCH, listOf(FoodIntakeLine("OATS", "food.oats", "50.0", FoodUnit.G)))
        assertFalse("OATS" in details.toString() || "50.0" in details.toString() || "LUNCH" in details.toString())
    }
}
