package com.lfnj.sport_app.contract

import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.CapabilitiesResponseDto
import com.lfnj.sport_app.core.session.LoginRequestDto
import com.lfnj.sport_app.core.session.RefreshSessionRequestDto
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionResponseDto
import com.lfnj.sport_app.core.session.SessionUserDto
import com.lfnj.sport_app.core.session.SubscriptionStatus
import com.lfnj.sport_app.features.identity.login.LoginViewModel
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

/** HU003 en el cliente móvil frente a `bff-mobile` 0.3.0. */
class SessionContractTest {
    private val contract = OpenApiContract.load()

    @Test
    fun `las operaciones de sesion y capacidades existen en el contrato`() {
        contract.assertApiMatches(SessionApi::class.java)
    }

    @Test
    fun `los modelos de sesion corresponden al contrato`() {
        contract.assertModelMatches("LoginRequest", LoginRequestDto.serializer())
        contract.assertModelMatches("RefreshSessionRequest", RefreshSessionRequestDto.serializer())
        contract.assertModelMatches("SessionResponse", SessionResponseDto.serializer())
        contract.assertModelMatches("SessionUser", SessionUserDto.serializer())
        contract.assertModelMatches("CapabilitiesResponse", CapabilitiesResponseDto.serializer())
    }

    @Test
    fun `el catalogo de actores y estados de suscripcion coincide con el contrato`() {
        OpenApiContract.assertSameElements(
            "SessionUser.actor_type",
            contract.enumValues("SessionUser", "actor_type"),
            ActorType.entries.map { it.wire }.toSet(),
        )
        OpenApiContract.assertSameElements(
            "CapabilitiesResponse.actor_type",
            contract.enumValues("CapabilitiesResponse", "actor_type"),
            ActorType.entries.map { it.wire }.toSet(),
        )
        OpenApiContract.assertSameElements(
            "CapabilitiesResponse.subscription_status",
            contract.enumValues("CapabilitiesResponse", "subscription_status"),
            SubscriptionStatus.entries.map { it.wire }.toSet(),
        )
    }

    @Test
    fun `el cliente trata todas las respuestas documentadas`() {
        assertTrue(contract.responseCodes("post", "/v1/sessions").containsAll(setOf("200", "401", "422", "429", "503", "504")))
        assertTrue(contract.responseCodes("post", "/v1/sessions/refresh").containsAll(setOf("200", "401", "422", "503", "504")))
        assertTrue(contract.responseCodes("get", "/v1/me/capabilities").containsAll(setOf("200", "401", "403", "503")))
    }

    @Test
    fun `la validacion local usa los limites del contrato`() {
        assertTrue(contract.maxLength("LoginRequest", "email") == LoginViewModel.MAX_EMAIL_LENGTH)
        assertTrue(contract.maxLength("LoginRequest", "password") == LoginViewModel.MAX_PASSWORD_LENGTH)
    }

    private fun OpenApiContract.maxLength(schema: String, property: String): Int =
        propertySchema(schema, property).getValue("maxLength").jsonPrimitive.int
}
