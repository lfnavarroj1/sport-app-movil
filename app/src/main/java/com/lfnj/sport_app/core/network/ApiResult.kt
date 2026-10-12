package com.lfnj.sport_app.core.network

/**
 * Resultado de una llamada al BFF móvil. Las fallas no contienen cuerpos, tokens ni datos
 * personales: solo lo necesario para decidir el estado de pantalla y trazar el error.
 */
sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>

    sealed interface Failure : ApiResult<Nothing>

    /** Respuesta HTTP no exitosa con el `code` versionado del contrato, si vino en el cuerpo. */
    data class HttpFailure(
        val status: Int,
        val code: String?,
        val correlationId: String?,
        val retryAfterSeconds: Long?,
    ) : Failure

    /** Sin red, servidor inalcanzable o conexión interrumpida. */
    data object NoConnection : Failure

    /** El BFF no respondió dentro del tiempo configurado. */
    data object Timeout : Failure

    /** El cuerpo no corresponde al contrato esperado. */
    data object InvalidResponse : Failure

    /** La variante no tiene URL del BFF aprobada. */
    data object NotConfigured : Failure
}
