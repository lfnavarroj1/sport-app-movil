package com.lfnj.sport_app.features.integrations.data

import com.lfnj.sport_app.features.integrations.model.StravaIntegration
import com.lfnj.sport_app.features.integrations.model.StravaLastSync
import com.lfnj.sport_app.features.integrations.model.StravaLinkStatus
import com.lfnj.sport_app.features.integrations.model.StravaSyncError
import com.lfnj.sport_app.features.integrations.model.StravaSyncStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Integración con Strava del deportista en `bff-mobile` 0.22.0 (HU031, ADR-042 §5–§8). La app nunca
 * llama a Strava ni a Terceros: solo a estas cuatro operaciones. El callback público
 * `GET /v1/integrations/strava/callback` lo recibe el BFF y responde con el enlace profundo.
 */
interface StravaApi {
    /** `201` con la URL del consentimiento oficial y el vencimiento del `state` de un solo uso. */
    @POST("v1/me/integrations/strava/authorization")
    suspend fun startAuthorization(@Header("Authorization") authorization: String): Response<StravaAuthorizationResponseDto>

    @GET("v1/me/integrations/strava")
    suspend fun integration(@Header("Authorization") authorization: String): Response<StravaIntegrationResponseDto>

    /** `202` con la corrida aceptada; la misma clave devuelve la corrida original (`Idempotent-Replayed`). */
    @POST("v1/me/integrations/strava/syncs")
    suspend fun requestSync(
        @Header("Authorization") authorization: String,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): Response<StravaSyncResponseDto>

    /** `204`, idempotente: revoca en Strava (mejor esfuerzo), borra los tokens y conserva las actividades importadas. */
    @DELETE("v1/me/integrations/strava")
    suspend fun unlink(@Header("Authorization") authorization: String): Response<Unit>
}

/** `StravaAuthorizationResponse`. `toString` no expone la URL: contiene el `state` antifalsificación. */
@Serializable
data class StravaAuthorizationResponseDto(
    @SerialName("authorization_url") val authorizationUrl: String,
    @SerialName("expires_at") val expiresAt: String,
) {
    override fun toString(): String = "StravaAuthorizationResponseDto(<oculto>)"
}

/** `StravaIntegrationResponse`; nunca contiene tokens. */
@Serializable
data class StravaIntegrationResponseDto(
    val status: String,
    @SerialName("external_athlete_id") val externalAthleteId: String?,
    @SerialName("linked_at") val linkedAt: String?,
    val scopes: List<String>,
    @SerialName("last_sync") val lastSync: StravaSyncSummaryDto?,
) {
    override fun toString(): String = "StravaIntegrationResponseDto(status=$status)"
}

/** `StravaSyncSummary`. */
@Serializable
data class StravaSyncSummaryDto(
    @SerialName("sync_run_id") val syncRunId: String,
    val status: String,
    @SerialName("requested_at") val requestedAt: String,
    @SerialName("finished_at") val finishedAt: String?,
    @SerialName("imported_count") val importedCount: Long,
    @SerialName("error_code") val errorCode: String?,
) {
    override fun toString(): String = "StravaSyncSummaryDto(status=$status)"
}

/** `StravaSyncResponse`. */
@Serializable
data class StravaSyncResponseDto(
    @SerialName("sync_run_id") val syncRunId: String,
    val status: String,
)

/** La respuesta no corresponde al contrato y no se muestra parcialmente. */
class InvalidStravaResponse : Exception()

/**
 * Convierte el estado `200` en el modelo confirmado. Un valor fuera del contrato invalida toda la
 * respuesta: nunca se completa un estado inventado. El identificador del atleta no se conserva.
 */
fun StravaIntegrationResponseDto.toModel(syncedAt: Instant): StravaIntegration = try {
    StravaIntegration(
        status = StravaLinkStatus.fromWire(status) ?: throw InvalidStravaResponse(),
        linkedAt = linkedAt?.let(::parseInstant),
        scopes = scopes,
        lastSync = lastSync?.let { sync ->
            StravaLastSync(
                status = StravaSyncStatus.fromWire(sync.status) ?: throw InvalidStravaResponse(),
                requestedAt = parseInstant(sync.requestedAt),
                finishedAt = sync.finishedAt?.let(::parseInstant),
                importedCount = sync.importedCount.takeIf { it >= 0 } ?: throw InvalidStravaResponse(),
                error = sync.errorCode?.let { StravaSyncError.fromWire(it) ?: throw InvalidStravaResponse() },
            )
        },
        syncedAt = syncedAt,
    )
} catch (_: DateTimeException) {
    throw InvalidStravaResponse()
}

private fun parseInstant(value: String): Instant = OffsetDateTime.parse(value).toInstant()
