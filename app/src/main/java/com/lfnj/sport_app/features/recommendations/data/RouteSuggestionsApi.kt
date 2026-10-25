package com.lfnj.sport_app.features.recommendations.data

import com.lfnj.sport_app.features.recommendations.model.ContextSourceStatus
import com.lfnj.sport_app.features.recommendations.model.RoutePoint
import com.lfnj.sport_app.features.recommendations.model.RouteSuggestion
import com.lfnj.sport_app.features.recommendations.model.RouteSuggestionsSnapshot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** Rutas sugeridas al deportista en `bff-mobile` 0.15.0 (HU026, ADR-041 §4). */
interface RouteSuggestionsApi {
    @GET("v1/me/suggestions/routes")
    suspend fun routes(
        @Header("Authorization") authorization: String,
        @Query("limit") limit: Int,
    ): Response<RouteSuggestionsResponseDto>

    companion object {
        /** Valor por defecto del contrato (1–50): la pantalla muestra una sola página. */
        const val LIMIT = 20
    }
}

@Serializable
data class RouteSuggestionsResponseDto(
    val items: List<RouteSuggestionItemDto>,
    val personalized: Boolean,
    @SerialName("missing_context") val missingContext: List<String>,
    @SerialName("generated_at") val generatedAt: String,
    val sources: List<SuggestionSourceDto>,
) {
    /** Sin nombres, autores ni coordenadas: un `toString` accidental no expone el contenido. */
    override fun toString(): String = "RouteSuggestionsResponseDto(items=${items.size}, personalized=$personalized)"
}

@Serializable
data class RouteSuggestionItemDto(
    val reference: RouteReferenceDto,
    val title: String,
    /** Obligatorio y anulable en el contrato: la ruta puede no tener descripción. */
    val summary: String?,
    val reasons: List<String>,
    @SerialName("route_id") val routeId: String,
    val sport: String,
    @SerialName("distance_km") val distanceKm: String,
    val difficulty: String,
    val city: String,
    @SerialName("country_code") val countryCode: String,
    @SerialName("start_point") val startPoint: RoutePointDto,
    val waypoints: List<RoutePointDto>,
    val source: String,
    @SerialName("published_at") val publishedAt: String,
    /** Obligatorio y anulable en el contrato: `null` si Usuarios no respondió. */
    @SerialName("author_name") val authorName: String?,
) {
    override fun toString(): String = "RouteSuggestionItemDto(route_id=$routeId)"
}

/** `SuggestionReference` del contrato para rutas (`type` = `route`). */
@Serializable
data class RouteReferenceDto(val type: String, val id: String)

@Serializable
data class RoutePointDto(val lat: Double, val lng: Double) {
    override fun toString(): String = "RoutePointDto"
}

/**
 * Convierte una respuesta `200` en la lista confirmada. Cualquier campo obligatorio mal formado
 * invalida toda la respuesta: nunca se completan datos inventados.
 */
fun RouteSuggestionsResponseDto.toSnapshot(syncedAt: Instant): RouteSuggestionsSnapshot = try {
    RouteSuggestionsSnapshot(
        items = items.map { it.toSuggestion() },
        personalized = personalized,
        missingContext = missingContext,
        generatedAt = instant(generatedAt),
        usersSource = sources.firstOrNull { it.source == RouteSuggestionsSnapshot.SOURCE_USERS }
            ?.let { ContextSourceStatus.fromWire(it.status) },
        syncedAt = syncedAt,
    )
} catch (_: DateTimeParseException) {
    throw InvalidSuggestionsResponse()
}

private fun RouteSuggestionItemDto.toSuggestion(): RouteSuggestion {
    if (reference.type != REFERENCE_TYPE || source != SOURCE_ORGANIZER) throw InvalidSuggestionsResponse()
    if (!DISTANCE_KM.matches(distanceKm)) throw InvalidSuggestionsResponse()
    return RouteSuggestion(
        routeId = routeId,
        title = title,
        summary = summary?.takeIf { it.isNotBlank() },
        sport = sport,
        distanceKm = distanceKm,
        difficulty = difficulty,
        city = city,
        countryCode = countryCode,
        startPoint = startPoint.toPoint(),
        waypoints = waypoints.map { it.toPoint() },
        publishedAt = instant(publishedAt),
        authorName = authorName?.takeIf { it.isNotBlank() },
        reasons = reasons,
    )
}

private fun RoutePointDto.toPoint(): RoutePoint = RoutePoint(lat, lng).takeIf { it.valid } ?: throw InvalidSuggestionsResponse()

private const val REFERENCE_TYPE = "route"

/** Kilómetros con exactamente un decimal, como lo publica el contrato («12.5»). */
private val DISTANCE_KM = Regex("""\d+\.\d""")

/** Todas las rutas son de organizadores (`source: ORGANIZER`, HU026). */
private const val SOURCE_ORGANIZER = "ORGANIZER"

private fun instant(value: String): Instant = OffsetDateTime.parse(value).toInstant()
