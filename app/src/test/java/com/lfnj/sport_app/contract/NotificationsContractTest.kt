package com.lfnj.sport_app.contract

import com.lfnj.sport_app.features.notifications.data.NotificationDto
import com.lfnj.sport_app.features.notifications.data.NotificationPreferencesDto
import com.lfnj.sport_app.features.notifications.data.NotificationPreferencesRequestDto
import com.lfnj.sport_app.features.notifications.data.NotificationsApi
import com.lfnj.sport_app.features.notifications.data.NotificationsPageDto
import com.lfnj.sport_app.features.notifications.model.NotificationPriority
import com.lfnj.sport_app.features.notifications.model.NotificationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.PUT

/** HU039 en el cliente móvil frente a la copia exacta de `bff-mobile` (ADR-045 §11, tag `notifications`). */
class NotificationsContractTest {
    private val contract = OpenApiContract.load()

    @Test
    fun `las operaciones de la bandeja y las preferencias existen con sus parametros`() {
        contract.assertApiMatches(NotificationsApi::class.java)
        NotificationsApi::class.java.declaredMethods.mapNotNull { it.getAnnotation(PUT::class.java) }.forEach {
            assertTrue("put /${it.value} no existe en el contrato", contract.hasOperation("put", "/" + it.value))
        }
        assertEquals(setOf("limit", "cursor"), contract.parameters("get", "/v1/me/notifications", "query"))
        assertEquals(setOf("notification_id"), contract.parameters("post", "/v1/me/notifications/{notification_id}/read", "path"))
        assertTrue("authorization" in contract.parameters("get", "/v1/me/notification-preferences", "header"))
    }

    @Test
    fun `los modelos del cliente corresponden a los esquemas`() {
        contract.assertModelMatches("NotificationResponse", NotificationDto.serializer())
        contract.assertModelMatches("NotificationsPageResponse", NotificationsPageDto.serializer())
        contract.assertModelMatches("NotificationPreferencesResponse", NotificationPreferencesDto.serializer())
        contract.assertRequestBodyMatches("put", "/v1/me/notification-preferences", NotificationPreferencesRequestDto.serializer())
    }

    @Test
    fun `tipos y prioridades coinciden con los que valida el cliente`() {
        assertEquals(NotificationType.entries.map { it.name }.toSet(), contract.enumValues("NotificationResponse", "type"))
        assertEquals(NotificationPriority.entries.map { it.name }.toSet(), contract.enumValues("NotificationResponse", "priority"))
    }

    @Test
    fun `el cliente trata todas las respuestas documentadas`() {
        assertEquals(setOf("200", "401", "403", "422", "503", "504"), contract.responseCodes("get", "/v1/me/notifications"))
        assertEquals(
            setOf("200", "401", "403", "404", "422", "503", "504"),
            contract.responseCodes("post", "/v1/me/notifications/{notification_id}/read"),
        )
        assertEquals(setOf("200", "401", "403", "422", "503", "504"), contract.responseCodes("get", "/v1/me/notification-preferences"))
        assertEquals(
            setOf("200", "401", "403", "409", "422", "503", "504"),
            contract.responseCodes("put", "/v1/me/notification-preferences"),
        )
    }

    @Test
    fun `la bandeja no expone el destinatario`() {
        assertTrue("recipient_id" !in contract.schemaProperties("NotificationResponse"))
    }
}
