package com.lfnj.sport_app.core.session.revocation

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lfnj.sport_app.R
import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.security.EncryptedTokenStore
import com.lfnj.sport_app.core.security.KeystoreTokenCipher
import com.lfnj.sport_app.core.session.LogoutOutcome
import com.lfnj.sport_app.core.session.RestoreOutcome
import com.lfnj.sport_app.core.session.RevocationResult
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.testing.TestAppContainer
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.TimeUnit

/**
 * HU004 en el dispositivo: cola de revocación en Room real con AES-GCM del Android Keystore,
 * separada del almacén de sesión, que sobrevive al reinicio del proceso y no se respalda (ADR-026).
 */
@RunWith(AndroidJUnit4::class)
class RevocationStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private var now = System.currentTimeMillis()
    private var scheduled = 0
    private var database = openDatabase()
    private val sessionPreferences = context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
    private val sessionStore = EncryptedTokenStore(sessionPreferences, KeystoreTokenCipher(SESSION_KEY_ALIAS))

    @get:Rule
    val migrationHelper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), RevocationDatabase::class.java)

    private fun openDatabase() = Room.databaseBuilder(context, RevocationDatabase::class.java, DB_NAME).build()

    private fun api() = BffMobileClient.retrofit(server.url("/").toString()).create(SessionApi::class.java)

    /** Una cola nueva sobre la misma base equivale a un proceso nuevo: no conserva nada en memoria. */
    private fun queue(db: RevocationDatabase = database) = RevocationQueue(
        dao = db.revocationDao(),
        cipher = KeystoreTokenCipher(TestAppContainer.REVOCATION_TEST_KEY_ALIAS),
        api = api(),
        json = BffMobileClient.json,
        scheduler = { scheduled++ },
        clock = { now },
    )

    private fun sessionManager(revocations: RevocationQueue = queue()) =
        SessionManager(api(), sessionStore, BffMobileClient.json, clock = { now }, revoker = revocations)

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() {
        server.shutdown()
        database.close()
        context.deleteDatabase(DB_NAME)
        context.deleteDatabase(MIGRATION_DB)
        sessionPreferences.edit().clear().commit()
        TestAppContainer.deleteTestKey(TestAppContainer.REVOCATION_TEST_KEY_ALIAS)
        TestAppContainer.deleteTestKey(SESSION_KEY_ALIAS)
    }

    private fun rawPayloads(): List<ByteArray> =
        database.openHelper.readableDatabase.query("SELECT payload FROM revocation_entry").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getBlob(0)) }
        }

    private suspend fun loginAthlete(manager: SessionManager) {
        server.enqueue(MockResponse().setBody(SESSION_BODY))
        manager.login("ana@example.com", "Secreta-123")
        server.takeRequest(5, TimeUnit.SECONDS)
    }

    @Test
    fun laColaSoloGuardaLaCredencialCifradaYMetadatosTecnicos() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))

        queue().revoke(REFRESH_TOKEN, now + 604_800_000L)

        val payload = rawPayloads().single()
        assertFalse("Credencial en claro", String(payload, Charsets.ISO_8859_1).contains(REFRESH_TOKEN))
        val columns = database.openHelper.readableDatabase.query("SELECT * FROM revocation_entry").use { it.columnNames.toSet() }
        assertEquals(setOf("entry_id", "idempotency_key", "expires_at_ms", "attempts", "next_attempt_at_ms", "payload"), columns)
    }

    @Test
    fun ca6Ca7LaRevocacionPendienteSobreviveAlReinicioYSeSincronizaConLaMismaClave() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(RevocationResult.PENDING, queue().revoke(REFRESH_TOKEN, now + 604_800_000L))
        val firstKey = server.takeRequest(5, TimeUnit.SECONDS)?.getHeader("Idempotency-Key")
        database.close()

        database = openDatabase()
        now += RevocationQueue.INITIAL_BACKOFF_MILLIS
        server.enqueue(MockResponse().setResponseCode(204))

        assertEquals(RevocationRun.COMPLETED, queue().run())

        val retry = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals(firstKey, retry.getHeader("Idempotency-Key"))
        assertEquals("""{"refresh_token":"$REFRESH_TOKEN"}""", retry.body.readUtf8())
        assertTrue(rawPayloads().isEmpty())
    }

    @Test
    fun ca6LaCredencialPendienteEstaSeparadaDelAlmacenDeSesionYNoLoRestaura() = runBlocking {
        val manager = sessionManager()
        loginAthlete(manager)
        assertEquals(REFRESH_TOKEN, sessionStore.read())
        server.enqueue(MockResponse().setResponseCode(503))

        assertEquals(LogoutOutcome.LoggedOutRemotePending, manager.logout())

        assertNull("El almacén de sesión ya no tiene la credencial", sessionStore.read())
        assertEquals(1, rawPayloads().size)
        // Un proceso nuevo solo lee el almacén de sesión: no puede reabrir la sesión con la cola.
        assertEquals(RestoreOutcome.NoSession, sessionManager().restore())
        assertEquals(1, scheduled)
    }

    @Test
    fun laColaUsaUnaClaveDistintaDeLaSesion() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        queue().revoke(REFRESH_TOKEN, now + 604_800_000L)

        val decryptedWithSessionKey = runCatching { KeystoreTokenCipher(SESSION_KEY_ALIAS).decrypt(rawPayloads().single()) }

        assertTrue("La clave de la sesión no debe descifrar la cola", decryptedWithSessionKey.isFailure)
    }

    @Test
    fun conLaClaveInvalidadaLaEntradaSeDescartaSinEnviarse() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        queue().revoke(REFRESH_TOKEN, now + 604_800_000L)
        server.takeRequest(5, TimeUnit.SECONDS)
        TestAppContainer.deleteTestKey(TestAppContainer.REVOCATION_TEST_KEY_ALIAS)
        now += RevocationQueue.INITIAL_BACKOFF_MILLIS

        assertEquals(RevocationRun.COMPLETED, queue().run())

        assertEquals(1, server.requestCount)
        assertTrue(rawPayloads().isEmpty())
    }

    @Test
    fun elEsquemaCreadoCoincideConElExportadoDeLaVersion1() {
        migrationHelper.createDatabase(MIGRATION_DB, 1).close()

        migrationHelper.runMigrationsAndValidate(MIGRATION_DB, 1, true).close()
    }

    @Test
    fun laColaDeRevocacionSeExcluyeDelRespaldoYLaTransferencia() {
        val backup = excludedPaths(R.xml.backup_rules)
        val extraction = excludedPaths(R.xml.data_extraction_rules)

        listOf(RevocationDatabase.NAME, "${RevocationDatabase.NAME}-wal", "${RevocationDatabase.NAME}-shm").forEach { file ->
            assertTrue("$file en backup_rules", "database:$file" in backup)
            assertEquals("$file en nube y transferencia", 2, extraction.count { it == "database:$file" })
        }
    }

    private fun excludedPaths(xml: Int): List<String> {
        val paths = mutableListOf<String>()
        context.resources.getXml(xml).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "exclude") {
                    paths += "${parser.getAttributeValue(null, "domain")}:${parser.getAttributeValue(null, "path")}"
                }
            }
        }
        return paths
    }

    private companion object {
        const val DB_NAME = "sportapp_revocation_test.db"
        const val MIGRATION_DB = "sportapp_revocation_migration_test.db"
        const val SESSION_PREFS = "sportapp_secure_session_revocation_test"
        const val SESSION_KEY_ALIAS = "sportapp.session.revocation-test"
        const val REFRESH_TOKEN = "refresh-token-sintetico-000000000001"
        const val SESSION_BODY =
            """{"token_type":"Bearer","access_token":"access-sintetico","expires_in":900,""" +
                """"refresh_token":"$REFRESH_TOKEN","refresh_expires_in":604800,""" +
                """"user":{"user_id":"7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b","full_name":"Ana Deportista",""" +
                """"email":"ana@example.com","actor_type":"athlete"}}"""
    }
}
