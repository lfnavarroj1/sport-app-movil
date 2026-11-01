package com.lfnj.sport_app.contract

import com.lfnj.sport_app.features.nutrition.reminders.data.ReminderPreferencesRequestDto
import com.lfnj.sport_app.features.nutrition.reminders.data.ReminderPreferencesResponseDto
import com.lfnj.sport_app.features.nutrition.reminders.data.ReminderRuleDto
import com.lfnj.sport_app.features.nutrition.reminders.data.ReminderRulesResponseDto
import com.lfnj.sport_app.features.nutrition.reminders.data.ReminderSettingDto
import com.lfnj.sport_app.features.nutrition.reminders.data.SessionRemindersApi
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderType
import com.lfnj.sport_app.features.nutrition.reminders.model.SESSION_REMINDERS_CAPABILITY
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.PUT

/** HU038 en el cliente móvil frente a la copia exacta de `bff-mobile` 0.18.0 (ADR-045 §4–§5). */
class SessionRemindersContractTest {
    private val contract = OpenApiContract.load()
    private val rules = "/v1/session-reminder-rules"
    private val preferences = "/v1/me/session-reminder-preferences"

    @Test
    fun `las operaciones consumidas existen con su metodo y encabezado de autorizacion`() {
        contract.assertApiMatches(SessionRemindersApi::class.java)
        val put = SessionRemindersApi::class.java.declaredMethods.mapNotNull { it.getAnnotation(PUT::class.java)?.value }
        assertEquals(listOf("v1/me/session-reminder-preferences"), put)
        assertTrue(contract.hasOperation("put", preferences))
        assertTrue("authorization" in contract.parameters("get", rules, "header"))
        assertTrue("authorization" in contract.parameters("get", preferences, "header"))
        assertTrue("authorization" in contract.parameters("put", preferences, "header"))
    }

    @Test
    fun `los modelos de reglas y preferencias corresponden al contrato`() {
        contract.assertModelMatches("ReminderRulesResponse", ReminderRulesResponseDto.serializer())
        contract.assertModelMatches("ReminderRuleResponse", ReminderRuleDto.serializer())
        contract.assertModelMatches("ReminderPreferencesResponse", ReminderPreferencesResponseDto.serializer())
        contract.assertModelMatches("HydrationSetting", ReminderSettingDto.serializer())
        contract.assertModelMatches("NutritionSetting", ReminderSettingDto.serializer())
        contract.assertRequestBodyMatches("put", preferences, ReminderPreferencesRequestDto.serializer())
    }

    @Test
    fun `el catalogo de tipos es el del contrato`() {
        OpenApiContract.assertSameElements(
            "ReminderType",
            reminderTypes(),
            ReminderType.entries.map { it.name }.toSet(),
        )
    }

    /** `ReminderType` es un esquema `enum` de nivel superior: se lee directamente de la copia. */
    private fun reminderTypes(): Set<String> {
        val text = checkNotNull(javaClass.getResourceAsStream(OpenApiContract.RESOURCE)).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return Json.parseToJsonElement(text).jsonObject.getValue("components").jsonObject.getValue("schemas").jsonObject
            .getValue("ReminderType").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }.toSet()
    }

    @Test
    fun `los rangos documentados coinciden con los del ruleset de ADR-045`() {
        val hydration = contract.propertySchema("HydrationSetting", "interval_minutes")
        val nutrition = contract.propertySchema("NutritionSetting", "interval_minutes")
        assertEquals(10.0, hydration.getValue("minimum").jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(60.0, hydration.getValue("maximum").jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(30.0, nutrition.getValue("minimum").jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(120.0, nutrition.getValue("maximum").jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test
    fun `los errores que maneja el cliente estan documentados`() {
        assertTrue(contract.responseCodes("get", rules).containsAll(setOf("200", "401", "403", "503", "504")))
        assertTrue(contract.responseCodes("get", preferences).containsAll(setOf("200", "401", "403", "503", "504")))
        assertTrue(contract.responseCodes("put", preferences).containsAll(setOf("200", "401", "403", "409", "422", "503", "504")))
        assertEquals("nutrition.session.reminders", SESSION_REMINDERS_CAPABILITY)
    }
}
