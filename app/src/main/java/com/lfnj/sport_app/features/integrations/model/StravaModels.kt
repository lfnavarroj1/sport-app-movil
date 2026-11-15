package com.lfnj.sport_app.features.integrations.model

import com.lfnj.sport_app.core.session.AccessSnapshot
import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.SubscriptionStatus
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.time.Duration
import java.time.Instant

/** Capacidad del plan FREE que habilita «Integrar con Strava» (`/integraciones/strava`, opción 160, ADR-042 §13). */
const val STRAVA_CAPABILITY = "integrations.strava.connect"

/** Guarda visual de la ruta; la autorización la repiten el BFF y Terceros (ADR-042 §14). */
object StravaAccess {
    fun canUse(access: AccessSnapshot?): Boolean =
        access != null && access.actorType == ActorType.ATHLETE &&
            access.subscriptionStatus == SubscriptionStatus.ACTIVE && STRAVA_CAPABILITY in access.capabilities
}

/** `StravaIntegrationResponse.status` (`bff-mobile` 0.22.0). */
enum class StravaLinkStatus {
    NOT_LINKED,
    ACTIVE,
    REVOKED,
    EXPIRED;

    companion object {
        fun fromWire(value: String): StravaLinkStatus? = entries.firstOrNull { it.name == value }
    }
}

/** Estado de una corrida de sincronización (`StravaSyncSummary.status` y `StravaSyncResponse.status`). */
enum class StravaSyncStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    RATE_LIMITED;

    /** La corrida sigue pendiente en el worker de Terceros. */
    val inProgress: Boolean get() = this == QUEUED || this == RUNNING

    companion object {
        fun fromWire(value: String): StravaSyncStatus? = entries.firstOrNull { it.name == value }
    }
}

/** `StravaSyncSummary.error_code` del contrato: causa recuperable de una corrida fallida (CA6, CA7). */
enum class StravaSyncError(val wire: String) {
    RATE_LIMITED("rate_limited"),
    PROVIDER_UNAVAILABLE("provider_unavailable"),
    CIRCUIT_OPEN("circuit_open"),
    TOKEN_EXPIRED("token_expired"),
    INSUFFICIENT_SCOPE("insufficient_scope"),
    PROVIDER_REJECTED("provider_rejected"),
    INVALID_RESPONSE("invalid_response"),
    INVALID_ACTIVITY("invalid_activity"),
    INTEGRATION_REVOKED("integration_revoked");

    /** La corrección exige volver a autorizar SportApp en Strava. */
    val requiresRelink: Boolean get() = this == TOKEN_EXPIRED || this == INSUFFICIENT_SCOPE || this == INTEGRATION_REVOKED

    companion object {
        fun fromWire(value: String): StravaSyncError? = entries.firstOrNull { it.wire == value }
    }
}

/** Última corrida de sincronización confirmada por el BFF. */
data class StravaLastSync(
    val status: StravaSyncStatus,
    val requestedAt: Instant,
    /** `null` mientras la corrida no termina. */
    val finishedAt: Instant?,
    val importedCount: Long,
    /** `null` si la corrida no falló. */
    val error: StravaSyncError?,
)

/**
 * Estado de vinculación confirmado por `GET /v1/me/integrations/strava`. Nunca contiene tokens ni
 * el identificador del atleta en Strava (minimización: no se muestra ni se guarda).
 */
data class StravaIntegration(
    val status: StravaLinkStatus,
    val linkedAt: Instant?,
    val scopes: List<String>,
    /** `null` si nunca se sincronizó: «vinculado» no significa «sincronizado». */
    val lastSync: StravaLastSync?,
    /** Instante local en que el BFF confirmó este estado. */
    val syncedAt: Instant,
) {
    /** El estado confirmado tiene más de 24 horas (ADR-042 §13: caché `STALE` a las 24 h). */
    fun isStale(now: Instant): Boolean = Duration.between(syncedAt, now) > STALE_AFTER

    /** Hay una corrida `QUEUED`/`RUNNING` del deportista. */
    val syncInProgress: Boolean get() = lastSync?.status?.inProgress == true

    companion object {
        val STALE_AFTER: Duration = Duration.ofHours(24)
    }
}

/**
 * Resultado del retorno desde Strava por el enlace profundo `sportapp://integrations/strava?result=…`
 * que emite el callback público de `bff-mobile` (ADR-042 §6). Es solo una indicación: la app confirma
 * siempre el estado con el BFF antes de mostrarlo como vinculado.
 */
enum class StravaLinkResult {
    LINKED,
    DENIED,
    INVALID_STATE,
    ERROR;

    companion object {
        const val SCHEME = "sportapp"
        const val HOST = "integrations"
        const val PATH = "/strava"

        /**
         * Interpreta un enlace profundo. Devuelve `null` si no es el retorno de Strava; un retorno con un
         * `result` ausente o desconocido se trata como [ERROR] (sin detalles). Nunca conserva otros parámetros.
         */
        fun fromDeepLink(link: String?): StravaLinkResult? {
            if (link == null) return null
            val uri = try {
                URI(link)
            } catch (_: URISyntaxException) {
                return null
            }
            if (!SCHEME.equals(uri.scheme, ignoreCase = true) || !HOST.equals(uri.host, ignoreCase = true) || uri.path != PATH) return null
            val result = uri.rawQuery.orEmpty().split('&')
                .map { it.split('=', limit = 2) }
                .firstOrNull { it.size == 2 && it[0] == "result" }
                ?.let { decode(it[1]) }
            return entries.firstOrNull { it.name == result } ?: ERROR
        }

        private fun decode(value: String): String? = try {
            URLDecoder.decode(value, Charsets.UTF_8.name())
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** Scopes que SportApp solicita a Strava (ADR-042 §5): `read,activity:read`. */
object StravaScopes {
    const val READ = "read"
    const val ACTIVITY_READ = "activity:read"
}
