package com.lfnj.sport_app.features.training.session.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

const val IDEMPOTENCY_HEADER = "Idempotency-Key"

/**
 * Operaciones de HU032 en `bff-mobile` (ADR-029, «Contrato HTTP»). La identidad sale del token:
 * los cuerpos nunca llevan `athlete_id`, `status`, `version` ni `duration_seconds`.
 */
interface TrainingSessionApi {
    @POST("v1/training-sessions")
    suspend fun start(
        @Header("Authorization") authorization: String,
        @Header(IDEMPOTENCY_HEADER) idempotencyKey: String,
        @Body body: StartTrainingSessionRequestDto,
    ): Response<TrainingSessionDto>

    @POST("v1/training-sessions/{session_id}/finish")
    suspend fun finish(
        @Header("Authorization") authorization: String,
        @Header(IDEMPOTENCY_HEADER) idempotencyKey: String,
        @Path("session_id") sessionId: String,
        @Body body: FinishTrainingSessionRequestDto,
    ): Response<TrainingSessionDto>

    /** `200` con la sesión activa propia o `204` si no existe (consulta CQRS). */
    @GET("v1/training-sessions/active")
    suspend fun active(@Header("Authorization") authorization: String): Response<ActiveTrainingSessionDto>
}

/** Cuerpo de `POST /v1/training-sessions`. `plan_id` se omite en una sesión libre. */
@Serializable
data class StartTrainingSessionRequestDto(
    @SerialName("session_id") val sessionId: String,
    @SerialName("plan_id") val planId: String? = null,
    val sport: String,
    @SerialName("started_at") val startedAt: String,
) {
    override fun toString(): String = "StartTrainingSessionRequestDto(<oculto>)"
}

/** Cuerpo de `POST /v1/training-sessions/{session_id}/finish`. */
@Serializable
data class FinishTrainingSessionRequestDto(
    @SerialName("ended_at") val endedAt: String,
) {
    override fun toString(): String = "FinishTrainingSessionRequestDto(<oculto>)"
}

/**
 * Sesión activa propia (`ActiveTrainingSessionResponse`): la vista de lectura no incluye fin,
 * duración ni marcas de creación y actualización.
 */
@Serializable
data class ActiveTrainingSessionDto(
    @SerialName("session_id") val sessionId: String,
    @SerialName("athlete_id") val athleteId: String,
    @SerialName("plan_id") val planId: String? = null,
    val sport: String,
    @SerialName("started_at") val startedAt: String,
    val status: String,
    val version: Int,
) {
    override fun toString(): String = "ActiveTrainingSessionDto(status=$status, version=$version)"
}

/** Sesión confirmada por el backend (`ACTIVE` versión 1 o `FINISHED` versión 2). */
@Serializable
data class TrainingSessionDto(
    @SerialName("session_id") val sessionId: String,
    @SerialName("athlete_id") val athleteId: String,
    @SerialName("plan_id") val planId: String? = null,
    val sport: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String? = null,
    @SerialName("duration_seconds") val durationSeconds: Long? = null,
    val status: String,
    val version: Int,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
) {
    override fun toString(): String = "TrainingSessionDto(status=$status, version=$version)"

    companion object {
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_FINISHED = "FINISHED"
    }
}
