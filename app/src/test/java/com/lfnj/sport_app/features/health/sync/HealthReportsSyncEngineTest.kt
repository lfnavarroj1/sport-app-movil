package com.lfnj.sport_app.features.health.sync

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.features.health.data.HealthSubmitResult
import com.lfnj.sport_app.features.health.data.local.HealthReportEntity
import com.lfnj.sport_app.features.health.model.HealthSeverity
import com.lfnj.sport_app.features.health.model.HealthSyncFailure
import com.lfnj.sport_app.features.health.model.HealthSyncState
import com.lfnj.sport_app.features.training.session.sync.SyncRun
import com.lfnj.sport_app.testing.FakeTokenStore
import com.lfnj.sport_app.testing.HealthFixtures
import com.lfnj.sport_app.testing.HealthHarness
import com.lfnj.sport_app.testing.SessionFixtures
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** HU042 CA1, CA3 y CA5: cola de reportes frente a `bff-mobile` simulado (ADR-037 §4, política de ADR-029). */
class HealthReportsSyncEngineTest {
    private val h = HealthHarness()

    @Before
    fun setUp() = runTest { h.login() }

    @After
    fun tearDown() = h.shutdown()

    private suspend fun capture(severity: HealthSeverity = HealthSeverity.MODERATE, sessionId: String? = null, owner: String = h.owner): String =
        (h.repository.submit(owner, HealthFixtures.details(severity = severity, sessionId = sessionId)) as HealthSubmitResult.Queued).reportId

    private fun confirmed(id: String, severity: String = "MODERATE", sessionId: String? = null) =
        MockResponse().setResponseCode(201).setBody(HealthFixtures.response(id, severity = severity, sessionId = sessionId))

    private fun failure(status: Int, code: String, field: String? = null, issue: String? = null) =
        MockResponse().setResponseCode(status).setBody(HealthFixtures.error(code, field, issue))

    @Test
    fun `CA1 y CA5 el reporte pendiente se envia con su clave y solo el 201 lo confirma`() = runTest {
        val id = capture(sessionId = HealthFixtures.SESSION_ID)
        assertEquals(HealthSyncState.PENDING_SYNC, h.local().single().state)
        h.server.enqueue(confirmed(id, sessionId = HealthFixtures.SESSION_ID))

        assertEquals(SyncRun.COMPLETED, h.engine.run(h.owner))

        val request = h.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/me/health-reports", request.path)
        assertTrue(checkNotNull(request.getHeader("Authorization")).startsWith("Bearer "))
        UUID.fromString(checkNotNull(request.getHeader("Idempotency-Key")))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(
            setOf("report_id", "type", "body_area", "severity", "occurred_at", "session_id", "consent"),
            body.keys,
        )
        assertFalse("athlete_id" in body)
        val local = h.local().single()
        assertEquals(HealthSyncState.SYNCED, local.state)
        assertEquals(Instant.parse("2026-10-24T18:35:02Z"), local.reportedAt)
        assertEquals(0, h.dao.countPending(h.owner))
        assertEquals("El contador de intentos sobrevive a la confirmación", 1, h.dao.reports.getValue(id).attempts)
    }

    @Test
    fun `CA3 un reintento tras una respuesta perdida reutiliza report_id e Idempotency-Key y confirma una sola vez`() = runTest {
        val id = capture()
        h.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        assertEquals(SyncRun.RETRY, h.engine.run(h.owner))
        val first = h.takeRequest()
        assertEquals(HealthSyncState.PENDING_SYNC, h.local().single().state)

        // El contrato responde 201 (Idempotent-Replayed) para la misma clave o el mismo report_id con los mismos datos.
        h.server.enqueue(confirmed(id).setHeader("Idempotent-Replayed", "true"))
        assertEquals(SyncRun.COMPLETED, h.engine.run(h.owner))
        val second = h.takeRequest()

        assertEquals(first.getHeader("Idempotency-Key"), second.getHeader("Idempotency-Key"))
        assertEquals(first.body.readUtf8(), second.body.readUtf8())
        assertEquals(HealthSyncState.SYNCED, h.local().single().state)
        assertEquals(SyncRun.COMPLETED, h.engine.run(h.owner))
        assertEquals("No hay un tercer envío", 2, h.server.requestCount - 1)
    }

    @Test
    fun `CA3 un 409 report_id_reused significa otros datos y nunca se presenta como confirmado`() = runTest {
        capture()
        h.server.enqueue(failure(409, "report_id_reused"))

        assertEquals(SyncRun.COMPLETED, h.engine.run(h.owner))

        val local = h.local().single()
        assertEquals(HealthSyncState.SYNC_FAILED, local.state)
        assertEquals(HealthSyncFailure.CONFLICT, local.failure)
        assertNull(local.reportedAt)
    }

    @Test
    fun `un 201 que no corresponde al reporte enviado no lo confirma`() = runTest {
        val id = capture()
        h.server.enqueue(MockResponse().setResponseCode(201).setBody(HealthFixtures.response(id, severity = "HIGH")))

        h.engine.run(h.owner)

        assertEquals(HealthSyncFailure.INVALID_RESPONSE, h.local().single().failure)
    }

    @Test
    fun `CA2 los rechazos 4xx quedan SYNC_FAILED con una causa explicable y no se reintentan`() = runTest {
        val cases = listOf(
            failure(422, "validation_error", "consent.version", "unsupported_consent_version") to HealthSyncFailure.CONSENT_VERSION,
            failure(422, "validation_error", "body_area", "unknown_value") to HealthSyncFailure.VALIDATION,
            failure(422, "validation_error", "occurred_at", "too_old") to HealthSyncFailure.EXPIRED,
            failure(404, "training_session_not_found") to HealthSyncFailure.SESSION_NOT_FOUND,
            failure(403, "permission_required") to HealthSyncFailure.PERMISSION_REQUIRED,
            failure(409, "idempotency_key_reused") to HealthSyncFailure.CONFLICT,
            failure(400, "bad_request") to HealthSyncFailure.REJECTED,
        )
        cases.forEach { (response, expected) ->
            val id = capture()
            h.server.enqueue(response)
            assertEquals(SyncRun.COMPLETED, h.engine.run(h.owner))
            val entity = h.dao.reports.getValue(id)
            assertEquals(expected.name, entity.failureReason)
            assertEquals(HealthReportEntity.SYNC_FAILED, entity.syncState)
        }
        assertTrue("Cada rechazo conserva su intento", h.dao.reports.values.all { it.attempts == 1 })
        val validation = h.dao.reports.values.single { it.failureReason == HealthSyncFailure.VALIDATION.name }
        assertEquals("body_area", validation.failureField)
        assertEquals("unknown_value", validation.failureIssue)
        val requests = h.server.requestCount
        assertEquals(SyncRun.COMPLETED, h.engine.run(h.owner))
        assertEquals("Ningún rechazo se reenvía", requests, h.server.requestCount)
    }

    @Test
    fun `sin red y ante 502 503 o 504 el reporte sigue pendiente y se reintenta`() = runTest {
        capture()
        listOf(502, 503, 504).forEach { status ->
            h.server.enqueue(failure(status, "upstream_unavailable"))
            assertEquals(SyncRun.RETRY, h.engine.run(h.owner))
            assertEquals(HealthSyncState.PENDING_SYNC, h.local().single().state)
        }
        h.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals(SyncRun.RETRY, h.engine.run(h.owner))
        assertEquals(HealthSyncState.PENDING_SYNC, h.local().single().state)
    }

    @Test
    fun `un reporte sin sincronizar vence a los 30 dias y no se envia`() = runTest {
        capture()
        h.clock.advanceHours(30 * 24 + 1)

        assertEquals(SyncRun.COMPLETED, h.engine.run(h.owner))

        assertEquals(HealthSyncFailure.EXPIRED, h.local().single().failure)
        assertEquals("No se envió nada tras el inicio de sesión", 1, h.server.requestCount)
    }

    @Test
    fun `la cola de otro deportista no se envia con la sesion vigente`() = runTest {
        capture(owner = h.otherOwner)

        assertEquals(SyncRun.SUSPENDED, h.engine.run(h.otherOwner))

        assertEquals(1, h.dao.countPending(h.otherOwner))
        assertEquals(1, h.server.requestCount)
    }

    @Test
    fun `sin sesion la cola se suspende y conserva el pendiente sin pedir credenciales`() = runTest {
        val signedOut = HealthHarness()
        try {
            val manager = SessionManager(null as SessionApi?, FakeTokenStore(), BffMobileClient.json)
            val engine = HealthReportsSyncEngine(signedOut.dao, com.lfnj.sport_app.testing.SerialTransactionRunner(), signedOut.codec, signedOut.api, manager, BffMobileClient.json, signedOut.clock)
            signedOut.repository.submit(signedOut.owner, HealthFixtures.details())

            assertEquals(SyncRun.SUSPENDED, engine.run(signedOut.owner))
            assertEquals(1, signedOut.dao.countPending(signedOut.owner))
            assertEquals(0, signedOut.server.requestCount)
        } finally {
            signedOut.shutdown()
        }
    }

    @Test
    fun `tras reiniciar el proceso la sesion se recupera con el refresh token cifrado y se sincroniza`() = runTest {
        val id = capture()
        // Nuevo proceso: mismo almacén seguro y misma fuente local, sin usuario en memoria.
        val retrofit = BffMobileClient.retrofit(h.server.url("/").toString())
        val restarted = SessionManager(retrofit.create(SessionApi::class.java), h.tokenStore, BffMobileClient.json, clock = { 1_000_000L })
        val engine = HealthReportsSyncEngine(h.dao, com.lfnj.sport_app.testing.SerialTransactionRunner(), h.codec, h.api, restarted, BffMobileClient.json, h.clock)
        h.server.enqueue(SessionFixtures.ok(SessionFixtures.sessionBody()))
        h.server.enqueue(confirmed(id))

        assertEquals(SyncRun.COMPLETED, engine.run(h.owner))

        assertEquals(HealthSyncState.SYNCED, h.local().single().state)
    }

    @Test
    fun `un registro ilegible no se envia`() = runTest {
        capture()
        h.cipher.broken = true

        assertEquals(SyncRun.COMPLETED, h.engine.run(h.owner))

        assertEquals(HealthSyncFailure.UNREADABLE.name, h.dao.reports.values.single().failureReason)
        assertEquals(1, h.server.requestCount)
    }
}
