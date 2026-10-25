package com.lfnj.sport_app.features.events.model

import com.lfnj.sport_app.core.session.AccessSnapshot
import com.lfnj.sport_app.core.session.SubscriptionStatus
import com.lfnj.sport_app.features.recommendations.model.ContextSourceStatus
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Capacidad `FREE` que habilita «Eventos sugeridos» (HU027, ADR-041). */
const val EVENTS_SUGGEST_CAPABILITY = "events.suggest"

/** Guarda visual de la ruta: solo un deportista con suscripción activa y la capacidad vigente. */
object EventSuggestionsAccess {
    fun canView(access: AccessSnapshot?): Boolean =
        access != null && access.subscriptionStatus == SubscriptionStatus.ACTIVE && EVENTS_SUGGEST_CAPABILITY in access.capabilities
}

/** Partición local por identidad técnica: huella SHA-256 del `user_id` (ADR-041 §5, patrón ADR-031). */
object EventSuggestionsOwner {
    fun keyOf(userId: String): String =
        MessageDigest.getInstance("SHA-256").digest(userId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/** Motivo de un evento sugerido (`reasons[]`). Un código que esta versión no conoce se conserva sin interpretarlo. */
enum class EventSuggestionReason(val wire: String) {
    SPORT_MATCH("SPORT_MATCH"),
    CITY_MATCH("CITY_MATCH"),
    UPCOMING("UPCOMING");

    companion object {
        fun fromWire(value: String): EventSuggestionReason? = entries.firstOrNull { it.wire == value }
    }
}

/** Dato del perfil (deportes de HU006, ciudad de HU005) que faltó para personalizar (`missing_context[]`). */
enum class EventMissingContext(val wire: String) {
    SPORTS("sports"),
    CITY("city");

    companion object {
        fun fromWire(value: String): EventMissingContext? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Evento sugerido tal como lo confirmó el BFF. [capacity] es el cupo declarado, sin verificar
 * disponibilidad (la decide HU041 al inscribir); [fee] es informativa y `null` sin tarifa;
 * [myRegistration] no es nulo si el deportista ya está inscrito. [reasons] guarda los códigos
 * recibidos, conocidos o no.
 */
data class EventSuggestion(
    val eventId: String,
    val title: String,
    val summary: String,
    val discipline: String,
    val location: EventPlace,
    val startsAt: Instant,
    val endsAt: Instant,
    /** Zona IANA declarada por el organizador; las fechas se muestran en la zona del dispositivo. */
    val timeZone: String,
    val capacity: Int,
    val fee: EventPrice?,
    val myRegistration: MyRegistration?,
    val reasons: List<String>,
) {
    val knownReasons: List<EventSuggestionReason> get() = reasons.mapNotNull(EventSuggestionReason::fromWire)

    /** Ya inscrito: se marca y no se invita a inscribirse de nuevo (CA3). */
    val registered: Boolean get() = myRegistration != null

    /** Tarifa `0.00`: gratuito según ADR-040. */
    val free: Boolean get() = fee?.amount?.toBigDecimalOrNull()?.compareTo(BigDecimal.ZERO) == 0
}

/**
 * Última lista de eventos confirmada por el BFF para un deportista, con su `last_synced_at`
 * ([syncedAt]). Es lo que se guarda cifrado en el dispositivo y lo que la pantalla observa.
 */
data class EventSuggestionsSnapshot(
    /** En el orden del BFF: coincidencias de deporte y ciudad primero, luego por fecha (CA1). */
    val items: List<EventSuggestion>,
    val personalized: Boolean,
    /** Códigos de `missing_context[]` tal como llegaron. */
    val missingContext: List<String>,
    val generatedAt: Instant,
    /** Estado de Usuarios en `sources[]`, o `null` si no vino o es desconocido. */
    val usersSource: ContextSourceStatus?,
    val syncedAt: Instant,
) {
    val knownMissingContext: List<EventMissingContext> get() = missingContext.mapNotNull(EventMissingContext::fromWire)

    /** Usuarios no respondió: la lista no se pudo personalizar aunque el perfil esté completo. */
    val contextUnavailable: Boolean get() = usersSource == ContextSourceStatus.UNAVAILABLE

    /** Confirmado hace más de 24 horas (ADR-041 §5). */
    fun isStale(now: Instant): Boolean = Duration.between(syncedAt, now) > STALE_AFTER

    companion object {
        const val SOURCE_USERS = "users"
        val STALE_AFTER: Duration = Duration.of(24, ChronoUnit.HOURS)
    }
}
