package com.lfnj.sport_app.testing

import com.lfnj.sport_app.features.services.data.local.VirtualSessionsAppointmentsEntity
import com.lfnj.sport_app.features.services.data.local.VirtualSessionsCacheDao
import com.lfnj.sport_app.features.services.data.local.VirtualSessionsCatalogEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Datos sintéticos de HU040 conforme a `bff-mobile` 0.18.0 (`ServiceAvailabilityResponse`, `AppointmentResponse`). */
object VirtualSessionsFixtures {
    const val SERVICE_ID = "3f2a1b0c-9d8e-4f7a-8b6c-5d4e3f2a1b0c"
    const val OTHER_SERVICE_ID = "4a3b2c1d-0e9f-4a8b-9c7d-6e5f4a3b2c1d"
    const val PROVIDER_ID = "9e8d7c6b-5a4f-4e3d-8c2b-1a0f9e8d7c6b"
    const val SLOT_ID = "11111111-2222-4333-8444-555555555555"
    const val LATER_SLOT_ID = "66666666-7777-4888-9999-aaaaaaaaaaaa"
    const val APPOINTMENT_ID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    const val ATHLETE_ID = TrainingFixtures.USER_ID
    const val CORRELATION_ID = "8c1f2d3e-0000-4000-8000-000000000040"

    fun interval(
        id: String = SLOT_ID,
        startsAt: String = "2026-10-28T15:00:00Z",
        endsAt: String = "2026-10-28T16:00:00Z",
        timeZone: String = "America/Bogota",
        capacity: Int = 1,
    ) = """{"availability_id":"$id","starts_at":"$startsAt","ends_at":"$endsAt","time_zone":"$timeZone","capacity":$capacity}"""

    fun service(
        id: String = SERVICE_ID,
        name: String = "Asesoría de ritmo para 10K",
        category: String = "COACHING",
        modality: String = "VIRTUAL",
        intervals: List<String> = listOf(interval(), interval(LATER_SLOT_ID, "2026-10-30T13:00:00Z", "2026-10-30T14:00:00Z", "Europe/Madrid", 3)),
        amount: String = "120000.00",
    ) = """{"service_id":"$id","provider_id":"$PROVIDER_ID","name":"$name","description":"Revisión de objetivos y plan semanal.",""" +
        """"category":"$category","modality":"$modality","price":{"amount":"$amount","currency":"COP","billing_unit":"SESSION"},""" +
        """"availability":${intervals.joinToString(",", "[", "]")},"available":${intervals.isNotEmpty()}}"""

    fun catalog(services: List<String> = listOf(service()), nextCursor: String? = null): String {
        val cursor = nextCursor?.let { "\"$it\"" } ?: "null"
        return """{"items":${services.joinToString(",", "[", "]")},"next_cursor":$cursor,"restricted_categories":["SPORTS_MEDICINE"]}"""
    }

    fun appointment(
        id: String = APPOINTMENT_ID,
        status: String = "BOOKED",
        availabilityId: String = SLOT_ID,
        startsAt: String = "2026-10-28T15:00:00Z",
        endsAt: String = "2026-10-28T16:00:00Z",
        timeZone: String = "America/Bogota",
        cancelledAt: String? = null,
        version: Int = 1,
    ): String {
        val cancelled = cancelledAt?.let { "\"$it\"" } ?: "null"
        return """{"appointment_id":"$id","athlete_id":"$ATHLETE_ID","provider_id":"$PROVIDER_ID","service_id":"$SERVICE_ID",""" +
            """"availability_id":"$availabilityId","service":{"name":"Asesoría de ritmo para 10K","category":"COACHING","modality":"VIRTUAL",""" +
            """"fee":{"amount":"120000.00","currency":"COP","billing_unit":"SESSION"}},"starts_at":"$startsAt","ends_at":"$endsAt",""" +
            """"time_zone":"$timeZone","status":"$status","meeting_url":null,"booked_at":"2026-10-25T14:00:00Z",""" +
            """"cancelled_at":$cancelled,"version":$version}"""
    }

    fun appointments(items: List<String> = listOf(appointment()), nextCursor: String? = null): String {
        val cursor = nextCursor?.let { "\"$it\"" } ?: "null"
        return """{"items":${items.joinToString(",", "[", "]")},"next_cursor":$cursor}"""
    }

    fun error(code: String, details: String = "[]") =
        """{"code":"$code","message":"mensaje","correlation_id":"$CORRELATION_ID","details":$details}"""
}

/** Caché en memoria con la misma semántica que el DAO de Room (una fila por deportista y lectura). */
class InMemoryVirtualSessionsDao : VirtualSessionsCacheDao {
    val catalogs = MutableStateFlow<Map<String, VirtualSessionsCatalogEntity>>(emptyMap())
    val appointments = MutableStateFlow<Map<String, VirtualSessionsAppointmentsEntity>>(emptyMap())
    var failWrites = false

    override fun observeCatalog(owner: String): Flow<VirtualSessionsCatalogEntity?> = catalogs.map { it[owner] }

    override suspend fun readCatalog(owner: String): VirtualSessionsCatalogEntity? = catalogs.value[owner]

    override suspend fun replaceCatalog(entry: VirtualSessionsCatalogEntity) {
        check(!failWrites) { "disco lleno" }
        catalogs.value = catalogs.value + (entry.ownerKey to entry)
    }

    override fun observeAppointments(owner: String): Flow<VirtualSessionsAppointmentsEntity?> = appointments.map { it[owner] }

    override suspend fun readAppointments(owner: String): VirtualSessionsAppointmentsEntity? = appointments.value[owner]

    override suspend fun replaceAppointments(entry: VirtualSessionsAppointmentsEntity) {
        check(!failWrites) { "disco lleno" }
        appointments.value = appointments.value + (entry.ownerKey to entry)
    }

    override suspend fun deleteCatalog(owner: String) {
        catalogs.value = catalogs.value - owner
    }

    override suspend fun deleteAppointments(owner: String) {
        appointments.value = appointments.value - owner
    }

    override suspend fun deleteCatalogsExcept(owner: String) {
        catalogs.value = catalogs.value.filterKeys { it == owner }
    }

    override suspend fun deleteAppointmentsExcept(owner: String) {
        appointments.value = appointments.value.filterKeys { it == owner }
    }
}
