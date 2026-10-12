package com.lfnj.sport_app.core.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.GeneralSecurityException
import java.security.KeyStore

/** HU003 CA6: almacenamiento del refresh token con Android Keystore en un dispositivo real o emulador. */
@RunWith(AndroidJUnit4::class)
class SecureStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alias = "sportapp.session.test"
    private val prefsName = "sportapp_secure_session_test"
    private val token = "refresh-token-sintetico-000000000001"
    private lateinit var store: EncryptedTokenStore

    @Before
    fun setUp() {
        store = EncryptedTokenStore(context.getSharedPreferences(prefsName, Context.MODE_PRIVATE), KeystoreTokenCipher(alias))
    }

    @After
    fun tearDown() {
        context.deleteSharedPreferences(prefsName)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun cifraYDescifraConLaClaveDelKeystore() {
        val cipher = KeystoreTokenCipher(alias)
        val plaintext = token.toByteArray()

        val first = cipher.encrypt(plaintext)
        val second = cipher.encrypt(plaintext)

        assertArrayEquals(plaintext, cipher.decrypt(first))
        assertFalse("El IV debe ser aleatorio", first.contentEquals(second))
        assertFalse(String(first, Charsets.ISO_8859_1).contains(token))
    }

    @Test
    fun laClaveNoEsExportable() {
        KeystoreTokenCipher(alias).encrypt(byteArrayOf(1))
        val entry = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .getEntry(alias, null) as KeyStore.SecretKeyEntry

        assertNull(entry.secretKey.encoded)
    }

    @Test
    fun unTextoCifradoAlteradoSeRechaza() {
        val cipher = KeystoreTokenCipher(alias)
        val encrypted = cipher.encrypt(token.toByteArray())
        encrypted[encrypted.size - 1] = (encrypted.last().toInt() xor 0x01).toByte()

        assertThrows(GeneralSecurityException::class.java) { cipher.decrypt(encrypted) }
    }

    @Test
    fun elArchivoDePreferenciasSoloContieneTextoCifrado() {
        store.write(token)

        val raw = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse(raw.contains(token))
        assertEquals(token, store.read())
    }

    @Test
    fun clearEliminaElToken() {
        store.write(token)
        store.clear()

        assertNull(store.read())
    }

    @Test
    fun unValorAlteradoSeTrataComoAusenteYSeElimina() {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putString("refresh_token.v1", "no-es-base64-valido").commit()

        assertNull(store.read())
        assertEquals(0, context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).all.size)
    }
}
