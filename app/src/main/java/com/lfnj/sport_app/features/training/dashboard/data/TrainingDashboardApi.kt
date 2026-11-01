package com.lfnj.sport_app.features.training.dashboard.data

import com.lfnj.sport_app.features.training.dashboard.model.DashboardRange
import com.lfnj.sport_app.features.training.dashboard.model.DashboardSnapshot
import com.lfnj.sport_app.features.training.dashboard.model.DashboardSourceState
import com.lfnj.sport_app.features.training.dashboard.model.DashboardSourceStatus
import com.lfnj.sport_app.features.training.dashboard.model.DashboardTotals
import com.lfnj.sport_app.features.training.dashboard.model.LatestMetric
import com.lfnj.sport_app.features.training.dashboard.model.SourceTotal
import com.lfnj.sport_app.features.training.dashboard.model.SourceTotals
import com.lfnj.sport_app.features.training.dashboard.model.SportTotal
import com.lfnj.sport_app.features.training.dashboard.model.WeekTotal
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/** Tablero de entrenamientos del deportista en `bff-mobile` (HU033, ADR-043 §1). */
interface TrainingDashboardApi {
    @GET("v1/me/training-dashboard")
    suspend fun dashboard(
        @Header("Authorization") authorization: String,
        @Query("from") from: String,
        @Query("to") to: String,
    ): Response<TrainingDashboardResponseDto>
}

/** `TrainingDashboardResponse`. Ningún `toString` expone actividad ni indicadores. */
@Serializable
data class TrainingDashboardResponseDto(
    val period: DashboardPeriodDto,
    @SerialName("has_data") val hasData: Boolean,
    val totals: DashboardTotalsDto,
    @SerialName("by_sport") val bySport: List<SportBreakdownDto>,
    @SerialName("by_week") val byWeek: List<WeekBreakdownDto>,
    @SerialName("by_source") val bySource: DashboardBySourceDto,
    @SerialName("latest_metrics") val latestMetrics: List<DashboardMetricDto>,
    val sources: List<DashboardSourceDto>,
) {
    override fun toString(): String = "TrainingDashboardResponseDto(hasData=$hasData, sources=${sources.size})"
}

/** `DashboardPeriodView`. */
@Serializable
data class DashboardPeriodDto(val from: String, val to: String)

/** `DashboardTotals`. */
@Serializable
data class DashboardTotalsDto(
    @SerialName("sessions_count") val sessionsCount: Long,
    @SerialName("total_duration_minutes") val totalDurationMinutes: Long,
    @SerialName("avg_duration_minutes") val avgDurationMinutes: Long,
    @SerialName("distance_meters") val distanceMeters: Long?,
) {
    override fun toString(): String = "DashboardTotalsDto(<oculto>)"
}

/** `SportBreakdown`. */
@Serializable
data class SportBreakdownDto(
    val sport: String,
    @SerialName("sessions_count") val sessionsCount: Long,
    @SerialName("total_duration_minutes") val totalDurationMinutes: Long,
    @SerialName("distance_meters") val distanceMeters: Long?,
) {
    override fun toString(): String = "SportBreakdownDto(<oculto>)"
}

/** `WeekBreakdown`. */
@Serializable
data class WeekBreakdownDto(
    @SerialName("week_start") val weekStart: String,
    @SerialName("sessions_count") val sessionsCount: Long,
    @SerialName("total_duration_minutes") val totalDurationMinutes: Long,
) {
    override fun toString(): String = "WeekBreakdownDto(<oculto>)"
}

/** `DashboardBySource`. */
@Serializable
data class DashboardBySourceDto(
    @SerialName("SPORTAPP") val sportApp: SourceBreakdownDto,
    @SerialName("STRAVA") val strava: SourceBreakdownDto,
)

/** `SourceBreakdown`. */
@Serializable
data class SourceBreakdownDto(
    @SerialName("sessions_count") val sessionsCount: Long,
    @SerialName("total_duration_minutes") val totalDurationMinutes: Long,
) {
    override fun toString(): String = "SourceBreakdownDto(<oculto>)"
}

/** `DashboardMetric`. `value` es un número JSON entero o decimal; se conserva exacto. */
@Serializable
data class DashboardMetricDto(
    val indicator: String,
    val value: JsonPrimitive,
    val unit: String,
    @SerialName("method_code") val methodCode: String,
    @SerialName("method_version") val methodVersion: Int,
    @SerialName("measured_at") val measuredAt: String,
    @SerialName("disclaimer_key") val disclaimerKey: String,
) {
    override fun toString(): String = "DashboardMetricDto(indicator=$indicator)"
}

/** `DashboardSource`. */
@Serializable
data class DashboardSourceDto(val source: String, val status: String)

/** Error de contrato: la respuesta no corresponde a ADR-043 y no se muestra parcialmente. */
class InvalidDashboardResponse : Exception()

/**
 * Convierte una respuesta `200` en el tablero confirmado de [range]. Cualquier campo obligatorio
 * mal formado invalida toda la respuesta: nunca se completan datos inventados (CA2).
 */
fun TrainingDashboardResponseDto.toSnapshot(range: DashboardRange, syncedAt: Instant): DashboardSnapshot = try {
    DashboardSnapshot(
        range = range,
        from = OffsetDateTime.parse(period.from).toInstant(),
        to = OffsetDateTime.parse(period.to).toInstant(),
        hasData = hasData,
        totals = DashboardTotals(totals.sessionsCount, totals.totalDurationMinutes, totals.avgDurationMinutes, totals.distanceMeters),
        bySport = bySport.map { SportTotal(it.sport, it.sessionsCount, it.totalDurationMinutes, it.distanceMeters) },
        byWeek = byWeek.map { WeekTotal(LocalDate.parse(it.weekStart), it.sessionsCount, it.totalDurationMinutes) },
        bySource = SourceTotals(bySource.sportApp.toModel(), bySource.strava.toModel()),
        latestMetrics = latestMetrics.map { it.toModel() },
        sources = sources.map { DashboardSourceState(it.source, DashboardSourceStatus.fromWire(it.status)) },
        syncedAt = syncedAt,
    )
} catch (_: DateTimeException) {
    throw InvalidDashboardResponse()
}

private fun SourceBreakdownDto.toModel() = SourceTotal(sessionsCount, totalDurationMinutes)

private fun DashboardMetricDto.toModel(): LatestMetric {
    if (value.isString) throw InvalidDashboardResponse()
    return LatestMetric(
        indicator = indicator,
        value = value.content.toBigDecimalOrNull() ?: throw InvalidDashboardResponse(),
        unit = unit,
        methodCode = methodCode,
        methodVersion = methodVersion,
        measuredAt = OffsetDateTime.parse(measuredAt).toInstant(),
        disclaimerKey = disclaimerKey,
    )
}
