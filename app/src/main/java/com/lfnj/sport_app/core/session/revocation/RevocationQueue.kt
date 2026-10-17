package com.lfnj.sport_app.core.session.revocation

import com.lfnj.sport_app.core.network.ApiResult
import com.lfnj.sport_app.core.network.apiCallAllowingNoContent
import com.lfnj.sport_app.core.security.TokenCipher
import com.lfnj.sport_app.core.session.RefreshSessionRequestDto
import com.lfnj.sport_app.core.session.RevocationResult
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionRevoker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.UUID

/** Programa el trabajo único que vacía la cola de revocación con conectividad. */
fun interface RevocationScheduler {
    fun schedule()
}

/** Resultado de una ejecución de la cola. */
enum class RevocationRun {
    /** No quedan revocaciones pendientes. */
    COMPLETED,

    /** Quedan entradas sin confirmar (red, timeout o `5xx`): reintentar con espera exponencial. */
    RETRY,

    /** La variante no tiene BFF configurado: no hay a dónde enviar. */
    SUSPENDED,
}

/**
 * Cola cifrada de revocación de HU004 (ADR-026 §8 y «Aplicación a HU004»).
 *
 * - Cada credencial se guarda cifrada con su propia clave de Keystore, separada del almacén de
 *   sesión, junto con su vencimiento, una `Idempotency-Key` estable, intentos y próxima ejecución.
 * - Se intenta la revocación de inmediato; sin confirmación queda en la cola y el trabajo único la
 *   reintenta con red y espera exponencial acotada (máximo 5 h, el tope de WorkManager), hasta
 *   confirmarla o hasta que la credencial venza.
 * - `204` confirma y elimina la entrada; el servicio también responde `204` a una sesión ya cerrada
 *   (CA2, CA7). Red, timeout y `5xx` (`503`/`504` del contrato) se reintentan; un `4xx` como
 *   `422` no mejora al repetirse y se descarta.
 * - Una entrada vencida se elimina sin enviarla; una que no puede descifrarse también.
 * - Nunca registra tokens, cuerpos ni respuestas.
 */
class RevocationQueue(
    private val dao: RevocationDao,
    private val cipher: TokenCipher,
    private val api: SessionApi?,
    private val json: Json,
    private val scheduler: RevocationScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : SessionRevoker {
    /** Serializa el intento inmediato y el trabajo durable para no enviar dos veces a la vez. */
    private val mutex = Mutex()

    override suspend fun revoke(refreshToken: String, expiresAtMillis: Long?): RevocationResult {
        val now = clock()
        if (expiresAtMillis != null && expiresAtMillis <= now) return RevocationResult.ALREADY_CLOSED
        val entry = expiresAtMillis?.let { expiresAt -> storage { enqueue(refreshToken, expiresAt, now) } }
        if (entry == null) {
            // Sin vencimiento conocido o sin poder guardar la entrada: un único intento, sin cola.
            return if (send(entry = null, refreshToken, newId()) == Delivery.CONFIRMED) {
                RevocationResult.CONFIRMED
            } else {
                RevocationResult.NOT_CONFIRMED
            }
        }
        val delivery = mutex.withLock { deliver(entry, refreshToken) }
        return when (delivery) {
            Delivery.CONFIRMED -> RevocationResult.CONFIRMED
            Delivery.DISCARDED -> RevocationResult.NOT_CONFIRMED
            Delivery.RETRY -> {
                scheduler.schedule()
                RevocationResult.PENDING
            }
        }
    }

    /** Ejecución del trabajo durable: descarta lo vencido y reintenta las entradas pendientes. */
    suspend fun run(): RevocationRun = mutex.withLock {
        val now = clock()
        dao.deleteExpired(now)
        if (api == null) return@withLock if (dao.count() == 0) RevocationRun.COMPLETED else RevocationRun.SUSPENDED
        for (entry in dao.entries()) {
            if (entry.nextAttemptAtMs > now) continue
            val token = decrypt(entry)
            if (token == null) {
                dao.delete(entry.entryId)
                continue
            }
            // Sin red para una entrada, tampoco la hay para las siguientes: se espera el próximo intento.
            if (deliver(entry, token) == Delivery.RETRY) break
        }
        if (dao.count() == 0) RevocationRun.COMPLETED else RevocationRun.RETRY
    }

    /** `true` si quedan revocaciones pendientes (p. ej. al abrir la aplicación). */
    suspend fun hasPending(): Boolean = storage { dao.count() > 0 } ?: false

    private enum class Delivery { CONFIRMED, RETRY, DISCARDED }

    private suspend fun enqueue(refreshToken: String, expiresAt: Long, now: Long): RevocationEntryEntity {
        val entry = RevocationEntryEntity(
            entryId = newId(),
            idempotencyKey = newId(),
            expiresAtMs = expiresAt,
            nextAttemptAtMs = now,
            payload = cipher.encrypt(refreshToken.toByteArray(Charsets.UTF_8)),
        )
        dao.insert(entry)
        return entry
    }

    /** Envía una entrada de la cola y actualiza su estado. Debe llamarse con [mutex] tomado. */
    private suspend fun deliver(entry: RevocationEntryEntity, refreshToken: String): Delivery {
        val delivery = send(entry, refreshToken, entry.idempotencyKey)
        when (delivery) {
            Delivery.CONFIRMED, Delivery.DISCARDED -> dao.delete(entry.entryId)
            Delivery.RETRY -> dao.markAttempt(entry.entryId, clock() + backoffMillis(entry.attempts + 1))
        }
        return delivery
    }

    private suspend fun send(entry: RevocationEntryEntity?, refreshToken: String, idempotencyKey: String): Delivery {
        val api = api ?: return if (entry == null) Delivery.DISCARDED else Delivery.RETRY
        val result = apiCallAllowingNoContent(json) {
            api.revokeSession(idempotencyKey, RefreshSessionRequestDto(refreshToken))
        }
        return when (result) {
            is ApiResult.Success -> Delivery.CONFIRMED
            is ApiResult.HttpFailure -> if (result.status in SERVER_ERRORS) Delivery.RETRY else Delivery.DISCARDED
            ApiResult.NoConnection, ApiResult.Timeout, ApiResult.InvalidResponse, ApiResult.NotConfigured -> Delivery.RETRY
        }
    }

    private fun decrypt(entry: RevocationEntryEntity): String? = try {
        cipher.decrypt(entry.payload).toString(Charsets.UTF_8)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** Errores de Room o del Keystore se tratan como «no se pudo guardar», sin detalles. */
    private suspend fun <T> storage(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        const val KEY_ALIAS = "sportapp.revocation.v1"

        /** Espera inicial del reintento; coincide con la del trabajo de WorkManager. */
        const val INITIAL_BACKOFF_MILLIS = 30_000L

        /** Tope de la espera exponencial (el máximo que admite WorkManager). */
        const val MAX_BACKOFF_MILLIS = 5 * 60 * 60 * 1000L

        private val SERVER_ERRORS = 500..599

        /** Espera antes del intento número [attempt] + 1: 30 s, 60 s, 120 s… hasta 5 h. */
        fun backoffMillis(attempt: Int): Long {
            val shift = (attempt - 1).coerceIn(0, 20)
            return (INITIAL_BACKOFF_MILLIS shl shift).coerceAtMost(MAX_BACKOFF_MILLIS)
        }
    }
}
