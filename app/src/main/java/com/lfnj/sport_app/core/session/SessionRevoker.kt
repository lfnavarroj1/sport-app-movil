package com.lfnj.sport_app.core.session

/** Resultado de solicitar al BFF la revocación de la credencial renovable (HU004). */
enum class RevocationResult {
    /** El BFF confirmó la revocación con `204` (CA1, CA5). */
    CONFIRMED,
    /** No había nada que revocar: la credencial ya venció (CA2). */
    ALREADY_CLOSED,
    /** Sin confirmación remota: la credencial quedó en la cola cifrada y se reintentará con red (CA6, CA7). */
    PENDING,
    /** Sin confirmación remota y sin entrada en la cola (no se pudo guardar o el BFF la rechazó). */
    NOT_CONFIRMED,
}

/**
 * Puerto hacia `POST /v1/sessions/revoke` de `bff-mobile` (HU004, contrato 0.4.0 en adelante).
 * La implementación es la cola cifrada de revocación ([com.lfnj.sport_app.core.session.revocation.RevocationQueue]).
 */
fun interface SessionRevoker {
    /**
     * Revoca [refreshToken], cuya vigencia termina en [expiresAtMillis] (`null` si no se conoce).
     * No debe registrar ni conservar el token fuera de la cola cifrada.
     */
    suspend fun revoke(refreshToken: String, expiresAtMillis: Long?): RevocationResult
}
