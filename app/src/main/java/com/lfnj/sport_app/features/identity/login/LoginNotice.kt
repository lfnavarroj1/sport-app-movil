package com.lfnj.sport_app.features.identity.login

/** Aviso que la pantalla de acceso muestra al llegar desde otra pantalla. */
enum class LoginNotice {
    /** La sesión venció o el BFF rechazó la renovación (HU003). */
    SESSION_EXPIRED,
    /** Se cerró la sesión localmente, pero la revocación remota no se confirmó (HU004 CA6). */
    LOGOUT_REMOTE_UNCONFIRMED;

    companion object {
        fun fromRoute(value: String?): LoginNotice? = entries.firstOrNull { it.name == value }
    }
}
