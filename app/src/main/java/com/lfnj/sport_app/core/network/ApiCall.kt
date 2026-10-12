package com.lfnj.sport_app.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.Response
import java.io.IOException
import java.io.InterruptedIOException

const val CORRELATION_HEADER = "X-Correlation-ID"
private const val RETRY_AFTER_HEADER = "Retry-After"

/**
 * Ejecuta una operación Retrofit y la traduce a [ApiResult]. Nunca registra la solicitud
 * ni la respuesta; solo conserva estado HTTP, `code`, `correlation_id` y `Retry-After`.
 */
suspend fun <T : Any> apiCall(json: Json, block: suspend () -> Response<T>): ApiResult<T> =
    try {
        val response = block()
        val body = response.body()
        if (response.isSuccessful && body != null) {
            ApiResult.Success(body)
        } else if (response.isSuccessful) {
            ApiResult.InvalidResponse
        } else {
            response.toHttpFailure(json)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: InterruptedIOException) {
        ApiResult.Timeout
    } catch (_: IOException) {
        ApiResult.NoConnection
    } catch (_: SerializationException) {
        ApiResult.InvalidResponse
    } catch (_: IllegalArgumentException) {
        ApiResult.InvalidResponse
    }

private fun Response<*>.toHttpFailure(json: Json): ApiResult.HttpFailure {
    val error = errorBody()?.use { body ->
        runCatching { json.decodeFromString(ErrorResponseDto.serializer(), body.string()) }.getOrNull()
    }
    return ApiResult.HttpFailure(
        status = code(),
        code = error?.code,
        correlationId = error?.correlationId ?: headers()[CORRELATION_HEADER],
        retryAfterSeconds = headers()[RETRY_AFTER_HEADER]?.trim()?.toLongOrNull()?.takeIf { it >= 0 },
    )
}
