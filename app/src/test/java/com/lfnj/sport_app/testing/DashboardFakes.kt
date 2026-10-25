package com.lfnj.sport_app.testing

import com.lfnj.sport_app.features.training.dashboard.data.local.TrainingDashboardCacheDao
import com.lfnj.sport_app.features.training.dashboard.data.local.TrainingDashboardCacheEntity
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Datos sintéticos de HU033 con la forma de `TrainingDashboardResponse` (`bff-mobile` 0.15.0, ADR-043). */
object DashboardFixtures {
    fun metric(
        indicator: String = "FTP_ESTIMATED",
        value: String = "238",
        unit: String = "W",
        method: String = "FTP_20_MIN_95_V1",
        measuredAt: String = "2026-10-20T12:30:00Z",
    ) = """{"indicator":"$indicator","value":$value,"unit":"$unit","method_code":"$method","method_version":1,""" +
        """"measured_at":"$measuredAt","disclaimer_key":"estimated_not_diagnostic"}"""

    fun source(name: String, status: String = "OK") = """{"source":"$name","status":"$status"}"""

    /** Dos sesiones en dos semanas ISO distintas y una FTP, como el E2E de ADR-043. */
    fun body(
        from: String = "2026-09-26T00:00:00-05:00",
        to: String = "2026-10-26T00:00:00-05:00",
        hasData: Boolean = true,
        totals: String = """{"sessions_count":2,"total_duration_minutes":95,"avg_duration_minutes":48,"distance_meters":null}""",
        bySport: List<String> = listOf(
            """{"sport":"running","sessions_count":1,"total_duration_minutes":45,"distance_meters":null}""",
            """{"sport":"cycling","sessions_count":1,"total_duration_minutes":50,"distance_meters":null}""",
        ),
        byWeek: List<String> = listOf(
            """{"week_start":"2026-10-12","sessions_count":1,"total_duration_minutes":45}""",
            """{"week_start":"2026-10-19","sessions_count":1,"total_duration_minutes":50}""",
        ),
        bySource: String = """{"SPORTAPP":{"sessions_count":2,"total_duration_minutes":95},"STRAVA":{"sessions_count":0,"total_duration_minutes":0}}""",
        metrics: List<String> = listOf(metric()),
        sources: List<String> = listOf(source("training_sessions"), source("training_metrics")),
    ): String =
        """{"period":{"from":"$from","to":"$to"},"has_data":$hasData,"totals":$totals,""" +
            """"by_sport":${bySport.joinToString(",", "[", "]")},"by_week":${byWeek.joinToString(",", "[", "]")},""" +
            """"by_source":$bySource,"latest_metrics":${metrics.joinToString(",", "[", "]")},""" +
            """"sources":${sources.joinToString(",", "[", "]")}}"""

    /** Respuesta sin datos del contrato (CA2): ceros y listas vacías. */
    fun emptyBody(from: String = "2026-09-26T00:00:00-05:00", to: String = "2026-10-26T00:00:00-05:00") = body(
        from = from,
        to = to,
        hasData = false,
        totals = """{"sessions_count":0,"total_duration_minutes":0,"avg_duration_minutes":0,"distance_meters":null}""",
        bySport = emptyList(),
        byWeek = emptyList(),
        bySource = """{"SPORTAPP":{"sessions_count":0,"total_duration_minutes":0},"STRAVA":{"sessions_count":0,"total_duration_minutes":0}}""",
        metrics = emptyList(),
    )

    fun validationError(field: String, issue: String): String =
        """{"code":"validation_error","message":"mensaje","correlation_id":"8c1f2d3e-0000-4000-8000-000000000033",""" +
            """"details":[{"field":"query.$field","issue":"$issue"}]}"""

    fun snapshot(
        range: DashboardRange = DashboardRange.LAST_30,
        from: Instant = Instant.parse("2026-09-26T05:00:00Z"),
        to: Instant = Instant.parse("2026-10-26T05:00:00Z"),
        hasData: Boolean = true,
        syncedAt: Instant = Instant.parse("2026-10-25T14:00:00Z"),
        sessions: DashboardSourceStatus = DashboardSourceStatus.OK,
        metrics: DashboardSourceStatus = DashboardSourceStatus.OK,
    ) = DashboardSnapshot(
        range = range,
        from = from,
        to = to,
        hasData = hasData,
        totals = if (hasData) DashboardTotals(2, 95, 48, null) else DashboardTotals(0, 0, 0, null),
        bySport = if (hasData) listOf(SportTotal("running", 1, 45, null), SportTotal("cycling", 1, 50, 20_500)) else emptyList(),
        byWeek = if (hasData) listOf(WeekTotal(LocalDate.of(2026, 10, 12), 1, 45), WeekTotal(LocalDate.of(2026, 10, 19), 1, 50)) else emptyList(),
        bySource = SourceTotals(SourceTotal(if (hasData) 2 else 0, if (hasData) 95 else 0), SourceTotal(0, 0)),
        latestMetrics = if (hasData) {
            listOf(LatestMetric("FTP_ESTIMATED", BigDecimal("238"), "W", "FTP_20_MIN_95_V1", 1, Instant.parse("2026-10-20T12:30:00Z"), "estimated_not_diagnostic"))
        } else {
            emptyList()
        },
        sources = listOf(DashboardSourceState("training_sessions", sessions), DashboardSourceState("training_metrics", metrics)),
        syncedAt = syncedAt,
    )
}

/** Caché en memoria con la misma semántica que el DAO de Room (una fila por deportista y opción). */
class InMemoryDashboardDao : TrainingDashboardCacheDao {
    val rows = MutableStateFlow<Map<Pair<String, String>, TrainingDashboardCacheEntity>>(emptyMap())
    var failWrites = false

    override fun observe(owner: String, period: String): Flow<TrainingDashboardCacheEntity?> = rows.map { it[owner to period] }

    override suspend fun replace(entry: TrainingDashboardCacheEntity) {
        check(!failWrites) { "disco lleno" }
        rows.value = rows.value + ((entry.ownerKey to entry.periodKey) to entry)
    }

    override suspend fun delete(owner: String) {
        rows.value = rows.value.filterKeys { it.first != owner }
    }

    override suspend fun deleteAllExcept(owner: String) {
        rows.value = rows.value.filterKeys { it.first == owner }
    }
}
