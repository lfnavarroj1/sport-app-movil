package com.lfnj.sport_app.core.session

/** Resultado de solicitar al BFF la revocación de la credencial renovable (HU004). */
enum class RevocationResult {
    /** El BFF confirmó la revocación (CA1, CA5). */
    CONFIRMED,
    /** La sesión ya estaba cerrada o la credencial ya no es válida: éxito idempotente (CA2, CA7). */
    ALREADY_CLOSED,
    /** Sin conectividad o BFF no disponible: la revocación remota no se pudo confirmar (CA6). */
    NOT_CONFIRMED,
}

/**
 * Puerto hacia la operación de cierre de sesión de `bff-mobile`.
 *
 * `BLOQUEADO`: `bff-mobile` 0.3.0 no expone esa operación (`tareas-hu/HU004-cerrar-sesion.md`,
 * dependencia de `T-HU004-BE`). La implementación HTTP se agrega cuando el backend publique la
 * versión del contrato con la operación, la credencial de entrada y sus respuestas; mientras
 * tanto el contenedor no provee ninguna y la opción «Cerrar sesión» permanece deshabilitada.
 */
fun interface SessionRevoker {
    /** No debe registrar ni conservar [refreshToken] fuera de la llamada. */
    suspend fun revoke(refreshToken: String): RevocationResult
}
