package com.lfnj.sport_app.testing

import com.lfnj.sport_app.features.events.data.local.EventSuggestionsCacheDao
import com.lfnj.sport_app.features.events.data.local.EventSuggestionsCacheEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Datos sintéticos de HU027 conforme a `EventSuggestionsResponse` de `bff-mobile` 0.15.0. */
object EventSuggestionsFixtures {
    const val MATCH_EVENT_ID = "3f2a1b0c-9d8e-4f7a-8b6c-5d4e3f2a1b0c"
    const val SPORT_EVENT_ID = "8a7b6c5d-4e3f-4a1b-9c0d-1e2f3a4b5c6d"
    const val REGISTERED_EVENT_ID = "0c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f"
    const val REGISTRATION_ID = "5e6f7a8b-9c0d-4e1f-a2b3-c4d5e6f7a8b9"

    fun fee(amount: String = "45000.00", currency: String = "COP") = """{"amount":"$amount","currency":"$currency"}"""

    fun registration(status: String = "REGISTERED", registeredAt: String = "2026-10-21T10:00:00Z") =
        """{"registration_id":"$REGISTRATION_ID","status":"$status","registered_at":"$registeredAt"}"""

    fun item(
        eventId: String = MATCH_EVENT_ID,
        referenceId: String = eventId,
        referenceType: String = "event",
        title: String = "Media maratón de Bogotá",
        summary: String = "Recorrido urbano de 21 km.",
        reasons: List<String> = listOf("SPORT_MATCH", "CITY_MATCH"),
        discipline: String = "Atletismo",
        locationName: String = "Parque Simón Bolívar",
        city: String = "Bogotá",
        countryCode: String = "CO",
        startsAt: String = "2026-11-15T12:00:00Z",
        endsAt: String = "2026-11-15T17:00:00Z",
        timeZone: String = "America/Bogota",
        capacity: Int = 500,
        fee: String? = fee(),
        myRegistration: String? = null,
    ): String =
        """{"reference":{"type":"$referenceType","id":"$referenceId"},"title":"$title","summary":"$summary",""" +
            """"reasons":${reasons.joinToString(",", "[", "]") { "\"$it\"" }},"event_id":"$eventId","discipline":"$discipline",""" +
            """"location":{"name":"$locationName","city":"$city","country_code":"$countryCode"},""" +
            """"starts_at":"$startsAt","ends_at":"$endsAt","time_zone":"$timeZone","capacity":$capacity,""" +
            """"fee":${fee ?: "null"},"my_registration":${myRegistration ?: "null"}}"""

    fun body(
        items: List<String> = listOf(
            item(),
            item(
                eventId = SPORT_EVENT_ID, title = "Gran fondo La Calera", discipline = "Ciclismo", city = "La Calera",
                reasons = listOf("SPORT_MATCH"), startsAt = "2026-11-20T11:00:00Z", endsAt = "2026-11-20T18:00:00Z", fee = null,
            ),
            item(
                eventId = REGISTERED_EVENT_ID, title = "Carrera 10K Usaquén", reasons = listOf("SPORT_MATCH", "CITY_MATCH"),
                startsAt = "2026-11-02T13:00:00Z", endsAt = "2026-11-02T15:00:00Z", fee = fee("0.00"), myRegistration = registration(),
            ),
        ),
        personalized: Boolean = true,
        missingContext: List<String> = emptyList(),
        usersStatus: String = "OK",
        generatedAt: String = "2026-10-25T13:00:00Z",
    ): String =
        """{"items":${items.joinToString(",", "[", "]")},"personalized":$personalized,""" +
            """"missing_context":${missingContext.joinToString(",", "[", "]") { "\"$it\"" }},""" +
            """"generated_at":"$generatedAt","sources":[{"source":"users","status":"$usersStatus"}]}"""

    /** Sin deportes, sin ciudad o sin respuesta de Usuarios: próximos eventos por fecha con `UPCOMING` (CA2). */
    fun notPersonalized(missing: List<String> = listOf("sports", "city"), usersStatus: String = "OK") = body(
        items = listOf(item(reasons = listOf("UPCOMING"))),
        personalized = false,
        missingContext = missing,
        usersStatus = usersStatus,
    )

    fun validationError(): String =
        """{"code":"validation_error","message":"mensaje","correlation_id":"8c1f2d3e-0000-4000-8000-000000000027",""" +
            """"details":[{"field":"query.limit","issue":"out_of_range"}]}"""
}

/** Caché en memoria con la misma semántica que el DAO de Room (una fila por deportista). */
class InMemoryEventSuggestionsDao : EventSuggestionsCacheDao {
    val rows = MutableStateFlow<Map<String, EventSuggestionsCacheEntity>>(emptyMap())
    var failWrites = false

    override fun observe(owner: String): Flow<EventSuggestionsCacheEntity?> = rows.map { it[owner] }

    override suspend fun replace(entry: EventSuggestionsCacheEntity) {
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
