package com.lfnj.sport_app.core.session

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

/** Operaciones de sesión e identidad de `bff-mobile` (HU003 desde 0.3.0; cierre de HU004 desde 0.4.0). */
interface SessionApi {
    @POST("v1/sessions")
    suspend fun createSession(@Body request: LoginRequestDto): Response<SessionResponseDto>

    @POST("v1/sessions/refresh")
    suspend fun refreshSession(@Body request: RefreshSessionRequestDto): Response<SessionResponseDto>

    /**
     * Cierre de sesión (HU004): `204` si el servicio confirma, también para una sesión ya cerrada,
     * vencida o desconocida. No lleva access token; `Idempotency-Key` es opcional y la cola la repite.
     */
    @POST("v1/sessions/revoke")
    suspend fun revokeSession(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: RefreshSessionRequestDto,
    ): Response<Unit>

    @GET("v1/me/capabilities")
    suspend fun capabilities(@Header("Authorization") authorization: String): Response<CapabilitiesResponseDto>
}
