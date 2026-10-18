package com.lfnj.sport_app.core.network

/**
 * Resultado de una llamada al BFF móvil. Las fallas no contienen cuerpos, tokens ni datos
 * personales: solo lo necesario para decidir el estado de pantalla y trazar el error.
 */
sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>

    sealed interface Failure : ApiResult<Nothing>

    /**
     * Respuesta HTTP no exitosa con el `code` versionado del contrato, si vino en el cuerpo, y
     * las causas `details[].issue` de una validación (sin valores enviados). [fieldIssues] conserva
     * además el campo de cada causa (`details[].field`), p. ej. para señalar la entrada rechazada.
     */
    data class HttpFailure(
        val status: Int,
        val code: String?,
        val correlationId: String?,
        val retryAfterSeconds: Long?,
        val issues: List<String> = emptyList(),
        val fieldIssues: List<FieldIssue> = emptyList(),
    ) : Failure

    /** Campo y causa estables de una validación; nunca contiene el valor enviado. */
    data class FieldIssue(val field: String?, val issue: String)

    /** Sin red, servidor inalcanzable o conexión interrumpida. */
    data object NoConnection : Failure

    /** El BFF no respondió dentro del tiempo configurado. */
    data object Timeout : Failure

    /** El cuerpo no corresponde al contrato esperado. */
    data object InvalidResponse : Failure

    /** La variante no tiene URL del BFF aprobada. */
    data object NotConfigured : Failure
}
