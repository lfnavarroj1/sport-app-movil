package com.lfnj.sport_app.testing

import com.lfnj.sport_app.features.integrations.data.local.StravaCacheDao
import com.lfnj.sport_app.features.integrations.data.local.StravaCacheEntity
import com.lfnj.sport_app.features.integrations.model.StravaIntegration
import com.lfnj.sport_app.features.integrations.model.StravaLastSync
import com.lfnj.sport_app.features.integrations.model.StravaLinkStatus
import com.lfnj.sport_app.features.integrations.model.StravaSyncError
import com.lfnj.sport_app.features.integrations.model.StravaSyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID

/** Datos sintéticos de HU031 con la forma de `bff-mobile` 0.22.0 (ADR-042). Nunca incluyen tokens reales. */
object StravaFixtures {
    /** Identificador de corrida generado, para no fijar literales de alta entropía en las pruebas. */
    val SYNC_RUN_ID: String = UUID.nameUUIDFromBytes("strava-sync-run".toByteArray()).toString()

    const val AUTHORIZATION_URL =
        "https://www.strava.com/oauth/mobile/authorize?client_id=12345&redirect_uri=https%3A%2F%2Fbff.example%2Fv1%2Fintegrations%2Fstrava%2Fcallback" +
            "&response_type=code&approval_prompt=auto&scope=read%2Cactivity%3Aread&state=estado-sintetico"

    fun authorizationBody(url: String = AUTHORIZATION_URL, expiresAt: String = "2026-11-15T15:10:00Z") =
        """{"authorization_url":"$url","expires_at":"$expiresAt"}"""

    fun lastSync(
        status: String = "SUCCEEDED",
        finishedAt: String? = "2026-11-15T14:05:00Z",
        importedCount: Int = 3,
        errorCode: String? = null,
    ): String {
        val finished = finishedAt?.let { "\"$it\"" } ?: "null"
        val error = errorCode?.let { "\"$it\"" } ?: "null"
        return """{"sync_run_id":"$SYNC_RUN_ID","status":"$status","requested_at":"2026-11-15T14:00:00Z",""" +
            """"finished_at":$finished,"imported_count":$importedCount,"error_code":$error}"""
    }

    fun integrationBody(
        status: String = "ACTIVE",
        linkedAt: String? = "2026-11-10T12:00:00Z",
        scopes: List<String> = listOf("read", "activity:read"),
        lastSync: String? = lastSync(),
        athleteId: String? = "98765",
    ): String {
        val linked = linkedAt?.let { "\"$it\"" } ?: "null"
        val athlete = athleteId?.let { "\"$it\"" } ?: "null"
        return """{"status":"$status","external_athlete_id":$athlete,"linked_at":$linked,""" +
            """"scopes":${scopes.joinToString(",", "[", "]") { "\"$it\"" }},"last_sync":${lastSync ?: "null"}}"""
    }

    fun notLinkedBody() = integrationBody(status = "NOT_LINKED", linkedAt = null, scopes = emptyList(), lastSync = null, athleteId = null)

    fun syncBody(status: String = "QUEUED") = """{"sync_run_id":"$SYNC_RUN_ID","status":"$status"}"""

    fun integration(
        status: StravaLinkStatus = StravaLinkStatus.ACTIVE,
        syncedAt: Instant = Instant.parse("2026-11-15T15:00:00Z"),
        lastSync: StravaLastSync? = StravaLastSync(
            StravaSyncStatus.SUCCEEDED, Instant.parse("2026-11-15T14:00:00Z"), Instant.parse("2026-11-15T14:05:00Z"), 3, null,
        ),
    ) = StravaIntegration(
        status = status,
        linkedAt = if (status == StravaLinkStatus.NOT_LINKED) null else Instant.parse("2026-11-10T12:00:00Z"),
        scopes = if (status == StravaLinkStatus.NOT_LINKED) emptyList() else listOf("read", "activity:read"),
        lastSync = lastSync,
        syncedAt = syncedAt,
    )

    fun sync(status: StravaSyncStatus, error: StravaSyncError? = null) = StravaLastSync(
        status = status,
        requestedAt = Instant.parse("2026-11-15T14:00:00Z"),
        finishedAt = if (status.inProgress) null else Instant.parse("2026-11-15T14:05:00Z"),
        importedCount = if (status == StravaSyncStatus.SUCCEEDED) 3 else 0,
        error = error,
    )
}

/** Caché en memoria con la misma semántica que el DAO de Room (una fila por deportista). */
class InMemoryStravaDao : StravaCacheDao {
    val rows = MutableStateFlow<Map<String, StravaCacheEntity>>(emptyMap())
    var failWrites = false

    override fun observe(owner: String): Flow<StravaCacheEntity?> = rows.map { it[owner] }

    override suspend fun replace(entry: StravaCacheEntity) {
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
