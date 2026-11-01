package com.lfnj.sport_app.features.recommendations.data.local

import com.lfnj.sport_app.core.security.TokenCipher
import com.lfnj.sport_app.features.recommendations.model.ContextSourceStatus
import com.lfnj.sport_app.features.recommendations.model.RoutePoint
import com.lfnj.sport_app.features.recommendations.model.RouteSuggestion
import com.lfnj.sport_app.features.recommendations.model.RouteSuggestionsSnapshot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.time.DateTimeException
import java.time.Instant

/** Contenido cifrado de [RouteSuggestionsCacheEntity.payload]. Instantes en segundos UTC. */
@Serializable
internal data class RoutesPayload(
    val items: List<CachedRoute>,
    val personalized: Boolean,
    @SerialName("missing_context") val missingContext: List<String>,
    @SerialName("generated_at") val generatedAt: Long,
    @SerialName("users_source") val usersSource: String? = null,
    @SerialName("last_synced_at") val lastSyncedAt: Long,
)

@Serializable
internal data class CachedRoute(
    @SerialName("route_id") val routeId: String,
    val title: String,
    val summary: String? = null,
    val sport: String,
    @SerialName("distance_km") val distanceKm: String,
    val difficulty: String,
    val city: String,
    @SerialName("country_code") val countryCode: String,
    @SerialName("start_point") val startPoint: CachedPoint,
    val waypoints: List<CachedPoint>,
    @SerialName("published_at") val publishedAt: Long,
    @SerialName("author_name") val authorName: String? = null,
    val reasons: List<String>,
)

@Serializable
internal data class CachedPoint(val lat: Double, val lng: Double)

/**
 * Cifra y descifra la lista confirmada con AES-GCM y una clave no exportable de Android Keystore
 * propia de HU026. Una caché que no puede descifrarse se trata como inexistente: nunca se muestran
 * rutas inventadas (ADR-041 §5).
 */
class RouteSuggestionsCodec(private val cipher: TokenCipher, private val json: Json) {

    fun encode(snapshot: RouteSuggestionsSnapshot): ByteArray {
        val payload = RoutesPayload(
            items = snapshot.items.map(::cached),
            personalized = snapshot.personalized,
            missingContext = snapshot.missingContext,
            generatedAt = snapshot.generatedAt.epochSecond,
            usersSource = snapshot.usersSource?.name,
            lastSyncedAt = snapshot.syncedAt.epochSecond,
        )
        return cipher.encrypt(json.encodeToString(RoutesPayload.serializer(), payload).toByteArray(Charsets.UTF_8))
    }

    /** `null` si la caché no puede descifrarse o no corresponde al formato esperado. */
    fun decode(entity: RouteSuggestionsCacheEntity): RouteSuggestionsSnapshot? = try {
        val payload = json.decodeFromString(RoutesPayload.serializer(), cipher.decrypt(entity.payload).toString(Charsets.UTF_8))
        RouteSuggestionsSnapshot(
            items = payload.items.map { it.toSuggestion() ?: return null },
            personalized = payload.personalized,
            missingContext = payload.missingContext,
            generatedAt = Instant.ofEpochSecond(payload.generatedAt),
            usersSource = payload.usersSource?.let(ContextSourceStatus::fromWire),
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

    private fun cached(item: RouteSuggestion) = CachedRoute(
        routeId = item.routeId,
        title = item.title,
        summary = item.summary,
        sport = item.sport,
        distanceKm = item.distanceKm,
        difficulty = item.difficulty,
        city = item.city,
        countryCode = item.countryCode,
        startPoint = CachedPoint(item.startPoint.lat, item.startPoint.lng),
        waypoints = item.waypoints.map { CachedPoint(it.lat, it.lng) },
        publishedAt = item.publishedAt.epochSecond,
        authorName = item.authorName,
        reasons = item.reasons,
    )

    /** `null` si una coordenada guardada está fuera de rango: no se abre un punto alterado. */
    private fun CachedRoute.toSuggestion(): RouteSuggestion? {
        val start = startPoint.toPoint() ?: return null
        val points = waypoints.map { it.toPoint() ?: return null }
        return RouteSuggestion(
            routeId = routeId,
            title = title,
            summary = summary,
            sport = sport,
            distanceKm = distanceKm,
            difficulty = difficulty,
            city = city,
            countryCode = countryCode,
            startPoint = start,
            waypoints = points,
            publishedAt = Instant.ofEpochSecond(publishedAt),
            authorName = authorName,
            reasons = reasons,
        )
    }

    private fun CachedPoint.toPoint(): RoutePoint? = RoutePoint(lat, lng).takeIf { it.valid }

    companion object {
        /** Alias de la clave no exportable de Android Keystore para la caché de HU026. */
        const val KEY_ALIAS = "sportapp.route_suggestions.v1"
    }
}
