package com.lfnj.sport_app.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `ErrorResponse` del contrato `bff-mobile`. */
@Serializable
data class ErrorResponseDto(
    val code: String,
    val message: String,
    @SerialName("correlation_id") val correlationId: String? = null,
    val details: List<ErrorDetailDto> = emptyList(),
)

@Serializable
data class ErrorDetailDto(
    val field: String? = null,
    val issue: String,
)
