package com.lfnj.sport_app.features.nutrition.intake.sync

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.features.nutrition.intake.data.FoodIntakeDraftItem
import com.lfnj.sport_app.features.nutrition.intake.model.FoodSyncFailure
import com.lfnj.sport_app.features.nutrition.intake.model.FoodSyncState
import com.lfnj.sport_app.features.nutrition.intake.model.FoodUnit
import com.lfnj.sport_app.features.training.session.sync.SyncRun
import com.lfnj.sport_app.testing.FakeTokenStore
import com.lfnj.sport_app.testing.FoodFixtures
import com.lfnj.sport_app.testing.FoodHarness
import com.lfnj.sport_app.testing.SerialTransactionRunner
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

/** HU043 CA1, CA3 y CA5: cola de consumos frente a `bff-mobile` simulado (ADR-037 §5, política de ADR-029). */
class FoodIntakeSyncEngineTest {
    private val h = FoodHarness()

    @Before
    fun setUp() = runTest {
        h.login()
        h.cacheCatalog()
    }

    @After
    fun tearDown() = h.shutdown()

    private fun confirmed(id: String) = MockResponse().setResponseCode(201).setBody(FoodFixtures.response(id))

    @Test
    fun `CA1 y CA5 el consumo pendiente se envia con su clave y queda confirmado con version 1`() = runTest {
        val id = h.capture()
        h.server.enqueue(confirmed(id))

        assertEquals(SyncRun.COMPLETED, h.engine.run(h.owner))

        val request = h.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/me/food-intakes", request.path)
        UUID.fromString(checkNotNull(request.getHeader("Idempotency-Key")))
        assertEquals(
            """{"intake_id":"$id","consumed_at":"2026-10-24T12:30:00Z","meal_type":"BREAKFAST",""" +
                """"items":[{"food_code":"OATS","quantity":"50.0","unit":"G"}]}""",
            request.body.readUtf8(),
        )
        val local = h.local().intakes.single()
        assertEquals(FoodSyncState.SYNCED, local.state)
        assertEquals(Instant.parse("2026-10-24T12:35:02Z"), local.registeredAt)
        assertEquals("food.oats", local.details?.items?.single()?.labelKey)
        assertTrue(h.dao.intakes.getValue(id).attempts == 1)
    }

    @Test
    fun `CA3 tras una respuesta perdida el reintento reutiliza clave y cuerpo y sincroniza una sola vez`() = runTest {
        val id = h.capture()
        h.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        assertEquals(SyncRun.RETRY, h.engine.run(h.owner))
        val first = h.takeRequest()
        assertEquals(FoodSyncState.PENDING_SYNC, h.local().intakes.single().state)

        h.server.enqueue(confirmed(id))
        assertEquals(SyncRun.COMPLETED, h.engine.run(h.owner))
        val second = h.takeRequest()
        assertEquals(first.getHeader("Idempotency-Key"), second.getHeader("Idempotency-Key"))
        assertEquals(first.body.readUtf8(), second.body.readUtf8())

        assertEquals(SyncRun.COMPLETED, h.engine.run(h.owner))
        assertEquals("Confirmado no se vuelve a enviar", 4, h.server.requestCount)
    }

    @Test
    fun `solo se reintentan 502 503 y 504`() = runTest {
        h.capture()
        listOf(502, 503, 504).forEach { status ->
            h.server.enqueue(MockResponse().setResponseCode(status).setBody(FoodFixtures.error("upstream_unavailable")))
            assertEquals(SyncRun.RETRY, h.engine.run(h.owner))
            h.takeRequest()
        }
        assertEquals(FoodSyncState.PENDING_SYNC, h.local().intakes.single().state)
    }

    @Test
    fun `CA3 un 409 intake_id_reused nunca se presenta como confirmado`() = runTest {
        val id = h.capture()
        h.server.enqueue(MockResponse().setResponseCode(409).setBody(FoodFixtures.error("intake_id_reused")))

        assertEquals(SyncRun.COMPLETED, h.engine.run(h.owner))

        val local = h.local().intakes.single()
        assertEquals(FoodSyncState.SYNC_FAILED, local.state)
        assertEquals(FoodSyncFailure.CONFLICT, local.failure)
        assertEquals(id, local.intakeId)
    }

    @Test
    fun `CA2 un 422 por alimento o unidad deja SYNC_FAILED explicable sin reintentar`() = runTest {
        h.capture()
        h.server.enqueue(MockResponse().setResponseCode(422).setBody(FoodFixtures.validationError("items.0.unit", "unit_not_allowed")))
        h.engine.run(h.owner)
        assertEquals(FoodSyncFailure.CATALOG_CHANGED, h.local().intakes.single().failure)
        assertEquals("unit_not_allowed", h.local().intakes.single().failureIssue)

        h.capture()
        h.server.enqueue(MockResponse().setResponseCode(422).setBody(FoodFixtures.validationError("consumed_at", "too_far_in_future")))
        h.engine.run(h.owner)
        val failures = h.local().intakes.map { it.failure }.toSet()
        assertEquals(setOf(FoodSyncFailure.CATALOG_CHANGED, FoodSyncFailure.VALIDATION), failures)
        assertEquals(4, h.server.requestCount)
    }

    @Test
    fun `un 403 deja el consumo rechazado por permiso`() = runTest {
        h.capture()
        h.server.enqueue(MockResponse().setResponseCode(403).setBody(FoodFixtures.error("permission_required")))

        h.engine.run(h.owner)

        assertEquals(FoodSyncFailure.PERMISSION_REQUIRED, h.local().intakes.single().failure)
    }

    @Test
    fun `una respuesta que no corresponde al consumo enviado no lo confirma`() = runTest {
        val id = h.capture(listOf(FoodIntakeDraftItem("MILK", "250.0", FoodUnit.ML)))
        h.server.enqueue(MockResponse().setResponseCode(201).setBody(FoodFixtures.response(id, items = """[{"food_code":"MILK","quantity":"200.0","unit":"ML"}]""")))

        h.engine.run(h.owner)

        assertEquals(FoodSyncFailure.INVALID_RESPONSE, h.local().intakes.single().failure)
    }

    @Test
    fun `los consumos se envian en orden de captura`() = runTest {
        val first = h.capture()
        h.clock.instant = h.clock.instant.plusSeconds(60)
        val second = h.capture(listOf(FoodIntakeDraftItem("WATER", "500.0", FoodUnit.ML)))
        h.server.enqueue(confirmed(first))
        h.server.enqueue(MockResponse().setResponseCode(201).setBody(FoodFixtures.response(second, items = """[{"food_code":"WATER","quantity":"500.0","unit":"ML"}]""")))

        assertEquals(SyncRun.COMPLETED, h.engine.run(h.owner))

        assertTrue(h.takeRequest().body.readUtf8().contains(first))
        assertTrue(h.takeRequest().body.readUtf8().contains(second))
        assertTrue(h.local().intakes.all { it.state == FoodSyncState.SYNCED })
    }

    @Test
    fun `un consumo con mas de 30 dias vence y queda SYNC_FAILED sin enviarse`() = runTest {
        h.capture()
        h.clock.advanceHours(30 * 24 + 1)

        assertEquals(SyncRun.COMPLETED, h.engine.run(h.owner))

        assertEquals(FoodSyncFailure.EXPIRED, h.local().intakes.single().failure)
        assertEquals(2, h.server.requestCount)
    }

    @Test
    fun `sin red se reintenta y el consumo sigue pendiente`() = runTest {
        h.capture()
        h.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        assertEquals(SyncRun.RETRY, h.engine.run(h.owner))
        assertEquals(FoodSyncState.PENDING_SYNC, h.local().intakes.single().state)
    }

    @Test
    fun `un registro ilegible queda SYNC_FAILED sin enviarse`() = runTest {
        h.capture()
        h.cipher.broken = true

        h.engine.run(h.owner)

        h.cipher.broken = false
        assertEquals(FoodSyncFailure.UNREADABLE, h.local().intakes.single().failure)
        assertNull(h.local().intakes.single().registeredAt)
    }

    @Test
    fun `otra particion o una sesion cerrada suspenden la cola sin enviar`() = runTest {
        h.capture()
        assertEquals(SyncRun.SUSPENDED, h.engine.run("otra-particion"))

        val signedOut = SessionManager(
            BffMobileClient.retrofit(h.server.url("/").toString()).create(SessionApi::class.java), FakeTokenStore(), BffMobileClient.json,
        )
        val engine = FoodIntakeSyncEngine(h.dao, SerialTransactionRunner(), h.codec, h.api, signedOut, BffMobileClient.json, h.clock)
        assertEquals(SyncRun.SUSPENDED, engine.run(h.owner))
        assertEquals(2, h.server.requestCount)
        assertEquals(FoodSyncState.PENDING_SYNC, h.local().intakes.single().state)
    }
}
