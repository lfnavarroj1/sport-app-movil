package com.lfnj.sport_app.testing

import com.lfnj.sport_app.features.services.data.local.SupportSuggestionsCacheDao
import com.lfnj.sport_app.features.services.data.local.SupportSuggestionsCacheEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Datos sintéticos de HU029 conforme a `ServiceSuggestionsResponse` de `bff-mobile` 0.18.0. */
object SupportSuggestionsFixtures {
    const val TRANSPORT_SERVICE_ID = "4d3c2b1a-0f9e-4d8c-9b7a-6f5e4d3c2b1a"
    const val COACHING_SERVICE_ID = "7e6d5c4b-3a29-4180-9f6e-5d4c3b2a1908"

    fun item(
        serviceId: String = TRANSPORT_SERVICE_ID,
        title: String = "Transporte al Medio Maratón de Bogotá",
        category: String = "TRANSPORT",
        modality: String = "IN_PERSON",
        amount: String = "80000.00",
        billingUnit: String = "SERVICE",
        startsAt: String = "2026-11-08T10:00:00Z",
        endsAt: String = "2026-11-08T12:00:00Z",
        timeZone: String = "America/Bogota",
        reasons: List<String> = listOf("EVENT_LOGISTICS"),
        authorName: String? = "Carlos Rodríguez",
        referenceType: String = "service",
        referenceId: String = serviceId,
    ): String {
        val author = authorName?.let { "\"$it\"" } ?: "null"
        return """{"reference":{"type":"$referenceType","id":"$referenceId"},"title":"$title",""" +
            """"summary":"Recogida en el punto de encuentro y regreso después de la carrera.",""" +
            """"reasons":${reasons.joinToString(",", "[", "]") { "\"$it\"" }},"service_id":"$serviceId",""" +
            """"category":"$category","modality":"$modality",""" +
            """"fee":{"amount":"$amount","currency":"COP","billing_unit":"$billingUnit"},""" +
            """"next_interval":{"starts_at":"$startsAt","ends_at":"$endsAt","time_zone":"$timeZone"},""" +
            """"author_name":$author}"""
    }

    fun coaching(reasons: List<String> = listOf("UPCOMING_AVAILABILITY")) = item(
        serviceId = COACHING_SERVICE_ID,
        title = "Entrenamiento de técnica de carrera",
        category = "COACHING",
        modality = "VIRTUAL",
        amount = "120000.00",
        billingUnit = "SESSION",
        startsAt = "2026-10-28T13:00:00Z",
        endsAt = "2026-10-28T14:00:00Z",
        reasons = reasons,
        authorName = "Laura Gómez",
    )

    fun body(
        items: List<String> = listOf(item(), coaching()),
        personalized: Boolean = true,
        missingContext: List<String> = emptyList(),
        eventsStatus: String? = "OK",
        usersStatus: String? = "OK",
        generatedAt: String = "2026-10-25T13:00:00Z",
    ): String {
        val sources = listOfNotNull(
            eventsStatus?.let { """{"source":"events","status":"$it"}""" },
            usersStatus?.let { """{"source":"users","status":"$it"}""" },
        )
        return """{"items":${items.joinToString(",", "[", "]")},"personalized":$personalized,""" +
            """"missing_context":${missingContext.joinToString(",", "[", "]") { "\"$it\"" }},""" +
            """"generated_at":"$generatedAt","sources":${sources.joinToString(",", "[", "]")}}"""
    }

    /** Sin inscripciones vigentes: solo entrenamiento con disponibilidad próxima (CA2). */
    fun withoutRegistrations(items: List<String> = listOf(coaching())) =
        body(items = items, personalized = false, missingContext = listOf("event_registrations"))

    /** Eventos no respondió: la lista llega sin el motivo logístico y lo declara (CA5). */
    fun eventsDown() =
        body(items = listOf(coaching()), personalized = false, missingContext = listOf("event_registrations"), eventsStatus = "UNAVAILABLE")

    fun validationError(): String =
        """{"code":"validation_error","message":"mensaje","correlation_id":"8c1f2d3e-0000-4000-8000-000000000029",""" +
            """"details":[{"field":"query.limit","issue":"out_of_range"}]}"""
}

/** Caché en memoria con la misma semántica que el DAO de Room (una fila por deportista). */
class InMemorySupportSuggestionsDao : SupportSuggestionsCacheDao {
    val rows = MutableStateFlow<Map<String, SupportSuggestionsCacheEntity>>(emptyMap())
    var failWrites = false

    override fun observe(owner: String): Flow<SupportSuggestionsCacheEntity?> = rows.map { it[owner] }

    override suspend fun replace(entry: SupportSuggestionsCacheEntity) {
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
