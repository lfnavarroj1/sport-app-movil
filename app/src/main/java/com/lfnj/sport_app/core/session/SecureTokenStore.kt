package com.lfnj.sport_app.core.session

/**
 * Almacenamiento seguro de credenciales renovables. El andamiaje solo define la interfaz:
 * la implementación y el mecanismo del sistema operativo los aprueba cada HU que la necesite.
 * Ningún valor guardado aquí puede registrarse en logs, analítica ni copias de seguridad.
 */
interface SecureTokenStore {
    fun read(): String?
    fun write(value: String)
    fun clear()
}
