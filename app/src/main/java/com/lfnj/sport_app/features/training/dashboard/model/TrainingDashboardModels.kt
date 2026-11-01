package com.lfnj.sport_app.features.training.dashboard.model

import com.lfnj.sport_app.core.session.AccessSnapshot
import com.lfnj.sport_app.core.session.SubscriptionStatus
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Capacidad del plan que habilita el tablero (`/entrenamientos/tablero`, ADR-043 §5). */
const val TRAINING_DASHBOARD_CAPABILITY = "training.dashboard.view"

/** Guarda visual de la ruta; la autorización la repiten el BFF y el servicio (ADR-043 §1). */
object TrainingDashboardAccess {
    fun canView(access: AccessSnapshot?): Boolean =
        access != null && access.subscriptionStatus == SubscriptionStatus.ACTIVE && TRAINING_DASHBOARD_CAPABILITY in access.capabilities
}

/** Periodos del selector (ADR-043 §5): 7, 30 o 90 días, o un rango de fechas personalizado. */
enum class DashboardRange(val days: Long?) {
    LAST_7(7),
    LAST_30(30),
    LAST_90(90),
    CUSTOM(null);

    /** Clave de la caché: una fila por deportista y opción del selector. */
    val cacheKey: String get() = name

    companion object {
        /** Sin elección del deportista se consultan los últimos 30 días, como el valor por defecto del BFF. */
        val DEFAULT = LAST_30
    }
}

/** Error de experiencia del rango personalizado; el BFF conserva la validación autoritativa (`422`). */
enum class CustomRangeError { END_BEFORE_START, TOO_LONG }

/**
 * Periodo consultado `[from, to)` en la zona del dispositivo (CA3): días completos desde la medianoche
 * del primer día hasta la medianoche siguiente al último. Los extremos viajan con su desfase.
 */
data class DashboardPeriod(val range: DashboardRange, val from: OffsetDateTime, val to: OffsetDateTime) {
    val fromParam: String get() = from.format(WIRE)
    val toParam: String get() = to.format(WIRE)

    /** El periodo cubre exactamente esos instantes (comparación sin depender del desfase). */
    fun sameInstants(fromInstant: Instant, toInstant: Instant): Boolean =
        from.toInstant() == fromInstant && to.toInstant() == toInstant

    companion object {
        /** Máximo del contrato (ADR-043 §1). */
        const val MAX_DAYS = 366L

        /** Siempre con segundos y desfase (`Z` en UTC), p. ej. `2026-10-01T00:00:00-05:00`. */
        private val WIRE: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)

        /** Los últimos [DashboardRange.days] días, incluido [today]. */
        fun preset(range: DashboardRange, today: LocalDate, zone: ZoneId): DashboardPeriod {
            val days = requireNotNull(range.days) { "$range no es un periodo predefinido" }
            return days(range, today.minusDays(days - 1), today, zone)
        }

        /** Rango personalizado con ambos extremos incluidos; `null` si no es válido (ver [validateCustom]). */
        fun custom(start: LocalDate, end: LocalDate, zone: ZoneId): DashboardPeriod? =
            if (validateCustom(start, end) == null) days(DashboardRange.CUSTOM, start, end, zone) else null

        fun validateCustom(start: LocalDate, end: LocalDate): CustomRangeError? = when {
            end.isBefore(start) -> CustomRangeError.END_BEFORE_START
            ChronoUnit.DAYS.between(start, end) + 1 > MAX_DAYS -> CustomRangeError.TOO_LONG
            else -> null
        }

        private fun days(range: DashboardRange, first: LocalDate, last: LocalDate, zone: ZoneId) = DashboardPeriod(
            range = range,
            from = first.atStartOfDay(zone).toOffsetDateTime(),
            to = last.plusDays(1).atStartOfDay(zone).toOffsetDateTime(),
        )
    }
}

/** Totales del periodo (`totals`). [distanceMeters] es `null` si ninguna sesión registró distancia. */
data class DashboardTotals(
    val sessionsCount: Long,
    val totalDurationMinutes: Long,
    val avgDurationMinutes: Long,
    val distanceMeters: Long?,
)

/** Distribución por deporte (`by_sport[]`); [sport] conserva el código del contrato. */
data class SportTotal(val sport: String, val sessionsCount: Long, val totalDurationMinutes: Long, val distanceMeters: Long?)

/** Distribución por semana ISO (`by_week[]`); [weekStart] es el lunes en la zona solicitada. */
data class WeekTotal(val weekStart: LocalDate, val sessionsCount: Long, val totalDurationMinutes: Long)

data class SourceTotal(val sessionsCount: Long, val totalDurationMinutes: Long)

/** Distribución por origen de las sesiones (`by_source`): registradas en SportApp o importadas de Strava. */
data class SourceTotals(val sportApp: SourceTotal, val strava: SourceTotal)

/** Último resultado de un indicador en el periodo (ADR-030): siempre una estimación, nunca un diagnóstico. */
data class LatestMetric(
    val indicator: String,
    val value: BigDecimal,
    val unit: String,
    val methodCode: String,
    val methodVersion: Int,
    val measuredAt: Instant,
    val disclaimerKey: String,
) {
    override fun toString(): String = "LatestMetric(indicator=$indicator, methodCode=$methodCode)"
}

enum class DashboardSourceStatus {
    OK, UNAVAILABLE;

    companion object {
        fun fromWire(value: String): DashboardSourceStatus? = entries.firstOrNull { it.name == value }
    }
}

/** Estado de una consulta interna del tablero (`sources[]`). [status] `null` = valor desconocido. */
data class DashboardSourceState(val name: String, val status: DashboardSourceStatus?) {
    val complete: Boolean get() = status == DashboardSourceStatus.OK
}

/**
 * Último tablero confirmado por el BFF para un deportista y una opción del selector, con su
 * `last_synced_at` ([syncedAt]). Es lo que se guarda cifrado y lo que la pantalla observa.
 */
data class DashboardSnapshot(
    val range: DashboardRange,
    val from: Instant,
    val to: Instant,
    val hasData: Boolean,
    val totals: DashboardTotals,
    val bySport: List<SportTotal>,
    val byWeek: List<WeekTotal>,
    val bySource: SourceTotals,
    val latestMetrics: List<LatestMetric>,
    val sources: List<DashboardSourceState>,
    val syncedAt: Instant,
) {
    /**
     * Consultas internas que no respondieron. Una esperada ausente también cuenta: nunca se afirma
     * que no hay datos sin la confirmación de su fuente.
     */
    val incompleteSources: List<String>
        get() = sources.filterNot { it.complete }.map { it.name } +
            EXPECTED_SOURCES.filter { expected -> sources.none { it.name == expected } }

    val complete: Boolean get() = incompleteSources.isEmpty()

    /** Sin datos y con todas las fuentes confirmadas: estado vacío honesto, sin gráficos (CA2). */
    val confirmedEmpty: Boolean get() = !hasData && complete

    /** Confirmado hace más de 24 horas (ADR-043 §5). */
    fun isStale(now: Instant): Boolean = Duration.between(syncedAt, now) > STALE_AFTER

    /** Último día incluido en el periodo `[from, to)`, en [zone]. */
    fun lastDay(zone: ZoneId): LocalDate = to.minusNanos(1).atZone(zone).toLocalDate()

    fun firstDay(zone: ZoneId): LocalDate = from.atZone(zone).toLocalDate()

    override fun toString(): String = "DashboardSnapshot(range=$range, hasData=$hasData)"

    companion object {
        const val SOURCE_SESSIONS = "training_sessions"
        const val SOURCE_METRICS = "training_metrics"
        val EXPECTED_SOURCES = listOf(SOURCE_SESSIONS, SOURCE_METRICS)
        val STALE_AFTER: Duration = Duration.of(24, ChronoUnit.HOURS)
    }
}

/** Barra de un gráfico: su proporción respecto del máximo de la serie, sin alterar el valor. */
data class DashboardBar<T>(val item: T, val value: Long, val fraction: Float)

/**
 * Agregación visual de las series del tablero: solo calcula la longitud relativa de cada barra.
 * Los valores se presentan tal como llegaron; una serie vacía no produce barras (CA2).
 */
object DashboardBars {
    fun <T> of(items: List<T>, value: (T) -> Long): List<DashboardBar<T>> {
        val max = items.maxOfOrNull(value)?.takeIf { it > 0 } ?: 0L
        return items.map { item ->
            val v = value(item)
            DashboardBar(item, v, if (max == 0L) 0f else (v.coerceAtLeast(0).toFloat() / max))
        }
    }

    /** Semanas en orden cronológico, como las devuelve el contrato pero sin depender de ello. */
    fun weeks(weeks: List<WeekTotal>): List<DashboardBar<WeekTotal>> = of(weeks.sortedBy { it.weekStart }) { it.totalDurationMinutes }

    /** Deportes de mayor a menor duración; ante empate se conserva el orden del BFF. */
    fun sports(sports: List<SportTotal>): List<DashboardBar<SportTotal>> =
        of(sports.sortedByDescending { it.totalDurationMinutes }) { it.totalDurationMinutes }
}
