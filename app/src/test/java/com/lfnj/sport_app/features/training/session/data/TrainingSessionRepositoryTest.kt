package com.lfnj.sport_app.features.training.session.data

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.features.training.session.data.local.TrainingSessionDao
import com.lfnj.sport_app.features.training.session.model.SyncFailure
import com.lfnj.sport_app.features.training.session.model.SyncState
import com.lfnj.sport_app.features.training.session.model.TrainingOwner
import com.lfnj.sport_app.features.training.session.model.TrainingSport
import com.lfnj.sport_app.features.training.session.sync.SyncRun
import com.lfnj.sport_app.testing.SessionFixtures
import com.lfnj.sport_app.testing.SessionFixtures.ACCESS_TOKEN
import com.lfnj.sport_app.testing.InMemoryTrainingDao
import com.lfnj.sport_app.testing.TrainingFixtures
import com.lfnj.sport_app.testing.TrainingFixtures.sessionBody
import com.lfnj.sport_app.testing.TrainingHarness
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TrainingSessionRepositoryTest {
    private val h = TrainingHarness()

    @After
    fun tearDown() = h.shutdown()

    private suspend fun start(sport: TrainingSport = TrainingSport.RUNNING): String =
        (h.repository.start(h.owner, sport) as StartResult.Started).sessionId

    // ------------------------------------------------------------- inicio y fin locales

    @Test
    fun `CA6 iniciar guarda sesion y comando cifrados antes de programar la sincronizacion`() = runTest {
        val id = start(TrainingSport.SWIMMING)

        assertTrue(TrainingHarness.isUuidV4(id))
        val session = h.session(id)
        assertEquals(SyncState.LOCAL, session.syncState)
        assertTrue(session.open)
        assertEquals(TrainingSport.SWIMMING, session.details?.sport)
        assertEquals(TrainingFixtures.START, session.details?.startedAt)
        val command = h.dao.commands.single()
        assertEquals("START", command.type)
        assertEquals(id, command.sessionId)
        assertTrue(TrainingHarness.isUuidV4(command.commandId))
        assertEquals(listOf(h.owner), h.scheduler.scheduled)
        assertEquals(50_000L, h.repository.monotonicStartMillis(id))
    }

    @Test
    fun `los instantes se envian en UTC con Z y precision de segundos`() {
        assertEquals("2026-10-12T12:30:00Z", Instant.parse("2026-10-12T12:30:00.987654Z").toWireInstant())
    }

    @Test
    fun `no se abre una segunda sesion mientras haya una abierta en el dispositivo`() = runTest {
        start()

        assertEquals(StartResult.AlreadyOpen, h.repository.start(h.owner, TrainingSport.CYCLING))
        assertEquals(1, h.sessions().size)
        assertEquals(1, h.dao.commands.size)
    }

    @Test
    fun `inicios simultaneos en el dispositivo crean una sola sesion`() = runTest {
        val results = (1..8).map { async { h.repository.start(h.owner, TrainingSport.RUNNING) } }.awaitAll()

        assertEquals(1, results.count { it is StartResult.Started })
        assertEquals(1, h.sessions().size)
    }

    @Test
    fun `finalizar registra el fin con su propia clave sin calcular duracion para el envio`() = runTest {
        val id = start()
        h.clock.advanceSeconds(1_800)

        assertEquals(FinishResult.FINISHED, h.repository.finish(h.owner, id))

        val session = h.session(id)
        assertFalse(session.open)
        assertEquals(TrainingFixtures.START.plusSeconds(1_800), session.details?.endedAt)
        assertEquals(1_800L, session.details?.localDurationSeconds)
        assertNull(session.details?.officialDurationSeconds)
        assertEquals(listOf("START", "FINISH"), h.dao.commands.map { it.type })
        assertNull(h.repository.monotonicStartMillis(id))
        assertEquals(FinishResult.NOT_OPEN, h.repository.finish(h.owner, id))
    }

    @Test
    fun `finalizar una sesion ya confirmada la devuelve a LOCAL hasta confirmar el fin`() = runTest {
        h.login()
        val id = start()
        h.server.enqueue(MockResponse().setResponseCode(201).setBody(sessionBody(id)))
        assertEquals(SyncRun.COMPLETED, h.engine.run(h.owner))
        assertEquals(SyncState.SYNCED, h.session(id).syncState)

        h.repository.finish(h.owner, id)

        assertEquals(SyncState.LOCAL, h.session(id).syncState)
    }

    /** Fuente local que falla como un disco lleno o una base dañada. */
    private class FailingDao(private val delegate: InMemoryTrainingDao = InMemoryTrainingDao()) : TrainingSessionDao by delegate {
        override suspend fun sessions(owner: String) = throw IllegalStateException("disco lleno")
        override suspend fun session(owner: String, sessionId: String) = throw IllegalStateException("disco lleno")
    }

    @Test
    fun `una falla del almacenamiento se informa sin programar sincronizacion`() = runTest {
        val failing = TrainingSessionRepository(FailingDao(), h.transactions, h.codec, h.scheduler, h.api, h.sessionManager, BffMobileClient.json, h.clock)

        assertEquals(StartResult.StorageFailed, failing.start(h.owner, TrainingSport.RUNNING))
        assertEquals(FinishResult.STORAGE_FAILED, failing.finish(h.owner, "8b89aca8-bb50-4dc9-8501-61353967de48"))
        assertFalse(failing.dismiss(h.owner, "8b89aca8-bb50-4dc9-8501-61353967de48"))
        assertTrue(h.scheduler.scheduled.isEmpty())
    }

    // ------------------------------------------------------------- partición, descarte y lectura

    @Test
    fun `cada deportista solo ve su particion`() = runTest {
        start()
        val other = TrainingOwner.keyOf(TrainingFixtures.OTHER_USER_ID)

        assertTrue(h.sessions(other).isEmpty())
        assertTrue(h.repository.start(other, TrainingSport.STRENGTH) is StartResult.Started)
        assertEquals(1, h.sessions().size)
        assertEquals(1, h.sessions(other).size)
        assertFalse(h.repository.dismiss(other, h.sessions().single().sessionId))
    }

    @Test
    fun `solo se descartan registros confirmados y terminados o rechazados`() = runTest {
        h.login()
        val id = start()
        h.repository.finish(h.owner, id)
        assertFalse("Un pendiente no se puede descartar", h.repository.dismiss(h.owner, id))

        h.server.enqueue(MockResponse().setResponseCode(201).setBody(sessionBody(id)))
        h.server.enqueue(MockResponse().setBody(sessionBody(id, status = "FINISHED", endedAt = "2026-10-12T12:30:00Z", durationSeconds = 0)))
        h.engine.run(h.owner)
        assertTrue(h.repository.dismiss(h.owner, id))
        assertTrue(h.sessions().isEmpty())

        val rejected = start()
        h.server.enqueue(SessionFixtures.error(403, "capability_required"))
        h.engine.run(h.owner)
        assertTrue(h.repository.dismiss(h.owner, rejected))
    }

    @Test
    fun `un registro ilegible se muestra como SYNC_FAILED sin datos inventados`() = runTest {
        val id = start()
        h.cipher.broken = true

        val session = h.repository.observe(h.owner).first().single()

        assertEquals(id, session.sessionId)
        assertEquals(SyncState.SYNC_FAILED, session.syncState)
        assertEquals(SyncFailure.UNREADABLE, session.failure)
        assertNull(session.details)
        assertTrue(session.dismissible)
    }

    @Test
    fun `observeOpenSession indica si hay una sesion abierta`() = runTest {
        assertFalse(h.repository.observeOpenSession(h.owner).first())
        val id = start()
        assertTrue(h.repository.observeOpenSession(h.owner).first())
        h.repository.finish(h.owner, id)
        assertFalse(h.repository.observeOpenSession(h.owner).first())
    }

    // ------------------------------------------------------------- sesión activa remota

    @Test
    fun `sin sesion activa remota (204) no se crea nada`() = runTest {
        h.login()
        h.server.enqueue(MockResponse().setResponseCode(204))

        assertEquals(RemoteCheck.CHECKED, h.repository.checkRemoteActive(h.owner))

        val request = h.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/training-sessions/active", request.path)
        assertEquals("Bearer $ACCESS_TOKEN", request.getHeader("Authorization"))
        assertTrue(h.sessions().isEmpty())
    }

    @Test
    fun `una sesion activa remota se adopta como confirmada y se puede finalizar`() = runTest {
        h.login()
        val remoteId = "8b89aca8-bb50-4dc9-8501-61353967de48"
        h.server.enqueue(MockResponse().setBody(sessionBody(remoteId, sport = "strength")))

        assertEquals(RemoteCheck.CHECKED, h.repository.checkRemoteActive(h.owner))

        val adopted = h.session(remoteId)
        assertEquals(SyncState.SYNCED, adopted.syncState)
        assertTrue(adopted.open)
        assertEquals(TrainingSport.STRENGTH, adopted.details?.sport)
        assertTrue(h.dao.commands.isEmpty())
        assertEquals(FinishResult.FINISHED, h.repository.finish(h.owner, remoteId))
        assertEquals(listOf("FINISH"), h.dao.commands.map { it.type })
    }

    @Test
    fun `la sesion remota nunca contradice una sesion local abierta`() = runTest {
        h.login()
        val localId = start()
        h.server.enqueue(MockResponse().setBody(sessionBody("8b89aca8-bb50-4dc9-8501-61353967de48")))

        h.repository.checkRemoteActive(h.owner)

        assertEquals(listOf(localId), h.sessions().map { it.sessionId })
    }

    @Test
    fun `la sesion remota no reemplaza el fin local pendiente de la misma sesion`() = runTest {
        h.login()
        val id = start()
        h.repository.finish(h.owner, id)
        h.server.enqueue(MockResponse().setBody(sessionBody(id)))

        h.repository.checkRemoteActive(h.owner)

        assertFalse(h.session(id).open)
    }

    @Test
    fun `un deporte desconocido en la sesion remota no se adopta`() = runTest {
        h.login()
        h.server.enqueue(MockResponse().setBody(sessionBody("8b89aca8-bb50-4dc9-8501-61353967de48", sport = "yoga")))

        h.repository.checkRemoteActive(h.owner)

        assertTrue(h.sessions().isEmpty())
    }

    @Test
    fun `la consulta remota distingue sin conexion, indisponible y sesion vencida`() = runTest {
        h.login()
        h.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals(RemoteCheck.NO_CONNECTION, h.repository.checkRemoteActive(h.owner))

        h.server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(RemoteCheck.UNAVAILABLE, h.repository.checkRemoteActive(h.owner))

        h.server.enqueue(SessionFixtures.error(401, "invalid_access_token"))
        h.server.enqueue(SessionFixtures.error(401, "invalid_refresh_token"))
        assertEquals(RemoteCheck.SIGNED_OUT, h.repository.checkRemoteActive(h.owner))
        assertNull(h.sessionManager.currentUser.value)
    }
}
