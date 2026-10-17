package com.lfnj.sport_app.features.identity.login

/** Aviso que la pantalla de acceso muestra al llegar desde otra pantalla. */
enum class LoginNotice {
    /** La sesión venció o el BFF rechazó la renovación (HU003). */
    SESSION_EXPIRED,
    /** Sesión cerrada en este dispositivo y revocación confirmada (HU004 CA5). */
    LOGGED_OUT,
    /** Sesión cerrada en este dispositivo; la revocación remota quedó pendiente en la cola (HU004 CA6, CA7). */
    LOGOUT_REMOTE_PENDING,
    /** Sesión cerrada en este dispositivo, pero la revocación remota no se confirmó ni quedó pendiente. */
    LOGOUT_REMOTE_UNCONFIRMED;

    companion object {
        fun fromRoute(value: String?): LoginNotice? = entries.firstOrNull { it.name == value }
    }
}
