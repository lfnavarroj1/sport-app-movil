package com.lfnj.sport_app.features.recommendations.model

import com.lfnj.sport_app.core.session.AccessSnapshot
import com.lfnj.sport_app.core.session.SubscriptionStatus
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Capacidad `FREE` que habilita «Entrenamientos sugeridos» (HU028, ADR-041). */
const val TRAINING_PLANS_SUGGEST_CAPABILITY = "training.plans.suggest"

/** Guarda visual de la ruta: solo un deportista con suscripción activa y la capacidad vigente. */
object PlanSuggestionsAccess {
    fun canView(access: AccessSnapshot?): Boolean =
        access != null && access.subscriptionStatus == SubscriptionStatus.ACTIVE && TRAINING_PLANS_SUGGEST_CAPABILITY in access.capabilities
}

/** Partición local por identidad técnica: huella SHA-256 del `user_id` (ADR-041 §5, patrón ADR-031). */
object PlanSuggestionsOwner {
    fun keyOf(userId: String): String =
        MessageDigest.getInstance("SHA-256").digest(userId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/** Motivo de una sugerencia (`reasons[]`). Un código que esta versión no conoce se conserva sin interpretarlo. */
enum class SuggestionReason(val wire: String) {
    SPORT_MATCH("SPORT_MATCH"),
    GOAL_MATCH("GOAL_MATCH");

    companion object {
        fun fromWire(value: String): SuggestionReason? = entries.firstOrNull { it.wire == value }
    }
}

/** Dato del perfil deportivo (HU006) que faltó para personalizar (`missing_context[]`). */
enum class MissingContext(val wire: String) {
    SPORTS("sports"),
    PRIMARY_GOAL("primary_goal");

    companion object {
        fun fromWire(value: String): MissingContext? = entries.firstOrNull { it.wire == value }
    }
}

/** Estado de la consulta del contexto en Usuarios (`sources[]`); `null` = valor desconocido. */
enum class ContextSourceStatus {
    OK, UNAVAILABLE;

    companion object {
        fun fromWire(value: String): ContextSourceStatus? = entries.firstOrNull { it.name == value }
    }
}

/** Día de una sesión semanal (`day_of_week`): solo los siete códigos del contrato. */
object WeekDays {
    private val BY_WIRE = mapOf(
        "MON" to DayOfWeek.MONDAY,
        "TUE" to DayOfWeek.TUESDAY,
        "WED" to DayOfWeek.WEDNESDAY,
        "THU" to DayOfWeek.THURSDAY,
        "FRI" to DayOfWeek.FRIDAY,
        "SAT" to DayOfWeek.SATURDAY,
        "SUN" to DayOfWeek.SUNDAY,
    )

    fun fromWire(value: String): DayOfWeek? = BY_WIRE[value]

    fun toWire(day: DayOfWeek): String = BY_WIRE.entries.first { it.value == day }.key
}

data class WeeklySession(val day: DayOfWeek, val description: String, val durationMinutes: Int)

/**
 * Plantilla sugerida tal como la confirmó el BFF. [sport], [goal] y [level] conservan el código
 * del contrato; la pantalla los traduce y muestra sin interpretar un código desconocido.
 * [reasons] guarda los códigos recibidos, conocidos o no. [authorName] es `null` si Usuarios no respondió.
 */
data class PlanTemplateSuggestion(
    val templateId: String,
    val professionalId: String,
    val title: String,
    val summary: String,
    val sport: String,
    val goal: String,
    val level: String,
    val durationWeeks: Int,
    val weeklySessions: List<WeeklySession>,
    val publishedAt: Instant,
    val authorName: String?,
    val reasons: List<String>,
) {
    val knownReasons: List<SuggestionReason> get() = reasons.mapNotNull(SuggestionReason::fromWire)
}

/**
 * Última lista confirmada por el BFF para un deportista, con su `last_synced_at` ([syncedAt]).
 * Es lo que se guarda cifrado en el dispositivo y lo que la pantalla observa (ADR-041 §5).
 */
data class PlanSuggestionsSnapshot(
    val items: List<PlanTemplateSuggestion>,
    val personalized: Boolean,
    /** Códigos de `missing_context[]` tal como llegaron. */
    val missingContext: List<String>,
    val generatedAt: Instant,
    /** Estado de Usuarios en `sources[]`, o `null` si no vino o es desconocido. */
    val usersSource: ContextSourceStatus?,
    val syncedAt: Instant,
) {
    val knownMissingContext: List<MissingContext> get() = missingContext.mapNotNull(MissingContext::fromWire)

    /** Usuarios no respondió: la lista no se pudo personalizar aunque el perfil esté completo. */
    val contextUnavailable: Boolean get() = usersSource == ContextSourceStatus.UNAVAILABLE

    /** Confirmado hace más de 24 horas (ADR-041 §5). */
    fun isStale(now: Instant): Boolean = Duration.between(syncedAt, now) > STALE_AFTER

    fun find(templateId: String): PlanTemplateSuggestion? = items.firstOrNull { it.templateId == templateId }

    companion object {
        const val SOURCE_USERS = "users"
        val STALE_AFTER: Duration = Duration.of(24, ChronoUnit.HOURS)
    }
}
