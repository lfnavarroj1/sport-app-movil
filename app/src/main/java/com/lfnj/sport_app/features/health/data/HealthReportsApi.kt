package com.lfnj.sport_app.features.health.data

import com.lfnj.sport_app.features.health.model.BodyArea
import com.lfnj.sport_app.features.health.model.HealthReportDetails
import com.lfnj.sport_app.features.health.model.HealthReportType
import com.lfnj.sport_app.features.health.model.HealthSeverity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.time.Instant

/**
 * Operación de HU042 en `bff-mobile` 0.21.0 (ADR-037). La identidad sale del token: el cuerpo
 * nunca lleva `athlete_id`, texto libre ni diagnóstico.
 */
interface HealthReportsApi {
    @POST("v1/me/health-reports")
    suspend fun report(
        @Header("Authorization") authorization: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: ReportHealthRequestDto,
    ): Response<HealthReportResponseDto>
}

/** `HealthConsent`. */
@Serializable
data class HealthConsentDto(val version: String)

/** `ReportHealthRequest`. Los opcionales `null` no se envían (`explicitNulls = false`). */
@Serializable
data class ReportHealthRequestDto(
    @SerialName("report_id") val reportId: String,
    val type: String,
    @SerialName("body_area") val bodyArea: String,
    val severity: String,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("expected_recovery_days") val expectedRecoveryDays: Int? = null,
    val consent: HealthConsentDto,
) {
    override fun toString(): String = "ReportHealthRequestDto(<oculto>)"

    /** `null` si el cuerpo guardado ya no corresponde a los catálogos del cliente. */
    fun toDetails(): HealthReportDetails? {
        return HealthReportDetails(
            type = HealthReportType.fromWire(type) ?: return null,
            bodyArea = BodyArea.fromWire(bodyArea) ?: return null,
            severity = HealthSeverity.fromWire(severity) ?: return null,
            occurredAt = runCatching { Instant.parse(occurredAt) }.getOrNull() ?: return null,
            sessionId = sessionId,
            expectedRecoveryDays = expectedRecoveryDays,
        )
    }
}

/** `HealthReportResponse`: reporte confirmado `OPEN`, versión 1, sin diagnóstico ni recomendación. */
@Serializable
data class HealthReportResponseDto(
    @SerialName("report_id") val reportId: String,
    @SerialName("athlete_id") val athleteId: String,
    val type: String,
    @SerialName("body_area") val bodyArea: String,
    val severity: String,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("expected_recovery_days") val expectedRecoveryDays: Int? = null,
    val consent: HealthConsentDto,
    val status: String,
    @SerialName("reported_at") val reportedAt: String,
    val version: Int,
) {
    override fun toString(): String = "HealthReportResponseDto(status=$status, version=$version)"

    /**
     * `reported_at` si la respuesta confirma exactamente el reporte enviado ([sent]); `null` en otro
     * caso, para no presentar como confirmado algo distinto de lo capturado.
     */
    fun confirms(sent: ReportHealthRequestDto): Instant? {
        if (status != STATUS_OPEN || reportId != sent.reportId) return null
        if (type != sent.type || bodyArea != sent.bodyArea || severity != sent.severity) return null
        if (sessionId != sent.sessionId || expectedRecoveryDays != sent.expectedRecoveryDays) return null
        val occurred = runCatching { Instant.parse(occurredAt) }.getOrNull() ?: return null
        if (occurred != Instant.parse(sent.occurredAt)) return null
        return runCatching { Instant.parse(reportedAt) }.getOrNull()
    }

    companion object {
        const val STATUS_OPEN = "OPEN"
    }
}
