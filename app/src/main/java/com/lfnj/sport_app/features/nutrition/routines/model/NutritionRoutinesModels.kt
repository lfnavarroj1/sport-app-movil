package com.lfnj.sport_app.features.nutrition.routines.model

import com.lfnj.sport_app.core.session.AccessSnapshot
import com.lfnj.sport_app.core.session.SubscriptionStatus
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Capacidad `FREE` que habilita «Rutinas de alimentación» (HU030, ADR-041). */
const val NUTRITION_ROUTINES_SUGGEST_CAPABILITY = "nutrition.routines.suggest"

/** Guarda visual de la ruta: solo un deportista con suscripción activa y la capacidad vigente. */
object NutritionRoutinesAccess {
    fun canView(access: AccessSnapshot?): Boolean =
        access != null && access.subscriptionStatus == SubscriptionStatus.ACTIVE && NUTRITION_ROUTINES_SUGGEST_CAPABILITY in access.capabilities
}

/** Partición local por identidad técnica: huella SHA-256 del `user_id` (ADR-041 §5, patrón ADR-031). */
object NutritionRoutinesOwner {
    fun keyOf(userId: String): String =
        MessageDigest.getInstance("SHA-256").digest(userId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/** Motivo de una sugerencia (`reasons[]`). Un código que esta versión no conoce se conserva sin interpretarlo. */
enum class RoutineReason(val wire: String) {
    RESTRICTION_COMPATIBLE("RESTRICTION_COMPATIBLE"),
    GOAL_MATCH("GOAL_MATCH"),
    SPORT_MATCH("SPORT_MATCH");

    companion object {
        fun fromWire(value: String): RoutineReason? = entries.firstOrNull { it.wire == value }
    }
}

/** Dato del perfil que faltó para personalizar (`missing_context[]`). */
enum class RoutineMissingContext(val wire: String) {
    NUTRITION_HABITS("nutrition_habits"),
    SPORTS("sports"),
    PRIMARY_GOAL("primary_goal");

    companion object {
        fun fromWire(value: String): RoutineMissingContext? = entries.firstOrNull { it.wire == value }
    }
}

/** Aviso fijo que el cliente localiza (`notices[]`). */
enum class RoutineNotice(val wire: String) {
    RESTRICTIONS_NOT_VERIFIED("RESTRICTIONS_NOT_VERIFIED"),
    NOT_MEDICAL_ADVICE("NOT_MEDICAL_ADVICE");

    companion object {
        fun fromWire(value: String): RoutineNotice? = entries.firstOrNull { it.wire == value }
    }
}

/** Estado de la consulta en Usuarios (`sources[]`); `null` = valor desconocido. */
enum class RoutineSourceStatus {
    OK, UNAVAILABLE;

    companion object {
        fun fromWire(value: String): RoutineSourceStatus? = entries.firstOrNull { it.name == value }
    }
}

/** Alimento de una comida tal como lo publicó el profesional: código del catálogo de HU043 y porciones. */
data class RoutineFood(val foodCode: String, val portions: Int)

/** Comida del día tipo. [meal] conserva el código del contrato (`BREAKFAST`…`HYDRATION`). */
data class RoutineMeal(val meal: String, val items: List<RoutineFood>)

/**
 * Rutina sugerida tal como la confirmó el BFF. [goal], [sport], [dietaryPattern] y
 * [containsAllergens] conservan el código del contrato; la pantalla los traduce y muestra sin
 * interpretar un código desconocido. [authorName] es `null` si Usuarios no respondió.
 */
data class NutritionRoutineSuggestion(
    val templateId: String,
    val professionalId: String,
    val title: String,
    val summary: String,
    val goal: String,
    val sport: String?,
    val dietaryPattern: String,
    val containsAllergens: List<String>,
    val durationDays: Int,
    val dailyMeals: List<RoutineMeal>,
    val publishedAt: Instant,
    val authorName: String?,
    val reasons: List<String>,
) {
    val knownReasons: List<RoutineReason> get() = reasons.mapNotNull(RoutineReason::fromWire)
}

/**
 * Última lista confirmada por el BFF para un deportista, con su `last_synced_at` ([syncedAt]).
 * Es lo que se guarda cifrado en el dispositivo y lo que la pantalla observa (ADR-041 §5).
 */
data class NutritionRoutinesSnapshot(
    val items: List<NutritionRoutineSuggestion>,
    val personalized: Boolean,
    /** Códigos de `missing_context[]` tal como llegaron. */
    val missingContext: List<String>,
    /** Códigos de `notices[]` tal como llegaron. */
    val notices: List<String>,
    val generatedAt: Instant,
    /** Estado de Usuarios en `sources[]`, o `null` si no vino o es desconocido. */
    val usersSource: RoutineSourceStatus?,
    val syncedAt: Instant,
) {
    val knownMissingContext: List<RoutineMissingContext> get() = missingContext.mapNotNull(RoutineMissingContext::fromWire)

    /**
     * No se comprobaron alergias ni patrón alimenticio (CA3): lo declara el aviso del BFF y, por
     * prudencia, también toda lista no personalizada; nunca se presenta como verificada.
     */
    val restrictionsNotVerified: Boolean
        get() = !personalized || RoutineNotice.RESTRICTIONS_NOT_VERIFIED.wire in notices

    /** Usuarios no respondió: la lista no se pudo personalizar aunque el perfil esté completo. */
    val contextUnavailable: Boolean get() = usersSource == RoutineSourceStatus.UNAVAILABLE

    /** Confirmado hace más de 24 horas (ADR-041 §5). */
    fun isStale(now: Instant): Boolean = Duration.between(syncedAt, now) > STALE_AFTER

    fun find(templateId: String): NutritionRoutineSuggestion? = items.firstOrNull { it.templateId == templateId }

    companion object {
        const val SOURCE_USERS = "users"
        val STALE_AFTER: Duration = Duration.of(24, ChronoUnit.HOURS)
    }
}
