package com.lfnj.sport_app.features.training.metrics.sync

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.features.training.metrics.data.SubmitResult
import com.lfnj.sport_app.features.training.metrics.model.MetricInputValue
import com.lfnj.sport_app.features.training.metrics.model.MetricSyncFailure
import com.lfnj.sport_app.features.training.metrics.model.MetricSyncState
import com.lfnj.sport_app.features.training.session.sync.SyncRun
import com.lfnj.sport_app.testing.FakeTokenStore
import com.lfnj.sport_app.testing.MetricsFixtures
import com.lfnj.sport_app.testing.MetricsHarness
import com.lfnj.sport_app.testing.SessionFixtures
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** HU034 CA4 y CA8: cola de cálculos frente a `bff-mobile` simulado (ADR-030, política offline). */
class MetricsSyncEngineTest {
    private val h = MetricsHarness()

    @Before
    fun setUp() = runTest {
        h.login()
        h.cacheCatalog()
    }

    @After
    fun tearDown() = h.shutdown()

    private suspend fun capture(watts: Long = 250): String {
        val queued = h.repository.submit(
            h.owner, MetricsFixtures.FTP_ID, listOf(MetricInputValue("mean_power_watts", watts, "W")), Instant.parse("2026-10-12T15:30:00Z"),
        )
        return (queued as SubmitResult.Queued).calculationId
    }

    private fun confirmed(id: String) = MockResponse().setResponseCode(201).setBody(MetricsFixtures.ftpResult(calculationId = id))

    @Test
    fun `CA1 CA4 y CA8 el calculo pendiente se envia con su clave y queda confirmado con el resultado del backend`() = runTest {
        val id = capture()
        h.server.enqueue(confirmed(id))

        assertEquals(SyncRun.COMPLETED, h.engine.run(h.owner))

        val request = h.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/training-metrics/calculations", request.path)
        val key = checkNotNull(request.getHeader("Idempotency-Key"))
        UUID.fromString(key)
        assertEquals(
            """{"calculation_id":"$id","metric_code":"FTP_ESTIMATED","method_code":"FTP_20_MIN_95_V1","method_version":1,""" +
                """"measured_at":"2026-10-12T15:30:00Z","inputs":[{"code":"mean_power_watts","value":250,"unit":"W"}]}""",
            request.body.readUtf8(),
        )
        val local = h.local()
        assertTrue(local.pending.isEmpty())
        val result = local.results.items.single()
        assertEquals(id, result.calculationId)
        assertEquals("238", result.value.toPlainString())
        assertEquals("estimated_not_diagnostic", result.disclaimerKey)
    }

    @Test
    fun `CA4 tras una respuesta perdida el reintento reutiliza clave y cuerpo`() = runTest {
        val id = capture()
        h.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        assertEquals(SyncRun.RETRY, h.engine.run(h.owner))
        val first = h.takeRequest()
        assertEquals(MetricSyncState.PENDING_SYNC, h.local().pending.single().state)

        h.server.enqueue(MockResponse().setResponseCode(201).setHeader("Idempotent-Replayed", "true").setBody(MetricsFixtures.ftpResult(calculationId = id)))
        assertEquals(SyncRun.COMPLETED, h.engine.run(h.owner))
        val retry = h.takeRequest()
        assertEquals(first.getHeader("Idempotency-Key"), retry.getHeader("Idempotency-Key"))
        assertEquals(first.body.readUtf8(), retry.body.readUtf8())
        assertEquals(1, h.local().results.items.size)
    }

    @Test
    fun `CA4 el mismo calculation_id ya existente con otra clave responde 200 y tambien confirma`() = runTest {
        val id = capture()
        h.server.enqueue(MockResponse().setResponseCode(200).setBody(MetricsFixtures.ftpResult(calculationId = id)))

        assertEquals(SyncRun.COMPLETED, h.engine.run(h.owner))
        assertEquals(id, h.local().results.items.single().calculationId)
    }

    @Test
    fun `CA8 solo red 502 503 y 504 se reintentan y el calculo sigue pendiente`() = runTest {
        capture()
        listOf(502, 503, 504).forEach { status ->
            h.server.enqueue(SessionFixtures.error(status, "upstream_unavailable"))
            assertEquals(SyncRun.RETRY, h.engine.run(h.owner))
        }
        h.server.shutdown()
        assertEquals(SyncRun.RETRY, h.engine.run(h.owner))

        val pending = h.local().pending.single()
        assertEquals(MetricSyncState.PENDING_SYNC, pending.state)
        assertTrue(h.local().results.items.isEmpty())
    }

    @Test
    fun `CA3 y CA8 un 422 queda SYNC_FAILED con el campo y la causa sin reintentar`() = runTest {
        val id = capture(watts = 2400)
        h.server.enqueue(MockResponse().setResponseCode(422).setBody(MetricsFixtures.validationError("inputs[0].value", "out_of_range")))

        assertEquals(SyncRun.COMPLETED, h.engine.run(h.owner))

        val failed = h.local().pending.single { it.calculationId == id }
        assertEquals(MetricSyncState.SYNC_FAILED, failed.state)
        assertEquals(MetricSyncFailure.VALIDATION, failed.failure)
        assertEquals("inputs[0].value", failed.failureField)
        assertEquals("out_of_range", failed.failureIssue)
        assertEquals("Un rechazo no se vuelve a enviar", SyncRun.COMPLETED, h.engine.run(h.owner))
        assertEquals(3, h.server.requestCount)
    }

    @Test
    fun `CA4 una clave o calculation_id reutilizados con otros datos quedan como conflicto`() = runTest {
        capture()
        h.server.enqueue(SessionFixtures.error(409, "calculation_id_reused"))

        h.engine.run(h.owner)

        assertEquals(MetricSyncFailure.CONFLICT, h.local().pending.single().failure)
    }

    @Test
    fun `sin la capacidad el calculo queda rechazado y explicable`() = runTest {
        capture()
        h.server.enqueue(SessionFixtures.error(403, "capability_required"))

        h.engine.run(h.owner)

        assertEquals(MetricSyncFailure.CAPABILITY_REQUIRED, h.local().pending.single().failure)
    }

    @Test
    fun `una respuesta de otro calculo no se acepta como confirmacion`() = runTest {
        capture()
        h.server.enqueue(confirmed("11111111-1111-4111-8111-111111111111"))

        h.engine.run(h.owner)

        assertEquals(MetricSyncFailure.INVALID_RESPONSE, h.local().pending.single().failure)
        assertTrue(h.local().results.items.isEmpty())
    }

    @Test
    fun `los pendientes vencen a los 30 dias sin enviarse y nunca aparecen como confirmados`() = runTest {
        capture()
        h.clock.advanceHours(30 * 24 + 1)

        assertEquals(SyncRun.COMPLETED, h.engine.run(h.owner))

        assertEquals(MetricSyncFailure.EXPIRED, h.local().pending.single().failure)
        assertEquals(2, h.server.requestCount)
        assertTrue(h.local().results.items.isEmpty())
    }

    @Test
    fun `un registro ilegible queda SYNC_FAILED sin enviarse`() = runTest {
        capture()
        h.cipher.broken = true

        h.engine.run(h.owner)
        h.cipher.broken = false

        assertEquals(MetricSyncFailure.UNREADABLE, h.local().pending.single().failure)
        assertEquals(2, h.server.requestCount)
    }

    @Test
    fun `sin sesion o con otro deportista la particion se suspende sin enviar`() = runTest {
        capture()

        assertEquals(SyncRun.SUSPENDED, h.engine.run("otra-huella"))
        h.sessionManager.clearLocalSession()
        val signedOut = MetricsSyncEngine(
            h.dao, com.lfnj.sport_app.testing.SerialTransactionRunner(), h.codec, h.api,
            SessionManager(BffMobileClient.retrofit(h.server.url("/").toString()).create(SessionApi::class.java), FakeTokenStore(), BffMobileClient.json),
            BffMobileClient.json, h.clock,
        )
        assertEquals(SyncRun.SUSPENDED, signedOut.run(h.owner))
        assertEquals(2, h.server.requestCount)
        assertNull(h.local().pending.single().failure)
    }

    @Test
    fun `CA5 el resultado confirmado se conserva hasta que la proyeccion lo devuelve`() = runTest {
        val id = capture()
        h.server.enqueue(confirmed(id))
        h.engine.run(h.owner)
        h.takeRequest()

        // La proyección aún no converge: la consulta llega vacía y el resultado no desaparece.
        h.server.enqueue(MockResponse().setBody(MetricsFixtures.page()))
        h.repository.refreshResults(h.owner)
        assertEquals(listOf(id), h.local().results.items.map { it.calculationId })

        h.server.enqueue(MockResponse().setBody(MetricsFixtures.page(MetricsFixtures.ftpResult(calculationId = id))))
        h.repository.refreshResults(h.owner)
        val results = h.local().results
        assertEquals(listOf(id), results.items.map { it.calculationId })
        assertTrue("Ya proyectado: sale de la espera", results.awaiting.isEmpty())
    }
}
