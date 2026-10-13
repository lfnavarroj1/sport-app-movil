package com.lfnj.sport_app.features.training.session.sync

import com.lfnj.sport_app.features.training.session.data.StartResult
import com.lfnj.sport_app.features.training.session.data.local.TrainingCommandEntity
import com.lfnj.sport_app.features.training.session.model.SyncFailure
import com.lfnj.sport_app.features.training.session.model.SyncState
import com.lfnj.sport_app.features.training.session.model.TrainingOwner
import com.lfnj.sport_app.features.training.session.model.TrainingSport
import com.lfnj.sport_app.testing.SessionFixtures
import com.lfnj.sport_app.testing.SessionFixtures.ACCESS_TOKEN
import com.lfnj.sport_app.testing.TrainingFixtures
import com.lfnj.sport_app.testing.TrainingFixtures.sessionBody
import com.lfnj.sport_app.testing.TrainingHarness
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class TrainingSyncEngineTest {
    private val h = TrainingHarness()

    @After
    fun tearDown() = h.shutdown()

    private suspend fun startLocally(sport: TrainingSport = TrainingSport.RUNNING): String =
        (h.repository.start(h.owner, sport) as StartResult.Started).sessionId

    private fun ok(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setBody(body)

    // ------------------------------------------------------------- CA1, CA2, CA6 y CA7

    @Test
    fun `CA6 y CA7 sin red inicio y fin se conservan y luego se sincronizan en orden con claves estables`() = runTest {
        h.login()
        val id = startLocally()
        h.clock.advanceSeconds(2_700)
        h.repository.finish(h.owner, id)
        assertEquals(SyncState.LOCAL, h.session(id).syncState)

        // Primera ejecución sin red: nada se confirma y el inicio sigue primero en la cola.
        h.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals(SyncRun.RETRY, h.engine.run(h.owner))
        val lost = h.takeRequest()
        assertEquals(SyncState.PENDING_SYNC, h.session(id).syncState)
        assertEquals(2, h.dao.commands.size)

        // Recuperada la red, se envían inicio y fin en ese orden.
        h.server.enqueue(ok(sessionBody(id), status = 201))
        h.server.enqueue(ok(sessionBody(id, status = "FINISHED", endedAt = "2026-10-12T13:15:00Z", durationSeconds = 2_700)))
        assertEquals(SyncRun.COMPLETED, h.engine.run(h.owner))

        val start = h.takeRequest()
        val finish = h.takeRequest()
        assertEquals("POST", start.method)
        assertEquals("/v1/training-sessions", start.path)
        assertEquals("Bearer $ACCESS_TOKEN", start.getHeader("Authorization"))
        assertEquals(lost.getHeader("Idempotency-Key"), start.getHeader("Idempotency-Key"))
        assertEquals(lost.body.readUtf8(), start.body.readUtf8().also {
            assertEquals("""{"session_id":"$id","sport":"running","started_at":"2026-10-12T12:30:00Z"}""", it)
        })
        assertEquals("/v1/training-sessions/$id/finish", finish.path)
        assertEquals("""{"ended_at":"2026-10-12T13:15:00Z"}""", finish.body.readUtf8())
        assertNotEquals(start.getHeader("Idempotency-Key"), finish.getHeader("Idempotency-Key"))
        assertTrue(TrainingHarness.isUuidV4(checkNotNull(finish.getHeader("Idempotency-Key"))))

        val synced = h.session(id)
        assertEquals(SyncState.SYNCED, synced.syncState)
        assertEquals(2_700L, synced.details?.officialDurationSeconds)
        assertTrue(h.dao.commands.isEmpty())
    }

    @Test
    fun `CA1 el inicio confirmado deja la sesion abierta y SYNCED sin fin inventado`() = runTest {
        h.login()
        val id = startLocally(TrainingSport.CYCLING)
        h.server.enqueue(ok(sessionBody(id, sport = "cycling"), status = 201))

        assertEquals(SyncRun.COMPLETED, h.engine.run(h.owner))

        val session = h.session(id)
        assertEquals(SyncState.SYNCED, session.syncState)
        assertTrue(session.open)
        assertNull(session.details?.endedAt)
        assertNull(session.details?.officialDurationSeconds)
    }

    @Test
    fun `la confirmacion del inicio no degrada un fin registrado despues en el dispositivo`() = runTest {
        h.login()
        val id = startLocally()
        h.clock.advanceSeconds(60)
        h.repository.finish(h.owner, id)
        // El fin falla por indisponibilidad después de confirmarse el inicio.
        h.server.enqueue(ok(sessionBody(id), status = 201))
        h.server.enqueue(MockResponse().setResponseCode(503))

        assertEquals(SyncRun.RETRY, h.engine.run(h.owner))

        val session = h.session(id)
        assertEquals(SyncState.PENDING_SYNC, session.syncState)
        assertEquals(TrainingFixtures.START.plusSeconds(60), session.details?.endedAt)
        assertEquals(1, h.dao.commands.size)
    }

    // ------------------------------------------------------------- CA4: idempotencia y reintentos

    @Test
    fun `CA4 tras una respuesta perdida el reintento reutiliza clave y cuerpo y no duplica la sesion`() = runTest {
        h.login()
        val id = startLocally()
        h.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals(SyncRun.RETRY, h.engine.run(h.owner))

        h.server.enqueue(ok(sessionBody(id), status = 201))
        assertEquals(SyncRun.COMPLETED, h.engine.run(h.owner))

        val first = h.takeRequest()
        val retry = h.takeRequest()
        assertEquals(first.getHeader("Idempotency-Key"), retry.getHeader("Idempotency-Key"))
        assertEquals(first.body.readUtf8(), retry.body.readUtf8())
        assertEquals(1, h.sessions().size)
        assertEquals(2, h.server.requestCount - 1) // sin contar el inicio de sesión
    }

    @Test
    fun `solo se reintentan 502, 503 y 504`() = runTest {
        h.login()
        for (status in listOf(502, 503, 504)) {
            startLocally().also { id ->
                h.server.enqueue(MockResponse().setResponseCode(status))
                assertEquals(SyncRun.RETRY, h.engine.run(h.owner))
                assertEquals(SyncState.PENDING_SYNC, h.session(id).syncState)
                h.server.enqueue(ok(sessionBody(id), status = 201))
                h.engine.run(h.owner)
                h.repository.finish(h.owner, id)
                h.server.enqueue(ok(sessionBody(id, status = "FINISHED", endedAt = "2026-10-12T12:30:00Z", durationSeconds = 0)))
                assertEquals(SyncRun.COMPLETED, h.engine.run(h.owner))
            }
        }
        val id = startLocally()
        h.server.enqueue(MockResponse().setResponseCode(500))

        assertEquals(SyncRun.COMPLETED, h.engine.run(h.owner))
        assertEquals(SyncState.SYNC_FAILED, h.session(id).syncState)
        assertEquals(SyncFailure.REJECTED, h.session(id).failure)
    }

    // ------------------------------------------------------------- conflictos funcionales (CA3, CA5, CA9)

    @Test
    fun `CA5 y CA7 active_session_exists deja toda la cadena SYNC_FAILED y nunca envia el fin`() = runTest {
        h.login()
        val id = startLocally()
        h.repository.finish(h.owner, id)
        h.server.enqueue(SessionFixtures.error(409, "active_session_exists"))

        assertEquals(SyncRun.COMPLETED, h.engine.run(h.owner))

        val session = h.session(id)
        assertEquals(SyncState.SYNC_FAILED, session.syncState)
        assertEquals(SyncFailure.ACTIVE_SESSION_EXISTS, session.failure)
        assertTrue(h.dao.commands.isEmpty())
        assertEquals(2, h.server.requestCount) // inicio de sesión + un único intento de inicio
    }

    @Test
    fun `CA3 finalizar una sesion inexistente o ajena se rechaza sin fabricar duracion`() = runTest {
        h.login()
        val id = startLocally()
        h.server.enqueue(ok(sessionBody(id), status = 201))
        h.engine.run(h.owner)
        h.repository.finish(h.owner, id)
        h.server.enqueue(SessionFixtures.error(404, "training_session_not_found"))

        h.engine.run(h.owner)

        val session = h.session(id)
        assertEquals(SyncState.SYNC_FAILED, session.syncState)
        assertEquals(SyncFailure.SESSION_NOT_FOUND, session.failure)
        assertNull(session.details?.officialDurationSeconds)
    }

    @Test
    fun `los rechazos funcionales se traducen a causas explicables`() = runTest {
        h.login()
        val cases = listOf(
            SessionFixtures.error(403, "capability_required") to SyncFailure.CAPABILITY_REQUIRED,
            SessionFixtures.error(403, "permission_required") to SyncFailure.PERMISSION_REQUIRED,
            SessionFixtures.error(404, "training_plan_not_found") to SyncFailure.PLAN_NOT_FOUND,
            SessionFixtures.error(409, "session_id_reused") to SyncFailure.CONFLICT,
            SessionFixtures.error(409, "idempotency_key_reused") to SyncFailure.CONFLICT,
            MockResponse().setResponseCode(422).setBody(TrainingFixtures.validationError("too_far_in_future")) to SyncFailure.DEVICE_CLOCK,
            MockResponse().setResponseCode(422).setBody(TrainingFixtures.validationError("too_old")) to SyncFailure.EXPIRED,
        )
        for ((response, expected) in cases) {
            val id = startLocally()
            h.server.enqueue(response)
            h.engine.run(h.owner)
            assertEquals(expected, h.session(id).failure)
            assertEquals(SyncState.SYNC_FAILED, h.session(id).syncState)
        }
    }

    @Test
    fun `fin anterior al inicio rechazado por el backend informa la hora del dispositivo`() = runTest {
        h.login()
        val id = startLocally()
        h.server.enqueue(ok(sessionBody(id), status = 201))
        h.engine.run(h.owner)
        h.clock.instant = TrainingFixtures.START.minusSeconds(120) // el usuario retrasó el reloj
        h.repository.finish(h.owner, id)
        h.server.enqueue(MockResponse().setResponseCode(422).setBody(TrainingFixtures.validationError("ends_before_starts")))

        h.engine.run(h.owner)

        assertEquals(SyncFailure.DEVICE_CLOCK, h.session(id).failure)
        assertEquals(0L, h.session(id).details?.localDurationSeconds)
    }

    @Test
    fun `una respuesta de otra sesion no se acepta como confirmacion`() = runTest {
        h.login()
        val id = startLocally()
        h.server.enqueue(ok(sessionBody("11111111-2222-4333-8444-555555555555"), status = 201))

        h.engine.run(h.owner)

        assertEquals(SyncFailure.INVALID_RESPONSE, h.session(id).failure)
    }

    // ------------------------------------------------------------- vencimiento, partición y sesión

    @Test
    fun `un comando con mas de 30 dias vence sin enviarse y queda visible como SYNC_FAILED`() = runTest {
        h.login()
        val id = startLocally()
        h.clock.instant = h.clock.instant.plus(TrainingSyncEngine.RETENTION).plus(Duration.ofMinutes(1))

        assertEquals(SyncRun.COMPLETED, h.engine.run(h.owner))

        assertEquals(SyncState.SYNC_FAILED, h.session(id).syncState)
        assertEquals(SyncFailure.EXPIRED, h.session(id).failure)
        assertTrue(h.dao.commands.isEmpty())
        assertEquals(1, h.server.requestCount)
    }

    @Test
    fun `otro deportista autenticado no puede reanudar una particion ajena`() = runTest {
        h.login()
        val other = TrainingOwner.keyOf(TrainingFixtures.OTHER_USER_ID)
        h.dao.insertCommand(
            TrainingCommandEntity(
                commandId = "c-ajeno", ownerKey = other, sessionId = "s-ajena", type = "START",
                createdAtMs = TrainingFixtures.START.toEpochMilli(), payload = byteArrayOf(),
            )
        )

        assertEquals(SyncRun.SUSPENDED, h.engine.run(other))

        assertEquals(1, h.server.requestCount)
        assertEquals(1, h.dao.commands.size)
    }

    @Test
    fun `sin sesion guardada la sincronizacion se suspende hasta volver a autenticarse`() = runTest {
        h.login()
        startLocally()
        h.sessionManager.clearLocalSession()

        assertEquals(SyncRun.SUSPENDED, h.newEngine(h.newSessionManager()).run(h.owner))

        assertEquals(1, h.dao.commands.size)
    }

    @Test
    fun `CA7 tras reiniciar el proceso recupera la sesion cifrada y sincroniza`() = runTest {
        h.login()
        val id = startLocally()
        val restarted = h.newSessionManager() // proceso nuevo: sin access token en memoria
        h.server.enqueue(SessionFixtures.ok(SessionFixtures.sessionBody(refreshToken = SessionFixtures.ROTATED_REFRESH_TOKEN)))
        h.server.enqueue(ok(sessionBody(id), status = 201))

        assertEquals(SyncRun.COMPLETED, h.newEngine(restarted).run(h.owner))

        assertEquals("/v1/sessions/refresh", h.takeRequest().path)
        assertEquals("/v1/training-sessions", h.takeRequest().path)
        assertEquals(SyncState.SYNCED, h.session(id).syncState)
    }

    @Test
    fun `sin red para recuperar la sesion se reintenta despues`() = runTest {
        h.login()
        startLocally()
        h.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        assertEquals(SyncRun.RETRY, h.newEngine(h.newSessionManager()).run(h.owner))
    }

    @Test
    fun `un comando ilegible se retira y su sesion queda SYNC_FAILED`() = runTest {
        h.login()
        val id = startLocally()
        h.cipher.broken = true

        assertEquals(SyncRun.COMPLETED, h.engine.run(h.owner))

        h.cipher.broken = false
        assertEquals(SyncFailure.UNREADABLE, h.session(id).failure)
        assertTrue(h.dao.commands.isEmpty())
        assertFalse(h.session(id).open)
    }

    @Test
    fun `la cola no guarda tokens ni datos de negocio en claro`() = runTest {
        h.login()
        startLocally()
        val stored = (h.dao.sessions.values.map { it.payload } + h.dao.commands.map { it.payload })
            .joinToString("|") { it.toString(Charsets.UTF_8) }

        listOf("running", "2026-10-12", ACCESS_TOKEN, SessionFixtures.REFRESH_TOKEN, "Bearer").forEach {
            assertFalse("Se encontró «$it» en claro", stored.contains(it))
        }
    }
}
