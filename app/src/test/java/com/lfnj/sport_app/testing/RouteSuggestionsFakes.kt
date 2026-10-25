package com.lfnj.sport_app.testing

import com.lfnj.sport_app.features.recommendations.data.local.RouteSuggestionsCacheDao
import com.lfnj.sport_app.features.recommendations.data.local.RouteSuggestionsCacheEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Datos sintéticos de HU026 conforme a `RouteSuggestionsResponse` de `bff-mobile` 0.15.0. */
object RouteSuggestionsFixtures {
    const val CITY_ROUTE_ID = "6b1d2c3e-4f5a-4b6c-8d7e-9f0a1b2c3d4e"
    const val OTHER_CITY_ROUTE_ID = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"

    fun point(lat: Double = 4.6097, lng: Double = -74.0817) = """{"lat":$lat,"lng":$lng}"""

    fun item(
        routeId: String = CITY_ROUTE_ID,
        title: String = "Circuito Parque Simón Bolívar",
        summary: String? = "Recorrido plano alrededor del lago.",
        sport: String = "running",
        distanceKm: String = "5.0",
        difficulty: String = "LOW",
        city: String = "Bogotá",
        countryCode: String = "CO",
        startPoint: String = point(),
        waypoints: List<String> = listOf(point(4.6584, -74.0937), point(4.6601, -74.0952)),
        reasons: List<String> = listOf("SPORT_MATCH", "CITY_MATCH"),
        authorName: String? = "Carlos Ruiz",
        publishedAt: String = "2026-10-20T14:00:00Z",
        referenceType: String = "route",
        source: String = "ORGANIZER",
    ): String {
        val author = authorName?.let { "\"$it\"" } ?: "null"
        val description = summary?.let { "\"$it\"" } ?: "null"
        return """{"reference":{"type":"$referenceType","id":"$routeId"},"title":"$title","summary":$description,""" +
            """"reasons":${reasons.joinToString(",", "[", "]") { "\"$it\"" }},"route_id":"$routeId","sport":"$sport",""" +
            """"distance_km":"$distanceKm","difficulty":"$difficulty","city":"$city","country_code":"$countryCode",""" +
            """"start_point":$startPoint,"waypoints":${waypoints.joinToString(",", "[", "]")},"source":"$source",""" +
            """"published_at":"$publishedAt","author_name":$author}"""
    }

    fun body(
        items: List<String> = listOf(
            item(),
            item(routeId = OTHER_CITY_ROUTE_ID, title = "Ascenso a Patios", distanceKm = "12.5", difficulty = "HIGH", city = "La Calera", reasons = listOf("SPORT_MATCH"), waypoints = emptyList()),
        ),
        personalized: Boolean = true,
        missingContext: List<String> = emptyList(),
        usersStatus: String = "OK",
        generatedAt: String = "2026-10-25T13:00:00Z",
    ): String =
        """{"items":${items.joinToString(",", "[", "]")},"personalized":$personalized,""" +
            """"missing_context":${missingContext.joinToString(",", "[", "]") { "\"$it\"" }},""" +
            """"generated_at":"$generatedAt","sources":[{"source":"users","status":"$usersStatus"}]}"""

    /** Sin deportes registrados o sin respuesta de Usuarios: últimas publicadas, sin motivos (CA3). */
    fun notPersonalized(missing: List<String> = listOf("sports", "city"), usersStatus: String = "OK") = body(
        items = listOf(item(reasons = emptyList(), sport = "cycling")),
        personalized = false,
        missingContext = missing,
        usersStatus = usersStatus,
    )

    fun validationError(): String =
        """{"code":"validation_error","message":"mensaje","correlation_id":"8c1f2d3e-0000-4000-8000-000000000026",""" +
            """"details":[{"field":"query.limit","issue":"out_of_range"}]}"""
}

/** Caché en memoria con la misma semántica que el DAO de Room (una fila por deportista). */
class InMemoryRouteSuggestionsDao : RouteSuggestionsCacheDao {
    val rows = MutableStateFlow<Map<String, RouteSuggestionsCacheEntity>>(emptyMap())
    var failWrites = false

    override fun observe(owner: String): Flow<RouteSuggestionsCacheEntity?> = rows.map { it[owner] }

    override suspend fun replace(entry: RouteSuggestionsCacheEntity) {
        check(!failWrites) { "disco lleno" }
        rows.value = rows.value + (entry.ownerKey to entry)
    }

    override suspend fun delete(owner: String) {
        rows.value = rows.value - owner
    }

    override suspend fun deleteAllExcept(owner: String) {
        rows.value = rows.value.filterKeys { it == owner }
    }
}
