package com.lfnj.sport_app.testing

import com.lfnj.sport_app.features.events.data.local.EventRegistrationsCacheDao
import com.lfnj.sport_app.features.events.data.local.EventRegistrationsCacheEntity
import com.lfnj.sport_app.features.events.model.EventDetail
import com.lfnj.sport_app.features.events.model.EventPlace
import com.lfnj.sport_app.features.events.model.EventPrice
import com.lfnj.sport_app.features.events.model.EventRegistration
import com.lfnj.sport_app.features.events.model.MyRegistration
import com.lfnj.sport_app.features.events.model.RegisteredEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import okhttp3.mockwebserver.MockResponse
import java.time.Instant

/** Datos sintéticos de HU041 alineados con los ejemplos de ADR-032 «Contrato público». */
object EventRegistrationFixtures {
    const val EVENT_ID = "683f4e38-6bb3-4c47-a6dc-9bb1588c67ef"
    const val OTHER_EVENT_ID = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
    const val REGISTRATION_ID = "6d2f0c8e-4a1b-4f3e-9c7d-2b8a5e1f0d43"
    const val OTHER_REGISTRATION_ID = "9e8d7c6b-5a49-4382-9170-6f5e4d3c2b1a"

    private const val LOCATION = """{"name":"Parque Metropolitano Simón Bolívar","city":"Bogotá","country_code":"CO"}"""

    fun detailBody(
        eventId: String = EVENT_ID,
        startsAt: String = "2026-11-15T12:00:00Z",
        fee: String = "null",
        myRegistration: String = "null",
        status: String = "PUBLISHED",
    ): String =
        """{"event_id":"$eventId","name":"Carrera 10K Bogotá","description":"Recorrido urbano de 10 km.",""" +
            """"discipline":"Running","location":$LOCATION,"starts_at":"$startsAt","ends_at":"2026-11-15T15:00:00Z",""" +
            """"time_zone":"America/Bogota","capacity":500,"status":"$status","version":2,"fee":$fee,""" +
            """"my_registration":$myRegistration}"""

    fun myRegistration(registrationId: String = REGISTRATION_ID) =
        """{"registration_id":"$registrationId","status":"REGISTERED","registered_at":"2026-10-12T22:30:00Z"}"""

    fun registration(
        registrationId: String = REGISTRATION_ID,
        eventId: String = EVENT_ID,
        name: String = "Carrera 10K Bogotá",
        startsAt: String = "2026-11-15T12:00:00Z",
        appliedFee: String = "null",
    ): String =
        """{"registration_id":"$registrationId","event_id":"$eventId","status":"REGISTERED",""" +
            """"registered_at":"2026-10-12T22:30:00Z","applied_fee":$appliedFee,"version":1,""" +
            """"event":{"name":"$name","starts_at":"$startsAt","ends_at":"2026-11-15T15:00:00Z",""" +
            """"time_zone":"America/Bogota","location":$LOCATION}}"""

    fun page(items: List<String>, nextCursor: String? = null): String =
        """{"items":${items.joinToString(",", "[", "]")},"next_cursor":${nextCursor?.let { "\"$it\"" } ?: "null"}}"""

    fun error(status: Int, code: String, details: String = "[]"): MockResponse = MockResponse()
        .setResponseCode(status)
        .setBody("""{"code":"$code","message":"mensaje","correlation_id":"8c1f2d3e-0000-4000-8000-000000000041","details":$details}""")

    fun exists(registrationId: String = REGISTRATION_ID): MockResponse =
        error(409, "event_registration_exists", """[{"field":"registration_id","issue":"$registrationId"}]""")

    fun detail(
        startsAt: Instant = Instant.parse("2026-11-15T12:00:00Z"),
        myRegistration: MyRegistration? = null,
        fee: EventPrice? = null,
    ) = EventDetail(
        eventId = EVENT_ID,
        name = "Carrera 10K Bogotá",
        description = "Recorrido urbano de 10 km.",
        discipline = "Running",
        location = EventPlace("Parque Metropolitano Simón Bolívar", "Bogotá", "CO"),
        startsAt = startsAt,
        endsAt = startsAt.plusSeconds(3 * 3600),
        timeZone = "America/Bogota",
        capacity = 500,
        version = 2,
        fee = fee,
        myRegistration = myRegistration,
    )

    fun confirmed(
        registrationId: String = REGISTRATION_ID,
        eventId: String = EVENT_ID,
        startsAt: Instant = Instant.parse("2026-11-15T12:00:00Z"),
        name: String = "Carrera 10K Bogotá",
    ) = EventRegistration(
        registrationId = registrationId,
        eventId = eventId,
        registeredAt = Instant.parse("2026-10-12T22:30:00Z"),
        appliedFee = null,
        version = 1,
        event = RegisteredEvent(name, startsAt, startsAt.plusSeconds(3 * 3600), "America/Bogota", EventPlace("Parque", "Bogotá", "CO")),
    )
}

/** Caché en memoria con la misma semántica que el DAO de Room (una fila por deportista). */
class InMemoryEventRegistrationsDao : EventRegistrationsCacheDao {
    val rows = MutableStateFlow<Map<String, EventRegistrationsCacheEntity>>(emptyMap())
    var failWrites = false

    override fun observe(owner: String): Flow<EventRegistrationsCacheEntity?> = rows.map { it[owner] }

    override suspend fun read(owner: String): EventRegistrationsCacheEntity? = rows.value[owner]

    override suspend fun replace(entry: EventRegistrationsCacheEntity) {
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
