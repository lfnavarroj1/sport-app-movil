package com.lfnj.sport_app.contract

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.features.nutrition.intake.data.FoodCatalogDto
import com.lfnj.sport_app.features.nutrition.intake.data.FoodCatalogItemDto
import com.lfnj.sport_app.features.nutrition.intake.data.FoodIntakeApi
import com.lfnj.sport_app.features.nutrition.intake.data.FoodIntakeItemDto
import com.lfnj.sport_app.features.nutrition.intake.data.FoodIntakeResponseDto
import com.lfnj.sport_app.features.nutrition.intake.data.RegisterFoodIntakeRequestDto
import com.lfnj.sport_app.features.nutrition.intake.model.FoodCategory
import com.lfnj.sport_app.features.nutrition.intake.model.FoodUnit
import com.lfnj.sport_app.features.nutrition.intake.model.MealType
import com.lfnj.sport_app.testing.FoodFixtures
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** HU043 en el cliente móvil frente a la copia exacta de `bff-mobile` 0.21.0 (ADR-037). */
class FoodIntakeContractTest {
    private val contract = OpenApiContract.load()
    private val json = BffMobileClient.json

    @Test
    fun `las dos operaciones de ADR-037 existen con If-None-Match e Idempotency-Key`() {
        assertEquals("0.21.0", contract.version)
        contract.assertApiMatches(FoodIntakeApi::class.java)
        assertTrue("if-none-match" in contract.parameters("get", "/v1/food-catalog", "header"))
        assertTrue("idempotency-key" in contract.parameters("post", "/v1/me/food-intakes", "header"))
    }

    @Test
    fun `los cuerpos y las respuestas corresponden al contrato`() {
        contract.assertModelMatches("FoodCatalogResponse", FoodCatalogDto.serializer())
        contract.assertModelMatches("FoodCatalogItem", FoodCatalogItemDto.serializer())
        contract.assertModelMatches("FoodIntakeItem", FoodIntakeItemDto.serializer())
        contract.assertModelMatches("FoodIntakeResponse", FoodIntakeResponseDto.serializer())
        contract.assertRequestBodyMatches("post", "/v1/me/food-intakes", RegisterFoodIntakeRequestDto.serializer())
    }

    @Test
    fun `los catalogos cerrados del cliente son exactamente los del contrato`() {
        fun enumOf(schema: String, property: String) =
            contract.propertySchema(schema, property).getValue("enum").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(enumOf("FoodIntakeResponse", "meal_type"), MealType.entries.map { it.wire })
        assertEquals(enumOf("FoodIntakeItem", "unit"), FoodUnit.entries.map { it.wire })
        assertEquals(enumOf("FoodCatalogItem", "default_unit"), FoodUnit.entries.map { it.wire })
        assertEquals(enumOf("FoodCatalogItem", "category"), FoodCategory.entries.map { it.wire })
        val allowed = contract.propertySchema("FoodCatalogItem", "allowed_units").getValue("items").jsonObject.getValue("enum").jsonArray
        assertEquals(allowed.map { it.jsonPrimitive.content }, FoodUnit.entries.map { it.wire })
    }

    @Test
    fun `el cliente trata todas las respuestas documentadas`() {
        assertTrue(contract.responseCodes("get", "/v1/food-catalog").containsAll(setOf("200", "304", "401", "403", "503", "504")))
        assertTrue(contract.responseCodes("post", "/v1/me/food-intakes").containsAll(setOf("201", "401", "403", "409", "422", "503", "504")))
    }

    @Test
    fun `el cuerpo no lleva athlete_id, texto libre ni calorias`() {
        val descriptor = RegisterFoodIntakeRequestDto.serializer().descriptor
        val fields = (0 until descriptor.elementsCount).map(descriptor::getElementName).toSet()

        assertEquals(setOf("intake_id", "consumed_at", "meal_type", "items"), fields)
    }

    @Test
    fun `el catalogo de ejemplo se decodifica y respeta las unidades permitidas`() {
        val catalog = json.decodeFromString(FoodCatalogDto.serializer(), FoodFixtures.CATALOG)
        val milk = checkNotNull(catalog.items.first { it.foodCode == "MILK" }.toModel())

        assertEquals(FoodFixtures.CATALOG_VERSION, catalog.catalogVersion)
        assertEquals(listOf(FoodUnit.ML, FoodUnit.CUP), milk.allowedUnits)
        assertEquals("food.milk", milk.labelKey)
    }

    @Test
    fun `CA1 la respuesta version 1 confirma el consumo enviado y otra no`() {
        val sent = RegisterFoodIntakeRequestDto("i-1", "2026-10-24T12:30:00Z", "BREAKFAST", listOf(FoodIntakeItemDto("OATS", "50.0", "G")))
        fun decode(body: String) = json.decodeFromString(FoodIntakeResponseDto.serializer(), body)

        assertEquals(Instant.parse("2026-10-24T12:35:02Z"), decode(FoodFixtures.response("i-1")).confirms(sent))
        assertEquals("150 y 150.0 son la misma cantidad", Instant.parse("2026-10-24T12:35:02Z"),
            decode(FoodFixtures.response("i-1", items = """[{"food_code":"OATS","quantity":"50","unit":"G"}]""")).confirms(sent))
        assertNull(decode(FoodFixtures.response("i-1", version = 2)).confirms(sent))
        assertNull(decode(FoodFixtures.response("i-2")).confirms(sent))
        assertNull(decode(FoodFixtures.response("i-1", mealType = "LUNCH")).confirms(sent))
        assertNull(decode(FoodFixtures.response("i-1", items = """[{"food_code":"OATS","quantity":"50.0","unit":"CUP"}]""")).confirms(sent))
    }

    @Test
    fun `los modelos con datos de alimentacion no los exponen al convertirse en texto`() {
        val body = RegisterFoodIntakeRequestDto("i-1", "2026-10-24T12:30:00Z", "BREAKFAST", listOf(FoodIntakeItemDto("OATS", "50.0", "G")))
        val response = json.decodeFromString(FoodIntakeResponseDto.serializer(), FoodFixtures.response("i-1"))
        listOf(body.toString(), response.toString(), body.items.single().toString()).forEach { text ->
            assertFalse(text, "OATS" in text || "50.0" in text || "BREAKFAST" in text)
        }
    }
}
