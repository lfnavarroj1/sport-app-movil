package com.lfnj.sport_app.features.health.model

import com.lfnj.sport_app.features.health.recentConfirmedSessions
import com.lfnj.sport_app.features.training.session.model.LocalTrainingSession
import com.lfnj.sport_app.features.training.session.model.SyncFailure
import com.lfnj.sport_app.features.training.session.model.SyncState
import com.lfnj.sport_app.features.training.session.model.TrainingSessionDetails
import com.lfnj.sport_app.features.training.session.model.TrainingSport
import com.lfnj.sport_app.testing.HealthFixtures
import com.lfnj.sport_app.testing.TrainingFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** HU042: catálogos cerrados de ADR-037 y validaciones de experiencia del formulario. */
class HealthReportModelsTest {
    private val now = HealthFixtures.NOW

    @Test
    fun `los catalogos son exactamente los de ADR-037`() {
        assertEquals(listOf("DISCOMFORT", "INJURY", "INCAPACITY"), HealthReportType.entries.map { it.wire })
        assertEquals(
            listOf("HEAD", "NECK", "SHOULDER", "ARM", "ELBOW", "WRIST_HAND", "CHEST", "BACK", "HIP", "THIGH", "KNEE", "LOWER_LEG", "ANKLE_FOOT", "OTHER"),
            BodyArea.entries.map { it.wire },
        )
        assertEquals(listOf("LOW", "MODERATE", "HIGH"), HealthSeverity.entries.map { it.wire })
        assertNull(BodyArea.fromWire("FOOT"))
        assertNull(HealthReportType.fromWire("DIAGNOSIS"))
    }

    @Test
    fun `CA6 solo la severidad alta sugiere consultar a un profesional`() {
        assertTrue(HealthSeverity.HIGH.suggestsProfessional)
        assertFalse(HealthSeverity.MODERATE.suggestsProfessional)
        assertFalse(HealthSeverity.LOW.suggestsProfessional)
    }

    @Test
    fun `la fecha admite hasta 5 minutos en el futuro y 365 dias en el pasado`() {
        assertNull(HealthReportValidation.validateOccurredAt(now.plus(Duration.ofMinutes(5)), now))
        assertEquals(OccurredAtError.IN_FUTURE, HealthReportValidation.validateOccurredAt(now.plus(Duration.ofMinutes(5)).plusSeconds(1), now))
        assertNull(HealthReportValidation.validateOccurredAt(now.minus(Duration.ofDays(365)), now))
        assertEquals(OccurredAtError.TOO_OLD, HealthReportValidation.validateOccurredAt(now.minus(Duration.ofDays(365)).minusSeconds(1), now))
    }

    @Test
    fun `los dias de recuperacion son opcionales y enteros entre 0 y 365`() {
        assertEquals(HealthReportValidation.Recovery.Valid(null), HealthReportValidation.parseRecoveryDays(" "))
        assertEquals(HealthReportValidation.Recovery.Valid(0), HealthReportValidation.parseRecoveryDays("0"))
        assertEquals(HealthReportValidation.Recovery.Valid(365), HealthReportValidation.parseRecoveryDays("365"))
        assertEquals(HealthReportValidation.Recovery.Invalid(RecoveryDaysError.OUT_OF_RANGE), HealthReportValidation.parseRecoveryDays("366"))
        assertEquals(HealthReportValidation.Recovery.Invalid(RecoveryDaysError.NOT_INTEGER), HealthReportValidation.parseRecoveryDays("2.5"))
        assertEquals(HealthReportValidation.Recovery.Invalid(RecoveryDaysError.NOT_INTEGER), HealthReportValidation.parseRecoveryDays("-1"))
    }

    @Test
    fun `la particion es la misma huella de HU032 y no contiene el user_id`() {
        val key = HealthOwner.keyOf(TrainingFixtures.USER_ID)
        assertEquals(64, key.length)
        assertFalse(TrainingFixtures.USER_ID in key)
        assertFalse(key == HealthOwner.keyOf(TrainingFixtures.OTHER_USER_ID))
    }

    @Test
    fun `el selector solo ofrece sesiones propias confirmadas, de la mas reciente a la mas antigua`() {
        fun session(id: String, state: SyncState, start: String, readable: Boolean = true) = LocalTrainingSession(
            sessionId = id,
            syncState = state,
            failure = if (state == SyncState.SYNC_FAILED) SyncFailure.REJECTED else null,
            details = if (readable) TrainingSessionDetails(TrainingSport.RUNNING, null, Instant.parse(start), null, null) else null,
        )
        val sessions = listOf(
            session("a", SyncState.SYNCED, "2026-10-20T10:00:00Z"),
            session("b", SyncState.PENDING_SYNC, "2026-10-23T10:00:00Z"),
            session("c", SyncState.SYNCED, "2026-10-22T10:00:00Z"),
            session("d", SyncState.SYNC_FAILED, "2026-10-21T10:00:00Z"),
            session("e", SyncState.SYNCED, "2026-10-24T10:00:00Z", readable = false),
        )

        assertEquals(listOf("c", "a"), sessions.recentConfirmedSessions().map { it.sessionId })
        assertEquals(listOf("c"), sessions.recentConfirmedSessions(limit = 1).map { it.sessionId })
    }

    @Test
    fun `los datos de salud no se exponen al convertirse en texto`() {
        val text = HealthFixtures.details(severity = HealthSeverity.HIGH).toString()
        assertFalse(text, "HIGH" in text || "KNEE" in text || "DISCOMFORT" in text)
    }
}
