package com.lfnj.sport_app.features.training.dashboard.data.local

import com.lfnj.sport_app.core.security.TokenCipher
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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate

/** Contenido cifrado de [TrainingDashboardCacheEntity.payload]. Instantes en segundos UTC; fechas ISO. */
@Serializable
internal data class DashboardPayload(
    val range: String,
    val from: Long,
    val to: Long,
    @SerialName("has_data") val hasData: Boolean,
    val totals: CachedTotals,
    @SerialName("by_sport") val bySport: List<CachedSport>,
    @SerialName("by_week") val byWeek: List<CachedWeek>,
    @SerialName("by_source") val bySource: Map<String, CachedSourceTotal>,
    @SerialName("latest_metrics") val latestMetrics: List<CachedMetric>,
    val sources: List<CachedSource>,
    @SerialName("last_synced_at") val lastSyncedAt: Long,
)

@Serializable
internal data class CachedTotals(val sessions: Long, val minutes: Long, val average: Long, val meters: Long? = null)

@Serializable
internal data class CachedSport(val sport: String, val sessions: Long, val minutes: Long, val meters: Long? = null)

@Serializable
internal data class CachedWeek(@SerialName("week_start") val weekStart: String, val sessions: Long, val minutes: Long)

@Serializable
internal data class CachedSourceTotal(val sessions: Long, val minutes: Long)

@Serializable
internal data class CachedMetric(
    val indicator: String,
    /** Representación decimal exacta del valor confirmado. */
    val value: String,
    val unit: String,
    @SerialName("method_code") val methodCode: String,
    @SerialName("method_version") val methodVersion: Int,
    @SerialName("measured_at") val measuredAt: Long,
    @SerialName("disclaimer_key") val disclaimerKey: String,
)

@Serializable
internal data class CachedSource(val name: String, val status: String? = null)

/**
 * Cifra y descifra el tablero confirmado con AES-GCM y una clave no exportable de Android Keystore
 * propia de HU033. Una caché que no puede descifrarse se trata como inexistente: nunca se muestran
 * agregados inventados (ADR-043 §2 y §5).
 */
class TrainingDashboardCodec(private val cipher: TokenCipher, private val json: Json) {

    fun encode(snapshot: DashboardSnapshot): ByteArray {
        val totals = snapshot.totals
        val payload = DashboardPayload(
            range = snapshot.range.name,
            from = snapshot.from.epochSecond,
            to = snapshot.to.epochSecond,
            hasData = snapshot.hasData,
            totals = CachedTotals(totals.sessionsCount, totals.totalDurationMinutes, totals.avgDurationMinutes, totals.distanceMeters),
            bySport = snapshot.bySport.map { CachedSport(it.sport, it.sessionsCount, it.totalDurationMinutes, it.distanceMeters) },
            byWeek = snapshot.byWeek.map { CachedWeek(it.weekStart.toString(), it.sessionsCount, it.totalDurationMinutes) },
            bySource = mapOf(
                SOURCE_SPORTAPP to CachedSourceTotal(snapshot.bySource.sportApp.sessionsCount, snapshot.bySource.sportApp.totalDurationMinutes),
                SOURCE_STRAVA to CachedSourceTotal(snapshot.bySource.strava.sessionsCount, snapshot.bySource.strava.totalDurationMinutes),
            ),
            latestMetrics = snapshot.latestMetrics.map {
                CachedMetric(it.indicator, it.value.toPlainString(), it.unit, it.methodCode, it.methodVersion, it.measuredAt.epochSecond, it.disclaimerKey)
            },
            sources = snapshot.sources.map { CachedSource(it.name, it.status?.name) },
            lastSyncedAt = snapshot.syncedAt.epochSecond,
        )
        return cipher.encrypt(json.encodeToString(DashboardPayload.serializer(), payload).toByteArray(Charsets.UTF_8))
    }

    /** `null` si la caché no puede descifrarse o no corresponde al formato esperado. */
    fun decode(entity: TrainingDashboardCacheEntity): DashboardSnapshot? = try {
        val payload = json.decodeFromString(DashboardPayload.serializer(), cipher.decrypt(entity.payload).toString(Charsets.UTF_8))
        DashboardSnapshot(
            range = DashboardRange.valueOf(payload.range),
            from = Instant.ofEpochSecond(payload.from),
            to = Instant.ofEpochSecond(payload.to),
            hasData = payload.hasData,
            totals = payload.totals.let { DashboardTotals(it.sessions, it.minutes, it.average, it.meters) },
            bySport = payload.bySport.map { SportTotal(it.sport, it.sessions, it.minutes, it.meters) },
            byWeek = payload.byWeek.map { WeekTotal(LocalDate.parse(it.weekStart), it.sessions, it.minutes) },
            bySource = SourceTotals(
                sportApp = payload.bySource[SOURCE_SPORTAPP]?.let { SourceTotal(it.sessions, it.minutes) } ?: error("fuente ausente"),
                strava = payload.bySource[SOURCE_STRAVA]?.let { SourceTotal(it.sessions, it.minutes) } ?: error("fuente ausente"),
            ),
            latestMetrics = payload.latestMetrics.map {
                LatestMetric(
                    it.indicator, it.value.toBigDecimal(), it.unit, it.methodCode, it.methodVersion,
                    Instant.ofEpochSecond(it.measuredAt), it.disclaimerKey,
                )
            },
            sources = payload.sources.map { DashboardSourceState(it.name, it.status?.let(DashboardSourceStatus::fromWire)) },
            syncedAt = Instant.ofEpochSecond(payload.lastSyncedAt),
        )
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: SerializationException) {
        null
    } catch (_: DateTimeException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    companion object {
        /** Alias de la clave no exportable de Android Keystore para la caché de HU033. */
        const val KEY_ALIAS = "sportapp.training_dashboard.v1"
        private const val SOURCE_SPORTAPP = "SPORTAPP"
        private const val SOURCE_STRAVA = "STRAVA"
    }
}
