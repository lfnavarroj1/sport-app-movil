package com.lfnj.sport_app.features.health.data

import com.lfnj.sport_app.features.health.data.local.HealthReportEntity
import com.lfnj.sport_app.features.health.model.HealthSyncFailure
import com.lfnj.sport_app.features.health.model.HealthSyncState
import com.lfnj.sport_app.testing.HealthFixtures
import com.lfnj.sport_app.testing.HealthHarness
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** HU042 CA5: captura local cifrada antes de cualquier envío, partición y retiro de fallidos. */
class HealthReportsRepositoryTest {
    private val harness = HealthHarness()

    @After
    fun tearDown() = harness.shutdown()

    @Test
    fun `CA5 el reporte se guarda cifrado como PENDING_SYNC con report_id e Idempotency-Key estables y se programa`() = runTest {
        val result = harness.repository.submit(harness.owner, HealthFixtures.details(sessionId = HealthFixtures.SESSION_ID, recoveryDays = 7))

        val reportId = (result as HealthSubmitResult.Queued).reportId
        val entity = harness.dao.reports.getValue(reportId)
        assertEquals(HealthReportEntity.PENDING_SYNC, entity.syncState)
        assertEquals(harness.owner, entity.ownerKey)
        assertNotEquals(reportId, entity.idempotencyKey)
        assertEquals(listOf(harness.owner), harness.scheduler.scheduled)
        // El cifrado de prueba invierte los bytes: nada del reporte queda en claro.
        val stored = entity.payload.toString(Charsets.UTF_8)
        listOf("KNEE", "DISCOMFORT", "MODERATE", HealthFixtures.SESSION_ID, HealthFixtures.CONSENT_VERSION).forEach {
            assertFalse("$it en claro", it in stored)
        }
        val body = checkNotNull(harness.codec.decodeRequest(entity))
        assertEquals(reportId, body.reportId)
        assertEquals("2026-10-24T18:30:00Z", body.occurredAt)
        assertEquals(HealthFixtures.CONSENT_VERSION, body.consent.version)
        assertEquals(7, body.expectedRecoveryDays)
        assertEquals(HealthFixtures.SESSION_ID, body.sessionId)

        val local = harness.local().single()
        assertEquals(HealthSyncState.PENDING_SYNC, local.state)
        assertEquals(null, local.reportedAt)
    }

    @Test
    fun `cada reporte recibe identificadores nuevos`() = runTest {
        val first = harness.repository.submit(harness.owner, HealthFixtures.details()) as HealthSubmitResult.Queued
        val second = harness.repository.submit(harness.owner, HealthFixtures.details()) as HealthSubmitResult.Queued

        assertNotEquals(first.reportId, second.reportId)
        assertEquals(2, harness.dao.reports.values.map { it.idempotencyKey }.toSet().size)
    }

    @Test
    fun `sin version de consentimiento configurada no se guarda nada`() = runTest {
        val harness = HealthHarness(consentVersion = " ")
        try {
            assertEquals(null, harness.repository.consentVersion)
            assertEquals(HealthSubmitResult.ConsentUnavailable, harness.repository.submit(harness.owner, HealthFixtures.details()))
            assertTrue(harness.dao.reports.isEmpty())
            assertTrue(harness.scheduler.scheduled.isEmpty())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun `una falla de almacenamiento no programa envio ni deja datos`() = runTest {
        harness.dao.failWrites = true

        assertEquals(HealthSubmitResult.StorageFailed, harness.repository.submit(harness.owner, HealthFixtures.details()))
        assertTrue(harness.scheduler.scheduled.isEmpty())
    }

    @Test
    fun `cada deportista solo observa su particion`() = runTest {
        harness.repository.submit(harness.owner, HealthFixtures.details())

        assertEquals(1, harness.local().size)
        assertTrue(harness.local(harness.otherOwner).isEmpty())
    }

    @Test
    fun `solo se retira un reporte fallido o ilegible, nunca uno pendiente`() = runTest {
        val pending = (harness.repository.submit(harness.owner, HealthFixtures.details()) as HealthSubmitResult.Queued).reportId
        val failed = (harness.repository.submit(harness.owner, HealthFixtures.details()) as HealthSubmitResult.Queued).reportId
        harness.dao.replaceReport(harness.dao.reports.getValue(failed).failed(HealthSyncFailure.CONFLICT.name))

        assertFalse(harness.repository.dismiss(harness.owner, pending))
        assertFalse(harness.repository.dismiss(harness.otherOwner, failed))
        assertTrue(harness.repository.dismiss(harness.owner, failed))
        assertEquals(listOf(pending), harness.dao.reports.keys.toList())
    }

    @Test
    fun `un registro que no puede descifrarse se informa ilegible y no se presenta como confirmado`() = runTest {
        val id = (harness.repository.submit(harness.owner, HealthFixtures.details()) as HealthSubmitResult.Queued).reportId
        harness.dao.replaceReport(harness.dao.reports.getValue(id).synced(harness.codec.encode(checkNotNull(harness.codec.decodeRequest(harness.dao.reports.getValue(id))))))
        harness.cipher.broken = true

        val local = harness.local().single()
        assertEquals(HealthSyncState.SYNC_FAILED, local.state)
        assertEquals(HealthSyncFailure.UNREADABLE, local.failure)
        assertEquals(null, local.details)
        assertTrue(local.dismissible)
    }

    @Test
    fun `la limpieza al terminar la sesion borra los resueltos y conserva cifrados los pendientes`() = runTest {
        val pending = (harness.repository.submit(harness.owner, HealthFixtures.details()) as HealthSubmitResult.Queued).reportId
        val synced = (harness.repository.submit(harness.owner, HealthFixtures.details()) as HealthSubmitResult.Queued).reportId
        val failed = (harness.repository.submit(harness.owner, HealthFixtures.details()) as HealthSubmitResult.Queued).reportId
        val other = (harness.repository.submit(harness.otherOwner, HealthFixtures.details()) as HealthSubmitResult.Queued).reportId
        harness.dao.replaceReport(harness.dao.reports.getValue(synced).synced(harness.dao.reports.getValue(synced).payload))
        harness.dao.replaceReport(harness.dao.reports.getValue(failed).failed(HealthSyncFailure.REJECTED.name))

        harness.repository.clearSettled(harness.owner)

        assertEquals(setOf(pending, other), harness.dao.reports.keys)
        assertTrue(harness.repository.hasPending(harness.owner))
    }
}
