package com.lfnj.sport_app.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.Response
import java.io.IOException
import java.io.InterruptedIOException

const val CORRELATION_HEADER = "X-Correlation-ID"
private const val RETRY_AFTER_HEADER = "Retry-After"
private const val NO_CONTENT = 204

/**
 * Ejecuta una operación Retrofit y la traduce a [ApiResult]. Nunca registra la solicitud
 * ni la respuesta; solo conserva estado HTTP, `code`, causas de validación, `correlation_id`
 * y `Retry-After`.
 */
suspend fun <T : Any> apiCall(json: Json, block: suspend () -> Response<T>): ApiResult<T> =
    execute(json, block) { _, body -> if (body != null) ApiResult.Success(body) else ApiResult.InvalidResponse }

/**
 * Igual que [apiCall], pero `204 No Content` es un éxito sin cuerpo (`null`), como
 * `GET /v1/training-sessions/active` cuando no existe sesión activa (HU032).
 */
suspend fun <T : Any> apiCallAllowingNoContent(json: Json, block: suspend () -> Response<T>): ApiResult<T?> =
    execute(json, block) { response, body ->
        when {
            response.code() == NO_CONTENT -> ApiResult.Success(null)
            body != null -> ApiResult.Success(body)
            else -> ApiResult.InvalidResponse
        }
    }

private suspend fun <T : Any, R> execute(
    json: Json,
    block: suspend () -> Response<T>,
    onSuccess: (Response<T>, T?) -> ApiResult<R>,
): ApiResult<R> =
    try {
        val response = block()
        if (response.isSuccessful) onSuccess(response, response.body()) else response.toHttpFailure(json)
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
        issues = error?.details?.map { it.issue }.orEmpty(),
    )
}
