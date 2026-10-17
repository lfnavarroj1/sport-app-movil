package com.lfnj.sport_app.core.session.revocation

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.security.TokenCipher
import com.lfnj.sport_app.core.session.RevocationResult
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.testing.SessionFixtures.REFRESH_TOKEN
import com.lfnj.sport_app.testing.SessionFixtures.ROTATED_REFRESH_TOKEN
import com.lfnj.sport_app.testing.SessionFixtures.error
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

/** HU004 CA5–CA7: cola cifrada de revocación frente a `POST /v1/sessions/revoke` simulado. */
class RevocationQueueTest {
    private val server = MockWebServer().apply { start() }
    private val dao = InMemoryRevocationDao()
    private val cipher = ReversibleCipher()
    private var scheduled = 0
    private var now = 1_000_000L
    private val expiresAt = now + 604_800_000L

    private fun queue(api: SessionApi? = BffMobileClient.retrofit(server.url("/").toString()).create(SessionApi::class.java)) =
        RevocationQueue(dao, cipher, api, BffMobileClient.json, { scheduled++ }, clock = { now })

    @After
    fun tearDown() = server.shutdown()

    private fun noContent() = MockResponse().setResponseCode(204)
    private fun takeRequest(): RecordedRequest = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS)) { "No se envió la revocación" }

    // ------------------------------------------------------------------ intento inmediato

    @Test
    fun `CA5 la revocacion confirmada no deja entrada y usa solo el contrato`() = runTest {
        server.enqueue(noContent())

        assertEquals(RevocationResult.CONFIRMED, queue().revoke(REFRESH_TOKEN, expiresAt))

        val request = takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/sessions/revoke", request.path)
        assertEquals("""{"refresh_token":"$REFRESH_TOKEN"}""", request.body.readUtf8())
        UUID.fromString(request.getHeader("Idempotency-Key"))
        assertNull("La revocación no exige access token", request.getHeader("Authorization"))
        assertTrue(dao.rows.isEmpty())
        assertEquals(0, scheduled)
    }

    @Test
    fun `CA6 con 503 la credencial queda cifrada en la cola y se programa el trabajo`() = runTest {
        server.enqueue(error(503, "upstream_unavailable"))

        assertEquals(RevocationResult.PENDING, queue().revoke(REFRESH_TOKEN, expiresAt))

        val entry = dao.rows.single()
        assertEquals(expiresAt, entry.expiresAtMs)
        assertEquals(1, entry.attempts)
        assertEquals(now + RevocationQueue.INITIAL_BACKOFF_MILLIS, entry.nextAttemptAtMs)
        assertFalse("El token no se guarda en claro", String(entry.payload, Charsets.ISO_8859_1).contains(REFRESH_TOKEN))
        assertEquals(takeRequest().getHeader("Idempotency-Key"), entry.idempotencyKey)
        assertEquals(1, scheduled)
    }

    @Test
    fun `CA6 con 504 timeout o sin conexion la revocacion queda pendiente`() = runTest {
        server.enqueue(error(504, "upstream_timeout"))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertEquals(RevocationResult.PENDING, queue().revoke(REFRESH_TOKEN, expiresAt))
        assertEquals(RevocationResult.PENDING, queue().revoke(ROTATED_REFRESH_TOKEN, expiresAt))

        assertEquals(2, dao.rows.size)
        assertEquals(2, scheduled)
    }

    @Test
    fun `CA6 sin servidor alcanzable la revocacion queda pendiente`() = runTest {
        server.shutdown()

        assertEquals(RevocationResult.PENDING, queue().revoke(REFRESH_TOKEN, expiresAt))
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `un 422 no mejora al repetirse y se descarta sin dejar entrada`() = runTest {
        server.enqueue(error(422, "validation_error"))

        assertEquals(RevocationResult.NOT_CONFIRMED, queue().revoke(REFRESH_TOKEN, expiresAt))
        assertTrue(dao.rows.isEmpty())
        assertEquals(0, scheduled)
    }

    @Test
    fun `CA2 una credencial ya vencida no se envia ni se guarda`() = runTest {
        assertEquals(RevocationResult.ALREADY_CLOSED, queue().revoke(REFRESH_TOKEN, expiresAtMillis = now))

        assertEquals(0, server.requestCount)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `sin vencimiento conocido se intenta una vez sin guardar la credencial`() = runTest {
        server.enqueue(noContent())
        server.enqueue(error(503, "upstream_unavailable"))

        assertEquals(RevocationResult.CONFIRMED, queue().revoke(REFRESH_TOKEN, expiresAtMillis = null))
        assertEquals(RevocationResult.NOT_CONFIRMED, queue().revoke(REFRESH_TOKEN, expiresAtMillis = null))

        assertTrue(dao.rows.isEmpty())
        assertEquals(0, scheduled)
    }

    @Test
    fun `si la cola no puede guardar se intenta una vez directamente`() = runTest {
        dao.failInsert = true
        server.enqueue(error(503, "upstream_unavailable"))

        assertEquals(RevocationResult.NOT_CONFIRMED, queue().revoke(REFRESH_TOKEN, expiresAt))
        assertEquals(1, server.requestCount)
        assertTrue(dao.rows.isEmpty())
    }

    // ------------------------------------------------------------------ trabajo durable (CA7)

    @Test
    fun `CA7 al recuperar la red reintenta con la misma clave y elimina la entrada al confirmar`() = runTest {
        server.enqueue(error(503, "upstream_unavailable"))
        queue().revoke(REFRESH_TOKEN, expiresAt)
        val firstKey = takeRequest().getHeader("Idempotency-Key")
        now += RevocationQueue.INITIAL_BACKOFF_MILLIS
        server.enqueue(noContent())

        assertEquals(RevocationRun.COMPLETED, queue().run())

        val retry = takeRequest()
        assertEquals(firstKey, retry.getHeader("Idempotency-Key"))
        assertEquals("""{"refresh_token":"$REFRESH_TOKEN"}""", retry.body.readUtf8())
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `CA2 y CA7 una sesion ya cerrada en el servidor tambien responde 204 y confirma`() = runTest {
        server.enqueue(error(503, "upstream_unavailable"))
        queue().revoke(REFRESH_TOKEN, expiresAt)
        now += RevocationQueue.INITIAL_BACKOFF_MILLIS
        // El servicio responde 204 a una credencial ya revocada, rotada o desconocida (sin oráculo de tokens).
        server.enqueue(noContent())
        server.enqueue(noContent())

        assertEquals(RevocationRun.COMPLETED, queue().run())
        assertEquals(RevocationRun.COMPLETED, queue().run())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `CA7 sin red el reintento conserva la entrada y duplica la espera`() = runTest {
        server.enqueue(error(503, "upstream_unavailable"))
        queue().revoke(REFRESH_TOKEN, expiresAt)
        now += RevocationQueue.INITIAL_BACKOFF_MILLIS
        server.enqueue(error(504, "upstream_timeout"))

        assertEquals(RevocationRun.RETRY, queue().run())

        val entry = dao.rows.single()
        assertEquals(2, entry.attempts)
        assertEquals(now + 2 * RevocationQueue.INITIAL_BACKOFF_MILLIS, entry.nextAttemptAtMs)
    }

    @Test
    fun `una entrada cuya proxima ejecucion no llego no se envia`() = runTest {
        server.enqueue(error(503, "upstream_unavailable"))
        queue().revoke(REFRESH_TOKEN, expiresAt)
        takeRequest()

        assertEquals(RevocationRun.RETRY, queue().run())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `CA7 una entrada vencida se descarta sin enviarla`() = runTest {
        server.enqueue(error(503, "upstream_unavailable"))
        queue().revoke(REFRESH_TOKEN, expiresAt)
        takeRequest()
        now = expiresAt

        assertEquals(RevocationRun.COMPLETED, queue().run())

        assertEquals(1, server.requestCount)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `una entrada que no puede descifrarse se elimina sin enviarse`() = runTest {
        server.enqueue(error(503, "upstream_unavailable"))
        queue().revoke(REFRESH_TOKEN, expiresAt)
        takeRequest()
        now += RevocationQueue.INITIAL_BACKOFF_MILLIS
        cipher.broken = true

        assertEquals(RevocationRun.COMPLETED, queue().run())

        assertEquals(1, server.requestCount)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `CA7 una ejecucion procesa todas las revocaciones pendientes`() = runTest {
        server.enqueue(error(503, "upstream_unavailable"))
        server.enqueue(error(503, "upstream_unavailable"))
        queue().revoke(REFRESH_TOKEN, expiresAt)
        queue().revoke(ROTATED_REFRESH_TOKEN, expiresAt)
        now += RevocationQueue.INITIAL_BACKOFF_MILLIS
        server.enqueue(noContent())
        server.enqueue(noContent())

        assertEquals(RevocationRun.COMPLETED, queue().run())

        assertTrue(dao.rows.isEmpty())
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `sin BFF configurado el trabajo queda suspendido y conserva la entrada`() = runTest {
        server.enqueue(error(503, "upstream_unavailable"))
        queue().revoke(REFRESH_TOKEN, expiresAt)

        assertEquals(RevocationRun.SUSPENDED, queue(api = null).run())
        assertEquals(1, dao.rows.size)
        assertEquals(RevocationRun.COMPLETED, RevocationQueue(InMemoryRevocationDao(), cipher, null, BffMobileClient.json, {}).run())
    }

    @Test
    fun `hasPending informa si quedan revocaciones`() = runTest {
        assertFalse(queue().hasPending())
        server.enqueue(error(503, "upstream_unavailable"))
        queue().revoke(REFRESH_TOKEN, expiresAt)

        assertTrue(queue().hasPending())
    }

    @Test
    fun `la espera exponencial empieza en 30 s y se acota en 5 h`() {
        assertEquals(30_000L, RevocationQueue.backoffMillis(1))
        assertEquals(60_000L, RevocationQueue.backoffMillis(2))
        assertEquals(120_000L, RevocationQueue.backoffMillis(3))
        assertEquals(RevocationQueue.MAX_BACKOFF_MILLIS, RevocationQueue.backoffMillis(30))
    }
}

/** DAO en memoria con la misma semántica que las consultas de [RevocationDao]. */
private class InMemoryRevocationDao : RevocationDao {
    val rows = mutableListOf<RevocationEntryEntity>()
    var failInsert = false

    override suspend fun insert(entry: RevocationEntryEntity) {
        if (failInsert) throw IllegalStateException("almacenamiento no disponible")
        require(rows.none { it.entryId == entry.entryId || it.idempotencyKey == entry.idempotencyKey })
        rows += entry
    }

    override suspend fun entries() = rows.sortedBy { it.nextAttemptAtMs }

    override suspend fun count() = rows.size

    override suspend fun markAttempt(entryId: String, nextAttemptAt: Long) {
        val index = rows.indexOfFirst { it.entryId == entryId }
        if (index < 0) return
        val entry = rows[index]
        rows[index] = RevocationEntryEntity(entry.entryId, entry.idempotencyKey, entry.expiresAtMs, entry.attempts + 1, nextAttemptAt, entry.payload)
    }

    override suspend fun delete(entryId: String) {
        rows.removeAll { it.entryId == entryId }
    }

    override suspend fun deleteExpired(now: Long): Int {
        val before = rows.size
        rows.removeAll { it.expiresAtMs <= now }
        return before - rows.size
    }
}

/** Cifrado de prueba reversible que no deja el texto en claro; el AES-GCM real se prueba en el dispositivo. */
private class ReversibleCipher : TokenCipher {
    var broken = false
    override fun encrypt(plaintext: ByteArray) = byteArrayOf(1) + plaintext.map { (it.toInt() xor 0x5A).toByte() }
    override fun decrypt(ciphertext: ByteArray): ByteArray {
        if (broken) throw IllegalStateException("clave invalidada")
        return ciphertext.drop(1).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }
}
