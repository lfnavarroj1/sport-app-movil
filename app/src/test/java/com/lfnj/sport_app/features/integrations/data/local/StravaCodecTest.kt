package com.lfnj.sport_app.features.integrations.data.local

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.features.integrations.model.StravaLinkStatus
import com.lfnj.sport_app.features.integrations.model.StravaSyncError
import com.lfnj.sport_app.features.integrations.model.StravaSyncStatus
import com.lfnj.sport_app.testing.FakeCipher
import com.lfnj.sport_app.testing.StravaFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** HU031: el estado de vinculación se guarda solo cifrado y sin tokens; una caché ilegible no se muestra. */
class StravaCodecTest {
    private val cipher = FakeCipher()
    private val codec = StravaCodec(cipher, BffMobileClient.json)

    @Test
    fun `ida y vuelta conserva estado, scopes y ultima corrida`() {
        val integration = StravaFixtures.integration(lastSync = StravaFixtures.sync(StravaSyncStatus.FAILED, StravaSyncError.CIRCUIT_OPEN))

        val decoded = codec.decode(StravaCacheEntity("owner", codec.encode(integration)))

        assertEquals(integration, decoded)
    }

    @Test
    fun `un estado sin vinculacion ni corridas tambien se conserva`() {
        val integration = StravaFixtures.integration(status = StravaLinkStatus.NOT_LINKED, lastSync = null)

        assertEquals(integration, codec.decode(StravaCacheEntity("owner", codec.encode(integration))))
    }

    @Test
    fun `el contenido guardado no queda en claro ni contiene tokens ni identificadores de Strava`() {
        val stored = codec.encode(StravaFixtures.integration())
        val plain = cipher.decrypt(stored).toString(Charsets.UTF_8)

        assertFalse("ACTIVE" in String(stored, Charsets.UTF_8))
        listOf("token", "access", "refresh", "\"code\"", "\"state\"", "athlete", "client", "98765").forEach {
            assertFalse("la caché no debe contener «$it»: $plain", it in plain.lowercase())
        }
    }

    @Test
    fun `una cache alterada o con otro formato se trata como inexistente`() {
        val stored = codec.encode(StravaFixtures.integration())
        cipher.broken = true
        assertNull(codec.decode(StravaCacheEntity("owner", stored)))

        cipher.broken = false
        assertNull(codec.decode(StravaCacheEntity("owner", cipher.encrypt("""{"status":"OTRO","scopes":[],"synced_at":1}""".toByteArray()))))
        assertNull(codec.decode(StravaCacheEntity("owner", cipher.encrypt("no-json".toByteArray()))))
        val unknownError = """{"status":"ACTIVE","scopes":[],"synced_at":1,""" +
            """"last_sync":{"status":"FAILED","requested_at":1,"imported_count":0,"error_code":"nuevo"}}"""
        assertNull(codec.decode(StravaCacheEntity("owner", cipher.encrypt(unknownError.toByteArray()))))
    }
}
