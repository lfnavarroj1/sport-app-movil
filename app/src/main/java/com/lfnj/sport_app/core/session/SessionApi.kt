package com.lfnj.sport_app.core.session

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

/** Operaciones de sesión e identidad del contrato `bff-mobile` 0.3.0 (HU003). */
interface SessionApi {
    @POST("v1/sessions")
    suspend fun createSession(@Body request: LoginRequestDto): Response<SessionResponseDto>

    @POST("v1/sessions/refresh")
    suspend fun refreshSession(@Body request: RefreshSessionRequestDto): Response<SessionResponseDto>

    @GET("v1/me/capabilities")
    suspend fun capabilities(@Header("Authorization") authorization: String): Response<CapabilitiesResponseDto>
}
