package com.lfnj.sport_app.features.training.metrics.data

import com.lfnj.sport_app.features.training.metrics.data.local.MetricCommandEntity
import com.lfnj.sport_app.features.training.metrics.model.MetricInputValue
import com.lfnj.sport_app.features.training.metrics.model.MetricSyncState
import com.lfnj.sport_app.testing.MetricsFixtures
import com.lfnj.sport_app.testing.MetricsHarness
import com.lfnj.sport_app.testing.SessionFixtures
import kotlinx.coroutines.test.runTest
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

/** HU034 en el repositorio móvil frente a `bff-mobile` simulado (ADR-030). No es un E2E. */
class TrainingMetricsRepositoryTest {
    private val h = MetricsHarness()
    private val measuredAt = Instant.parse("2026-10-12T15:30:00Z")

    @Before
    fun login() = runTest { h.login() }

    @After
    fun tearDown() = h.shutdown()

    // ---------------------------------------------------------------- catálogo

    @Test
    fun `CA7 el catalogo confirmado se guarda cifrado con su ETag y sus metodos`() = runTest {
        h.server.enqueue(MockResponse().setBody(MetricsFixtures.CATALOG).setHeader("ETag", MetricsFixtures.ETAG))

        assertEquals(MetricsRefresh.Updated, h.repository.refreshCatalog(h.owner))

        val request = h.takeRequest()
        assertEquals("/v1/training-metrics/catalog", request.path)
        assertNull(request.getHeader("If-None-Match"))
        val catalog = checkNotNull(h.local().catalog)
        assertEquals(MetricsFixtures.ETAG, catalog.etag)
        assertEquals(listOf(MetricsFixtures.FTP_ID, MetricsFixtures.COOPER_ID), catalog.methods.map { it.id })
        assertEquals(MetricsFixtures.NOW, catalog.syncedAt)
        val stored = String(h.dao.catalogs.getValue(h.owner).payload, Charsets.ISO_8859_1)
        assertFalse("Catálogo en claro", stored.contains("mean_power_watts"))
    }

    @Test
    fun `CA9 un 304 con If-None-Match renueva la confirmacion sin cambiar los metodos`() = runTest {
        h.cacheCatalog()
        h.clock.advanceHours(30)
        assertTrue(checkNotNull(h.local().catalog).isStale(h.clock.instant()))
        h.server.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", MetricsFixtures.ETAG))

        assertEquals(MetricsRefresh.Updated, h.repository.refreshCatalog(h.owner))

        assertEquals(MetricsFixtures.ETAG, h.takeRequest().getHeader("If-None-Match"))
        val catalog = checkNotNull(h.local().catalog)
        assertFalse(catalog.isStale(h.clock.instant()))
        assertEquals(2, catalog.methods.size)
    }

    @Test
    fun `CA9 sin red el catalogo cacheado se conserva con su fecha`() = runTest {
        h.cacheCatalog()
        h.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        assertEquals(MetricsRefresh.NoConnection, h.repository.refreshCatalog(h.owner))

        assertEquals(MetricsFixtures.NOW, h.local().catalog?.syncedAt)
    }

    @Test
    fun `sin la capacidad el BFF responde 403 capability_required y no se borra la cache`() = runTest {
        h.cacheCatalog()
        h.server.enqueue(SessionFixtures.error(403, "capability_required"))

        assertEquals(MetricsRefresh.CapabilityRequired, h.repository.refreshCatalog(h.owner))
        h.server.enqueue(SessionFixtures.error(403, "permission_required"))
        assertEquals(MetricsRefresh.PermissionRequired, h.repository.refreshCatalog(h.owner))
        h.server.enqueue(SessionFixtures.error(503, "upstream_unavailable"))
        assertTrue(h.repository.refreshCatalog(h.owner) is MetricsRefresh.Unavailable)
        assertTrue(h.local().catalog != null)
    }

    @Test
    fun `CA7 un tercer indicador escalar del catalogo se acepta sin cambiar el cliente`() = runTest {
        h.cacheCatalog(MetricsFixtures.CATALOG_WITH_THIRD)

        val third = checkNotNull(h.local().catalog).method("TEST_SCALAR_V1@3")
        assertTrue(checkNotNull(third).supported)
        assertEquals(listOf("repetitions", "heart_rate_bpm"), third.inputs.map { it.code })
    }

    // ---------------------------------------------------------------- historial

    @Test
    fun `CA5 y CA9 el historial confirmado se guarda con last_synced_at y en el orden del contrato`() = runTest {
        h.server.enqueue(
            MockResponse().setBody(MetricsFixtures.page(MetricsFixtures.ftpResult(), MetricsFixtures.cooperResult("c-2"), nextCursor = "opaco-1"))
        )

        assertEquals(MetricsRefresh.Updated, h.repository.refreshResults(h.owner))

        assertEquals("/v1/training-metrics/calculations?limit=20", h.takeRequest().path)
        val results = h.local().results
        assertEquals(listOf(MetricsFixtures.CALCULATION_ID, "c-2"), results.items.map { it.calculationId })
        assertEquals("opaco-1", results.nextCursor)
        assertEquals(MetricsFixtures.NOW, results.syncedAt)
        assertEquals("238", results.items.first().value.toPlainString())
        assertEquals("42.4", results.items.last().value.toPlainString())
    }

    @Test
    fun `un historial vacio es una respuesta confirmada, no un error`() = runTest {
        h.server.enqueue(MockResponse().setBody(MetricsFixtures.page()))

        assertEquals(MetricsRefresh.Updated, h.repository.refreshResults(h.owner))
        assertTrue(h.local().results.items.isEmpty())
    }

    @Test
    fun `un resultado no confirmado o sin valor numerico se rechaza sin inventar datos`() = runTest {
        h.server.enqueue(MockResponse().setBody(MetricsFixtures.page(MetricsFixtures.ftpResult().replace("\"value\":238", "\"value\":\"238\""))))

        assertEquals(MetricsRefresh.InvalidResponse, h.repository.refreshResults(h.owner))
        assertTrue(h.local().results.items.isEmpty())
    }

    @Test
    fun `la pagina siguiente usa el cursor opaco y no reemplaza la cache`() = runTest {
        h.server.enqueue(MockResponse().setBody(MetricsFixtures.page(MetricsFixtures.cooperResult("c-9"))))

        val more = h.repository.loadMore(h.owner, "opaco-1")

        assertEquals("/v1/training-metrics/calculations?limit=20&cursor=opaco-1", h.takeRequest().path)
        assertEquals(listOf("c-9"), (more as MoreResults.Loaded).items.map { it.calculationId })
        assertTrue(h.local().results.items.isEmpty())
    }

    // ---------------------------------------------------------------- captura (CA8)

    @Test
    fun `CA8 capturar guarda el calculo cifrado con identificadores estables antes de programar el envio`() = runTest {
        h.cacheCatalog()

        val queued = h.repository.submit(h.owner, MetricsFixtures.FTP_ID, listOf(MetricInputValue("mean_power_watts", 250, "W")), measuredAt)

        val calculationId = (queued as SubmitResult.Queued).calculationId
        val command = h.dao.commands.getValue(calculationId)
        assertEquals(MetricCommandEntity.PENDING_SYNC, command.syncState)
        assertTrue(command.idempotencyKey != calculationId)
        assertEquals(listOf(h.owner), h.scheduler.scheduled)
        assertFalse("Entrada en claro", String(command.payload, Charsets.ISO_8859_1).contains("mean_power_watts"))
        val pending = h.local().pending.single()
        assertEquals(MetricSyncState.PENDING_SYNC, pending.state)
        assertEquals(measuredAt, pending.request?.measuredAt)
        assertEquals("Solo el login y el catálogo: la captura no llama al BFF", 2, h.server.requestCount)
        assertTrue("Nunca hay resultado local", h.local().results.items.isEmpty())
    }

    @Test
    fun `CA9 no se habilita un metodo ausente del catalogo confirmado`() = runTest {
        assertEquals(SubmitResult.MethodUnavailable, h.repository.submit(h.owner, MetricsFixtures.FTP_ID, emptyList(), measuredAt))
        h.cacheCatalog()

        assertEquals(SubmitResult.MethodUnavailable, h.repository.submit(h.owner, "TEST_SCALAR_V1@3", emptyList(), measuredAt))
        assertTrue(h.dao.commands.isEmpty())
        assertTrue(h.scheduler.scheduled.isEmpty())
    }

    @Test
    fun `una falla de almacenamiento no programa ningun envio`() = runTest {
        h.cacheCatalog()
        h.dao.failWrites = true

        assertEquals(
            SubmitResult.StorageFailed,
            h.repository.submit(h.owner, MetricsFixtures.FTP_ID, listOf(MetricInputValue("mean_power_watts", 250, "W")), measuredAt),
        )
        assertTrue(h.scheduler.scheduled.isEmpty())
    }

    @Test
    fun `solo se descarta un calculo rechazado, nunca uno pendiente`() = runTest {
        h.cacheCatalog()
        val id = (h.repository.submit(h.owner, MetricsFixtures.FTP_ID, listOf(MetricInputValue("mean_power_watts", 250, "W")), measuredAt) as SubmitResult.Queued).calculationId

        assertFalse(h.repository.dismiss(h.owner, id))
        h.dao.replaceCommand(h.dao.commands.getValue(id).failed("VALIDATION", "inputs[0].value", "out_of_range"))
        assertTrue(h.repository.dismiss(h.owner, id))
        assertTrue(h.local().pending.isEmpty())
    }

    @Test
    fun `cada deportista solo ve su particion`() = runTest {
        h.cacheCatalog()
        h.repository.submit(h.owner, MetricsFixtures.FTP_ID, listOf(MetricInputValue("mean_power_watts", 250, "W")), measuredAt)

        val other = h.local(owner = "otra-huella")
        assertNull(other.catalog)
        assertTrue(other.pending.isEmpty())
        assertFalse(h.repository.hasPending("otra-huella"))
        assertTrue(h.repository.hasPending(h.owner))
    }
}
