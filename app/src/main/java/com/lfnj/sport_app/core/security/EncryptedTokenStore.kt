package com.lfnj.sport_app.core.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import com.lfnj.sport_app.core.session.SecureTokenStore
import java.security.GeneralSecurityException

/**
 * Guarda el refresh token solo como texto cifrado por [TokenCipher] en preferencias privadas de
 * la aplicación, excluidas de la copia de seguridad y de la transferencia entre dispositivos
 * (`res/xml/backup_rules.xml` y `data_extraction_rules.xml`). Si el valor no se puede descifrar
 * (clave invalidada o dato alterado) se elimina y se trata como ausente.
 */
class EncryptedTokenStore(
    private val preferences: SharedPreferences,
    private val cipher: TokenCipher,
) : SecureTokenStore {

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE),
        KeystoreTokenCipher(),
    )

    override fun read(): String? {
        val stored = preferences.getString(KEY_REFRESH_TOKEN, null) ?: return null
        val token = try {
            cipher.decrypt(Base64.decode(stored, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
        if (token == null) clear()
        return token
    }

    override fun write(value: String) {
        val encrypted = Base64.encodeToString(cipher.encrypt(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        preferences.edit(commit = true) { putString(KEY_REFRESH_TOKEN, encrypted) }
    }

    override fun clear() {
        preferences.edit(commit = true) { remove(KEY_REFRESH_TOKEN) }
    }

    companion object {
        /** Nombre del archivo excluido en las reglas de copia de seguridad. */
        const val PREFERENCES_FILE = "sportapp_secure_session"
        private const val KEY_REFRESH_TOKEN = "refresh_token.v1"
    }
}
