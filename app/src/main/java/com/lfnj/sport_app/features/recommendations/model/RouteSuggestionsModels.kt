package com.lfnj.sport_app.features.recommendations.model

import com.lfnj.sport_app.core.session.AccessSnapshot
import com.lfnj.sport_app.core.session.SubscriptionStatus
import java.math.BigDecimal
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Capacidad `FREE` que habilita «Rutas sugeridas» (HU026, ADR-041). */
const val TRAINING_ROUTES_SUGGEST_CAPABILITY = "training.routes.suggest"

/** Guarda visual de la ruta: solo un deportista con suscripción activa y la capacidad vigente. */
object RouteSuggestionsAccess {
    fun canView(access: AccessSnapshot?): Boolean =
        access != null && access.subscriptionStatus == SubscriptionStatus.ACTIVE && TRAINING_ROUTES_SUGGEST_CAPABILITY in access.capabilities
}

/** Partición local por identidad técnica: huella SHA-256 del `user_id` (ADR-041 §5, patrón ADR-031). */
object RouteSuggestionsOwner {
    fun keyOf(userId: String): String =
        MessageDigest.getInstance("SHA-256").digest(userId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/** Motivo de una ruta sugerida (`reasons[]`). Un código que esta versión no conoce se conserva sin interpretarlo. */
enum class RouteSuggestionReason(val wire: String) {
    SPORT_MATCH("SPORT_MATCH"),
    CITY_MATCH("CITY_MATCH");

    companion object {
        fun fromWire(value: String): RouteSuggestionReason? = entries.firstOrNull { it.wire == value }
    }
}

/** Dato del perfil (deportes de HU006, ciudad de HU005) que faltó para personalizar (`missing_context[]`). */
enum class RouteMissingContext(val wire: String) {
    SPORTS("sports"),
    CITY("city");

    companion object {
        fun fromWire(value: String): RouteMissingContext? = entries.firstOrNull { it.wire == value }
    }
}

/** Dificultad declarada por el organizador; un código desconocido se muestra tal cual. */
enum class RouteDifficulty(val wire: String) {
    LOW("LOW"),
    MODERATE("MODERATE"),
    HIGH("HIGH");

    companion object {
        fun fromWire(value: String): RouteDifficulty? = entries.firstOrNull { it.wire == value }
    }
}

/** Coordenada WGS84 de la ruta (`RoutePoint`), en los rangos que valida el dominio (ADR-041 §HU026). */
data class RoutePoint(val lat: Double, val lng: Double) {
    val valid: Boolean get() = lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0
}

/**
 * Ruta sugerida tal como la confirmó el BFF. [sport] y [difficulty] conservan el código del
 * contrato; [distanceKm] es la cadena decimal recibida («12.5»), sin redondeos. [reasons] guarda
 * los códigos recibidos, conocidos o no. [authorName] es `null` si Usuarios no respondió.
 */
data class RouteSuggestion(
    val routeId: String,
    val title: String,
    val summary: String?,
    val sport: String,
    val distanceKm: String,
    val difficulty: String,
    val city: String,
    val countryCode: String,
    val startPoint: RoutePoint,
    val waypoints: List<RoutePoint>,
    val publishedAt: Instant,
    val authorName: String?,
    val reasons: List<String>,
) {
    val knownReasons: List<RouteSuggestionReason> get() = reasons.mapNotNull(RouteSuggestionReason::fromWire)
}

/**
 * Última lista de rutas confirmada por el BFF para un deportista, con su `last_synced_at`
 * ([syncedAt]). Es lo que se guarda cifrado en el dispositivo y lo que la pantalla observa.
 */
data class RouteSuggestionsSnapshot(
    val items: List<RouteSuggestion>,
    val personalized: Boolean,
    /** Códigos de `missing_context[]` tal como llegaron. */
    val missingContext: List<String>,
    val generatedAt: Instant,
    /** Estado de Usuarios en `sources[]`, o `null` si no vino o es desconocido. */
    val usersSource: ContextSourceStatus?,
    val syncedAt: Instant,
) {
    val knownMissingContext: List<RouteMissingContext> get() = missingContext.mapNotNull(RouteMissingContext::fromWire)

    /** Usuarios no respondió: la lista no se pudo personalizar aunque el perfil esté completo. */
    val contextUnavailable: Boolean get() = usersSource == ContextSourceStatus.UNAVAILABLE

    /** Confirmado hace más de 24 horas (ADR-041 §5). */
    fun isStale(now: Instant): Boolean = Duration.between(syncedAt, now) > STALE_AFTER

    fun find(routeId: String): RouteSuggestion? = items.firstOrNull { it.routeId == routeId }

    companion object {
        const val SOURCE_USERS = "users"
        val STALE_AFTER: Duration = Duration.of(24, ChronoUnit.HOURS)
    }
}

/**
 * Apertura del punto de inicio en la aplicación de mapas del teléfono, sin proveedor cartográfico
 * propio (ADR-041 §HU026): `geo:lat,lng?q=lat,lng(nombre)`.
 */
object RouteMaps {
    fun geoUri(point: RoutePoint, label: String): String {
        val coordinates = "${coordinate(point.lat)},${coordinate(point.lng)}"
        return "geo:$coordinates?q=$coordinates(${encode(label)})"
    }

    /** Sin notación científica ni separador local: el intent exige el formato decimal con punto. */
    private fun coordinate(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    /** Codificación de porcentaje UTF-8; el espacio va como `%20`, no como `+`. */
    private fun encode(label: String): String = URLEncoder.encode(label, Charsets.UTF_8.name()).replace("+", "%20")
}

/** Formatos localizados; no alteran el dato fuente. */
object RouteFormat {
    /** «12.5» → «12,5» en español: exactamente un decimal, como lo publica el contrato; otro texto se muestra tal cual. */
    fun distance(distanceKm: String, locale: Locale): String {
        val value = distanceKm.toBigDecimalOrNull() ?: return distanceKm
        return NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }.format(value)
    }

    fun coordinate(value: Double, locale: Locale): String =
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 6
        }.format(value)
}
