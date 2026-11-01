package com.lfnj.sport_app.features.health.data

import com.lfnj.sport_app.features.health.data.local.HealthReportEntity
import com.lfnj.sport_app.features.health.data.local.HealthReportsCodec
import com.lfnj.sport_app.features.health.data.local.HealthReportsDao
import com.lfnj.sport_app.features.health.model.HealthReportDetails
import com.lfnj.sport_app.features.health.model.LocalHealthReport
import com.lfnj.sport_app.features.training.session.data.TrainingSyncScheduler
import com.lfnj.sport_app.features.training.session.data.local.TransactionRunner
import com.lfnj.sport_app.features.training.session.data.toWireInstant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.util.UUID

sealed interface HealthSubmitResult {
    /** El reporte quedó cifrado como `PENDING_SYNC` y se programó la sincronización (CA5). */
    data class Queued(val reportId: String) : HealthSubmitResult
    /** La variante no tiene versión de consentimiento configurada: no se captura nada. */
    data object ConsentUnavailable : HealthSubmitResult
    data object StorageFailed : HealthSubmitResult
}

/** Operaciones de la pantalla «Mi salud»; las implementa [HealthReportsRepository]. */
interface HealthReportsGateway {
    /** Versión vigente del consentimiento de datos de salud, o `null` si la variante no la tiene. */
    val consentVersion: String?
    fun observe(owner: String): Flow<List<LocalHealthReport>>
    suspend fun submit(owner: String, details: HealthReportDetails): HealthSubmitResult
    suspend fun dismiss(owner: String, reportId: String): Boolean
}

/**
 * Reportes de salud offline first (ADR-026 y ADR-037 HU042 §4). La pantalla observa la fuente local
 * cifrada; un reporte se guarda primero como `PENDING_SYNC` con su `report_id` (UUID v4) y su
 * `Idempotency-Key`, y lo envía el trabajo durable. Nunca se presenta como confirmado antes del `201`.
 */
class HealthReportsRepository(
    private val dao: HealthReportsDao,
    private val transactions: TransactionRunner,
    private val codec: HealthReportsCodec,
    private val scheduler: TrainingSyncScheduler,
    consentVersion: String?,
    private val clock: Clock = Clock.systemUTC(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : HealthReportsGateway {

    override val consentVersion: String? = consentVersion?.trim()?.takeIf { it.isNotEmpty() }

    override fun observe(owner: String): Flow<List<LocalHealthReport>> =
        dao.observeReports(owner).map { reports -> reports.map(codec::toLocal) }.distinctUntilChanged()

    suspend fun hasPending(owner: String): Boolean = dao.countPending(owner) > 0

    /** Guarda el reporte cifrado antes de cualquier envío y programa la sincronización. */
    override suspend fun submit(owner: String, details: HealthReportDetails): HealthSubmitResult {
        val consent = consentVersion ?: return HealthSubmitResult.ConsentUnavailable
        val reportId = newId()
        val body = ReportHealthRequestDto(
            reportId = reportId,
            type = details.type.wire,
            bodyArea = details.bodyArea.wire,
            severity = details.severity.wire,
            occurredAt = details.occurredAt.toWireInstant(),
            sessionId = details.sessionId,
            expectedRecoveryDays = details.expectedRecoveryDays,
            consent = HealthConsentDto(consent),
        )
        storage {
            dao.insertReport(
                HealthReportEntity(
                    reportId = reportId,
                    ownerKey = owner,
                    idempotencyKey = newId(),
                    syncState = HealthReportEntity.PENDING_SYNC,
                    createdAtMs = clock.millis(),
                    payload = codec.encode(body),
                )
            )
        } ?: return HealthSubmitResult.StorageFailed
        scheduler.schedule(owner)
        return HealthSubmitResult.Queued(reportId)
    }

    /** Retira del dispositivo un reporte rechazado, vencido o ilegible ya informado; nunca uno pendiente. */
    override suspend fun dismiss(owner: String, reportId: String): Boolean = storage {
        transactions.run {
            val entity = dao.report(owner, reportId)
            val failed = entity != null &&
                (entity.syncState == HealthReportEntity.SYNC_FAILED || codec.decodeRequest(entity) == null)
            if (failed) dao.deleteReport(owner, reportId)
            failed
        }
    } ?: false

    /**
     * Limpieza al terminar la sesión de [owner] ([com.lfnj.sport_app.features.health.sync.HealthReportsSessionPolicy]):
     * borra los reportes ya resueltos y conserva cifrados los pendientes, que solo reanuda el mismo
     * deportista (ADR-029 offline §4).
     */
    suspend fun clearSettled(owner: String) {
        dao.deleteSettled(owner)
    }

    /** Errores de Room o del Keystore se informan como falla de almacenamiento, sin detalles. */
    private suspend fun <T> storage(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
