package com.lfnj.sport_app.features.health.data.local

import com.lfnj.sport_app.core.security.TokenCipher
import com.lfnj.sport_app.features.health.data.ReportHealthRequestDto
import com.lfnj.sport_app.features.health.model.HealthSyncFailure
import com.lfnj.sport_app.features.health.model.HealthSyncState
import com.lfnj.sport_app.features.health.model.LocalHealthReport
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

/** Contenido cifrado de un reporte: el cuerpo enviado y, al confirmarse, `reported_at`. */
@Serializable
internal data class StoredHealthReport(
    val request: ReportHealthRequestDto,
    @SerialName("reported_at") val reportedAt: String? = null,
)

/**
 * Cifra y descifra los reportes de HU042 con AES-GCM y una clave no exportable de Android Keystore
 * propia. Un registro que no puede descifrarse se informa como ilegible y nunca se reemplaza por
 * valores inventados.
 */
class HealthReportsCodec(private val cipher: TokenCipher, private val json: Json) {

    fun encode(request: ReportHealthRequestDto, reportedAt: Instant? = null): ByteArray = cipher.encrypt(
        json.encodeToString(StoredHealthReport.serializer(), StoredHealthReport(request, reportedAt?.toString()))
            .toByteArray(Charsets.UTF_8)
    )

    fun decodeRequest(entity: HealthReportEntity): ReportHealthRequestDto? = stored(entity)?.request

    fun toLocal(entity: HealthReportEntity): LocalHealthReport {
        val stored = stored(entity)
        val details = stored?.request?.toDetails()
        val state = when {
            details == null -> HealthSyncState.SYNC_FAILED
            entity.syncState == HealthReportEntity.SYNCED -> HealthSyncState.SYNCED
            entity.syncState == HealthReportEntity.SYNC_FAILED -> HealthSyncState.SYNC_FAILED
            else -> HealthSyncState.PENDING_SYNC
        }
        return LocalHealthReport(
            reportId = entity.reportId,
            state = state,
            failure = if (details == null) HealthSyncFailure.UNREADABLE else HealthSyncFailure.fromWire(entity.failureReason),
            failureField = entity.failureField,
            failureIssue = entity.failureIssue,
            createdAt = Instant.ofEpochMilli(entity.createdAtMs),
            details = details,
            reportedAt = stored?.reportedAt?.takeIf { state == HealthSyncState.SYNCED }?.let { runCatching { Instant.parse(it) }.getOrNull() },
        )
    }

    /** Cualquier falla de descifrado o de formato se trata como registro ilegible. */
    private fun stored(entity: HealthReportEntity): StoredHealthReport? = try {
        json.decodeFromString(StoredHealthReport.serializer(), cipher.decrypt(entity.payload).toString(Charsets.UTF_8))
    } catch (_: Exception) {
        null
    }

    companion object {
        /** Alias de la clave no exportable de Android Keystore para los reportes de HU042. */
        const val KEY_ALIAS = "sportapp.health-reports.v1"
    }
}
