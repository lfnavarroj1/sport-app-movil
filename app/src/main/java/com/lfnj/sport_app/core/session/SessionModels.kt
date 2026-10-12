package com.lfnj.sport_app.core.session

/** Tipos de actor del catálogo aprobado (`SessionUser.actor_type`). */
enum class ActorType(val wire: String) {
    ATHLETE("athlete"),
    ORGANIZER("organizer"),
    SERVICE_PROVIDER("service_provider");

    /** La aplicación móvil es exclusiva para deportistas (HU003 CA11, ADR-025 §10). */
    val allowedInMobile: Boolean get() = this == ATHLETE

    companion object {
        /** Devuelve `null` para un valor ausente o desconocido: nunca asume un rol (ADR-025 §7). */
        fun fromWire(value: String?): ActorType? = entries.firstOrNull { it.wire == value }
    }
}

/** Estado de la suscripción del deportista (`CapabilitiesResponse.subscription_status`). */
enum class SubscriptionStatus(val wire: String) {
    ACTIVE("ACTIVE"),
    PROVISIONING("PROVISIONING"),
    NOT_APPLICABLE("NOT_APPLICABLE");

    companion object {
        fun fromWire(value: String): SubscriptionStatus? = entries.firstOrNull { it.wire == value }
    }
}

data class SessionUser(
    val userId: String,
    val fullName: String,
    val email: String,
    val actorType: ActorType,
) {
    override fun toString(): String = "SessionUser(userId=$userId, actorType=$actorType)"
}

/** Vista de permisos y capacidades vigentes que entrega el BFF tras el inicio de sesión. */
data class AccessSnapshot(
    val actorType: ActorType,
    val subscriptionStatus: SubscriptionStatus,
    val planCode: String?,
    val permissions: Set<String>,
    val capabilities: Set<String>,
)

/** Sesión autenticada en memoria. El access token nunca se persiste. */
internal data class AuthSession(
    val user: SessionUser,
    val accessToken: String,
    val accessExpiresAtMillis: Long,
    val refreshToken: String,
) {
    override fun toString(): String = "AuthSession(user=$user, accessExpiresAtMillis=$accessExpiresAtMillis)"
}
