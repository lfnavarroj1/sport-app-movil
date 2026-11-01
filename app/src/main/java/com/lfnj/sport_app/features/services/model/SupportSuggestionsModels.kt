package com.lfnj.sport_app.features.services.model

import com.lfnj.sport_app.core.session.AccessSnapshot
import com.lfnj.sport_app.core.session.SubscriptionStatus
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Capacidad `FREE` que habilita «Acompañamiento y logística» (HU029, ADR-041). */
const val SERVICES_SUPPORT_SUGGEST_CAPABILITY = "services.support.suggest"

/** Guarda visual de la ruta: solo un deportista con suscripción activa y la capacidad vigente. */
object SupportSuggestionsAccess {
    fun canView(access: AccessSnapshot?): Boolean =
        access != null && access.subscriptionStatus == SubscriptionStatus.ACTIVE && SERVICES_SUPPORT_SUGGEST_CAPABILITY in access.capabilities
}

/** Partición local por identidad técnica: huella SHA-256 del `user_id` (ADR-041 §5, patrón ADR-031). */
object SupportSuggestionsOwner {
    fun keyOf(userId: String): String =
        MessageDigest.getInstance("SHA-256").digest(userId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/** Categorías sugeribles en HU029; `SPORTS_MEDICINE` nunca llega a esta lista (ADR-041 HU029 §2). */
enum class SupportCategory(val wire: String) {
    COACHING("COACHING"),
    TRANSPORT("TRANSPORT"),
    ACCOMPANIMENT("ACCOMPANIMENT");

    companion object {
        fun fromWire(value: String): SupportCategory? = entries.firstOrNull { it.wire == value }
    }
}

/** Modalidad del servicio (`IN_PERSON`, `VIRTUAL`, `HYBRID`); un código desconocido se muestra tal cual. */
enum class SupportModality(val wire: String) {
    IN_PERSON("IN_PERSON"),
    VIRTUAL("VIRTUAL"),
    HYBRID("HYBRID");

    companion object {
        fun fromWire(value: String): SupportModality? = entries.firstOrNull { it.wire == value }
    }
}

/** Motivo de una sugerencia (`reasons[]`). Un código que esta versión no conoce se conserva sin interpretarlo. */
enum class SupportReason(val wire: String) {
    /** Transporte o acompañamiento disponible el día de un evento inscrito. */
    EVENT_LOGISTICS("EVENT_LOGISTICS"),

    /** Entrenamiento con disponibilidad en los próximos 31 días. */
    UPCOMING_AVAILABILITY("UPCOMING_AVAILABILITY");

    companion object {
        fun fromWire(value: String): SupportReason? = entries.firstOrNull { it.wire == value }
    }
}

/** Fuente opcional de `sources[]` cuyo estado cambia lo que la pantalla puede afirmar. */
enum class SupportSource(val wire: String) {
    /** Inscripciones del deportista: sin ella no se evalúa el motivo logístico (CA5). */
    EVENTS("events"),

    /** Directorio de Usuarios: sin él no hay nombre del profesional. */
    USERS("users");

    companion object {
        fun fromWire(value: String): SupportSource? = entries.firstOrNull { it.wire == value }
    }
}

/** Estado de una fuente opcional; `null` = valor desconocido. */
enum class SupportSourceStatus {
    OK, UNAVAILABLE;

    companion object {
        fun fromWire(value: String): SupportSourceStatus? = entries.firstOrNull { it.name == value }
    }
}

/** Intervalo que justifica el motivo, `[startsAt, endsAt)` en UTC con la zona IANA del profesional. */
data class SupportInterval(val startsAt: Instant, val endsAt: Instant, val timeZone: ZoneId)

/**
 * Servicio sugerido tal como lo confirmó el BFF. [category] y [modality] conservan el código del
 * contrato; [reasons] guarda los códigos recibidos, conocidos o no. [authorName] es `null` si
 * Usuarios no respondió. [fee] es solo informativa: sugerir no contrata ni reserva.
 */
data class SupportServiceSuggestion(
    val serviceId: String,
    val title: String,
    val summary: String,
    val category: String,
    val modality: String,
    val fee: ServiceFee,
    val nextInterval: SupportInterval,
    val authorName: String?,
    val reasons: List<String>,
) {
    val knownReasons: List<SupportReason> get() = reasons.mapNotNull(SupportReason::fromWire)
}

/**
 * Última lista confirmada por el BFF para un deportista, con su `last_synced_at` ([syncedAt]).
 * Es lo que se guarda cifrado en el dispositivo y lo que la pantalla observa (ADR-041 §5).
 */
data class SupportSuggestionsSnapshot(
    val items: List<SupportServiceSuggestion>,
    val personalized: Boolean,
    /** Códigos de `missing_context[]` tal como llegaron. */
    val missingContext: List<String>,
    val generatedAt: Instant,
    /** Estado de Eventos en `sources[]`, o `null` si no vino o es desconocido. */
    val eventsSource: SupportSourceStatus?,
    /** Estado de Usuarios en `sources[]`, o `null` si no vino o es desconocido. */
    val usersSource: SupportSourceStatus?,
    val syncedAt: Instant,
) {
    /** Eventos no respondió: la lista llegó sin el motivo logístico (CA5, HA011). */
    val eventsUnavailable: Boolean get() = eventsSource == SupportSourceStatus.UNAVAILABLE

    /** Faltaron las inscripciones vigentes (o Eventos no respondió): solo hay entrenamiento próximo. */
    val missingRegistrations: Boolean get() = MISSING_EVENT_REGISTRATIONS in missingContext

    /** Confirmado hace más de 24 horas (ADR-041 §5). */
    fun isStale(now: Instant): Boolean = Duration.between(syncedAt, now) > STALE_AFTER

    fun find(serviceId: String): SupportServiceSuggestion? = items.firstOrNull { it.serviceId == serviceId }

    companion object {
        const val MISSING_EVENT_REGISTRATIONS = "event_registrations"
        val STALE_AFTER: Duration = Duration.of(24, ChronoUnit.HOURS)
    }
}
