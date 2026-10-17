package com.lfnj.sport_app.testing

import com.lfnj.sport_app.features.calendar.data.local.CalendarCacheDao
import com.lfnj.sport_app.features.calendar.data.local.CalendarCacheEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** Datos sintéticos de HU035 alineados con el ejemplo de ADR-031 «Contrato público». */
object CalendarFixtures {
    const val EVENT_ITEM_ID = "EVENT_PUBLISHED:683f4e38-6bb3-4c47-a6dc-9bb1588c67ef"
    const val SESSION_ITEM_ID = "TRAINING_PLAN_SESSION:0d1c3e4a-8f1b-4a0e-9a6f-2b7c1d9e5f10"

    fun event(
        itemId: String = EVENT_ITEM_ID,
        occursOn: String = "2026-11-15",
        title: String = "Carrera 10K Bogotá",
        startsAt: String = "2026-11-15T12:00:00Z",
        endsAt: String = "2026-11-15T15:00:00Z",
        timeZone: String = "America/Bogota",
    ): String =
        """{"item_id":"$itemId","source":"EVENT_PUBLISHED","occurs_on":"$occursOn","title":"$title",""" +
            """"sport":"Running","starts_at":"$startsAt","ends_at":"$endsAt","time_zone":"$timeZone",""" +
            """"location":{"name":"Parque Metropolitano Simón Bolívar","city":"Bogotá","country_code":"CO"},""" +
            """"reference":{"event_id":"683f4e38-6bb3-4c47-a6dc-9bb1588c67ef"}}"""

    fun session(
        itemId: String = SESSION_ITEM_ID,
        occursOn: String = "2026-11-16",
        title: String = "Base aeróbica noviembre",
    ): String =
        """{"item_id":"$itemId","source":"TRAINING_PLAN_SESSION","occurs_on":"$occursOn","title":"$title",""" +
            """"sport":"running","starts_at":null,"ends_at":null,"time_zone":null,"duration_minutes":45,"rpe":4,""" +
            """"reference":{"plan_id":"f2a9c1d0-3b4e-4c5f-8d6e-7a8b9c0d1e2f","session_id":"0d1c3e4a-8f1b-4a0e-9a6f-2b7c1d9e5f10"}}"""

    fun source(name: String, status: String = "OK", truncated: Boolean = false) =
        """{"name":"$name","status":"$status","truncated":$truncated}"""

    fun body(
        items: List<String> = listOf(event(), session()),
        sources: List<String> = listOf(source("events"), source("training")),
        from: String = "2026-11-01T05:00:00Z",
        to: String = "2026-12-01T04:59:59Z",
    ): String =
        """{"from":"$from","to":"$to","items":${items.joinToString(",", "[", "]")},""" +
            """"sources":${sources.joinToString(",", "[", "]")},"evaluated_at":"2026-10-12T22:00:00Z"}"""

    fun validationError(vararg issues: String): String =
        """{"code":"validation_error","message":"mensaje","correlation_id":"8c1f2d3e-0000-4000-8000-000000000003",""" +
            """"details":[${issues.joinToString(",") { """{"field":"to","issue":"$it"}""" }}]}"""
}

/** Reloj ajustable con zona fija, para fechas del dispositivo y antigüedad de la caché. */
class MutableClock(var instant: Instant, private val zone: ZoneId) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableClock(instant, zone)
    override fun instant(): Instant = instant
}

/** Caché en memoria con la misma semántica que el DAO de Room (una fila por deportista). */
class InMemoryCalendarDao : CalendarCacheDao {
    val rows = MutableStateFlow<Map<String, CalendarCacheEntity>>(emptyMap())
    var failWrites = false

    override fun observe(owner: String): Flow<CalendarCacheEntity?> = rows.map { it[owner] }

    override suspend fun replace(entry: CalendarCacheEntity) {
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
