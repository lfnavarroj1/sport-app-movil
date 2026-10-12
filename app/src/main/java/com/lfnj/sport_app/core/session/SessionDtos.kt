package com.lfnj.sport_app.core.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Modelos del contrato `bff-mobile` 0.3.0 para HU003. Los que transportan credenciales
// redefinen toString() para que un registro accidental nunca exponga contraseñas ni tokens.

/** `LoginRequest`. */
@Serializable
data class LoginRequestDto(
    val email: String,
    val password: String,
) {
    override fun toString(): String = "LoginRequestDto(email=<oculto>, password=<oculto>)"
}

/** `RefreshSessionRequest`. */
@Serializable
data class RefreshSessionRequestDto(
    @SerialName("refresh_token") val refreshToken: String,
) {
    override fun toString(): String = "RefreshSessionRequestDto(refresh_token=<oculto>)"
}

/** `SessionResponse` (respuesta de `POST /v1/sessions` y `POST /v1/sessions/refresh`). */
@Serializable
data class SessionResponseDto(
    @SerialName("token_type") val tokenType: String,
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("refresh_expires_in") val refreshExpiresIn: Long,
    val user: SessionUserDto,
) {
    override fun toString(): String =
        "SessionResponseDto(token_type=$tokenType, access_token=<oculto>, expires_in=$expiresIn, " +
            "refresh_token=<oculto>, refresh_expires_in=$refreshExpiresIn, user=$user)"
}

/**
 * `SessionUser`. `actor_type` se recibe como texto opcional para poder rechazar de forma segura
 * un valor ausente o desconocido (HU003 CA10) en lugar de fallar la deserialización.
 */
@Serializable
data class SessionUserDto(
    @SerialName("user_id") val userId: String,
    @SerialName("full_name") val fullName: String,
    val email: String,
    @SerialName("actor_type") val actorType: String? = null,
) {
    override fun toString(): String = "SessionUserDto(user_id=$userId, actor_type=$actorType)"
}

/** `CapabilitiesResponse` de `GET /v1/me/capabilities`. */
@Serializable
data class CapabilitiesResponseDto(
    @SerialName("actor_type") val actorType: String? = null,
    @SerialName("subscription_status") val subscriptionStatus: String,
    @SerialName("plan_code") val planCode: String? = null,
    @SerialName("plan_version") val planVersion: Int? = null,
    @SerialName("catalog_version") val catalogVersion: String? = null,
    @SerialName("authorization_policy_version") val authorizationPolicyVersion: String,
    val permissions: List<String>,
    val capabilities: List<String>,
    @SerialName("evaluated_at") val evaluatedAt: String,
)
