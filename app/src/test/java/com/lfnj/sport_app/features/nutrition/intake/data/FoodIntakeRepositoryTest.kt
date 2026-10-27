package com.lfnj.sport_app.features.nutrition.intake.data

import com.lfnj.sport_app.features.nutrition.intake.model.FoodSyncState
import com.lfnj.sport_app.features.nutrition.intake.model.FoodUnit
import com.lfnj.sport_app.features.nutrition.intake.model.MealType
import com.lfnj.sport_app.testing.FoodFixtures
import com.lfnj.sport_app.testing.FoodHarness
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/** HU043 CA5 y CA6: catálogo en caché con `ETag` y encolado cifrado (ADR-037 §5). Contrato simulado. */
class FoodIntakeRepositoryTest {
    private val h = FoodHarness()

    @Before
    fun setUp() = runTest { h.login() }

    @After
    fun tearDown() = h.shutdown()

    @Test
    fun `el catalogo confirmado se guarda cifrado con su ETag y last_synced_at`() = runTest {
        h.server.enqueue(MockResponse().setBody(FoodFixtures.CATALOG).setHeader("ETag", FoodFixtures.ETAG))

        assertEquals(CatalogRefresh.Updated, h.repository.refreshCatalog(h.owner))

        val request = h.takeRequest()
        assertEquals("/v1/food-catalog", request.path)
        assertNull(request.getHeader("If-None-Match"))
        val catalog = checkNotNull(h.local().catalog)
        assertEquals(FoodFixtures.ETAG, catalog.etag)
        assertEquals(FoodFixtures.NOW, catalog.syncedAt)
        assertEquals(listOf("OATS", "EGG", "MILK", "WATER"), catalog.items.map { it.foodCode })
        val stored = h.dao.catalogs.getValue(h.owner).payload.toString(Charsets.UTF_8)
        assertFalse("En reposo no hay alimentos en claro", "OATS" in stored || "food.oats" in stored)
    }

    @Test
    fun `un 304 conserva el catalogo y renueva su confirmacion enviando If-None-Match`() = runTest {
        h.cacheCatalog()
        h.clock.advanceHours(30)
        assertTrue(checkNotNull(h.local().catalog).isStale(h.clock.instant()))
        h.server.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", FoodFixtures.ETAG))

        assertEquals(CatalogRefresh.Updated, h.repository.refreshCatalog(h.owner))

        assertEquals(FoodFixtures.ETAG, h.takeRequest().getHeader("If-None-Match"))
        val catalog = checkNotNull(h.local().catalog)
        assertFalse(catalog.isStale(h.clock.instant()))
        assertEquals(4, catalog.items.size)
    }

    @Test
    fun `CA5 sin red se conserva la cache y sin cache no hay alimentos`() = runTest {
        h.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals(CatalogRefresh.NoConnection, h.repository.refreshCatalog(h.owner))
        assertNull(h.local().catalog)
        assertEquals(
            FoodSubmitResult.CatalogUnavailable,
            h.repository.submit(h.owner, MealType.BREAKFAST, FoodFixtures.CONSUMED_AT, listOf(FoodFixtures.OATS_50)),
        )
        assertTrue(h.dao.intakes.isEmpty())

        h.cacheCatalog()
        h.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals(CatalogRefresh.NoConnection, h.repository.refreshCatalog(h.owner))
        assertEquals(4, h.local().catalog?.items?.size)
    }

    @Test
    fun `los errores del catalogo se traducen sin datos`() = runTest {
        h.server.enqueue(MockResponse().setResponseCode(403).setBody(FoodFixtures.error("permission_required")))
        assertEquals(CatalogRefresh.PermissionRequired, h.repository.refreshCatalog(h.owner))
        h.takeRequest()
        h.server.enqueue(MockResponse().setResponseCode(503).setBody(FoodFixtures.error("upstream_unavailable")))
        assertTrue(h.repository.refreshCatalog(h.owner) is CatalogRefresh.Unavailable)
        h.takeRequest()
        h.server.enqueue(MockResponse().setResponseCode(304))
        assertEquals("304 sin caché no inventa catálogo", CatalogRefresh.InvalidResponse, h.repository.refreshCatalog(h.owner))
    }

    @Test
    fun `CA5 el consumo se guarda cifrado con intake_id e Idempotency-Key antes de enviarse`() = runTest {
        h.cacheCatalog()

        val id = h.capture(listOf(FoodFixtures.OATS_50, FoodIntakeDraftItem("MILK", "250.0", FoodUnit.ML)))

        val entity = h.dao.intakes.getValue(id)
        assertEquals("PENDING_SYNC", entity.syncState)
        assertNotEquals(entity.intakeId, entity.idempotencyKey)
        assertEquals(listOf(h.owner), h.scheduler.scheduled)
        assertEquals("Solo login y catálogo: capturar no envía nada", 2, h.server.requestCount)
        val plain = entity.payload.toString(Charsets.UTF_8)
        assertFalse("En reposo no hay alimentos ni cantidades en claro", "OATS" in plain || "250.0" in plain || "BREAKFAST" in plain)
        val local = h.local().intakes.single()
        assertEquals(FoodSyncState.PENDING_SYNC, local.state)
        assertEquals(listOf("food.oats", "food.milk"), local.details?.items?.map { it.labelKey })
        assertEquals(FoodFixtures.CONSUMED_AT, local.details?.consumedAt)
    }

    @Test
    fun `CA6 no se admiten alimentos ni unidades ausentes de la cache`() = runTest {
        h.cacheCatalog()

        assertEquals(
            FoodSubmitResult.CatalogUnavailable,
            h.repository.submit(h.owner, MealType.LUNCH, FoodFixtures.CONSUMED_AT, listOf(FoodIntakeDraftItem("PIZZA", "1.0", FoodUnit.UNIT))),
        )
        assertEquals(
            FoodSubmitResult.CatalogUnavailable,
            h.repository.submit(h.owner, MealType.LUNCH, FoodFixtures.CONSUMED_AT, listOf(FoodIntakeDraftItem("MILK", "1.0", FoodUnit.G))),
        )
        assertTrue(h.dao.intakes.isEmpty())
        assertTrue(h.scheduler.scheduled.isEmpty())
    }

    @Test
    fun `una falla de almacenamiento no programa nada`() = runTest {
        h.cacheCatalog()
        h.dao.failWrites = true

        assertEquals(
            FoodSubmitResult.StorageFailed,
            h.repository.submit(h.owner, MealType.SNACK, FoodFixtures.CONSUMED_AT, listOf(FoodFixtures.OATS_50)),
        )
        assertTrue(h.scheduler.scheduled.isEmpty())
    }

    @Test
    fun `al terminar la sesion se borran cache y resueltos y se conservan los pendientes`() = runTest {
        h.cacheCatalog()
        val pending = h.capture()
        val failed = h.capture()
        h.dao.replaceIntake(h.dao.intakes.getValue(failed).failed("CONFLICT"))

        h.repository.clearOnSessionEnd(h.owner)

        assertNull(h.dao.catalogs[h.owner])
        assertEquals(setOf(pending), h.dao.intakes.keys)
        assertTrue(h.repository.hasPending(h.owner))
        assertFalse("Un pendiente nunca se retira", h.repository.dismiss(h.owner, pending))
    }

    @Test
    fun `solo se retira del dispositivo un consumo fallido y solo de su particion`() = runTest {
        h.cacheCatalog()
        val id = h.capture()
        h.dao.replaceIntake(h.dao.intakes.getValue(id).failed("REJECTED"))

        assertFalse(h.repository.dismiss("otra-particion", id))
        assertTrue(h.repository.dismiss(h.owner, id))
        assertTrue(h.dao.intakes.isEmpty())
    }

    @Test
    fun `cada particion ve solo sus consumos`() = runTest {
        h.cacheCatalog()
        h.capture()

        assertTrue(h.local("otra-particion").intakes.isEmpty())
        assertNull(h.local("otra-particion").catalog)
        assertEquals(Instant.parse("2026-10-24T12:30:00Z"), h.local().intakes.single().details?.consumedAt)
    }
}
