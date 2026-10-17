package com.lfnj.sport_app.features.training.session.sync

import com.lfnj.sport_app.core.network.ApiResult
import com.lfnj.sport_app.core.network.apiCall
import com.lfnj.sport_app.core.session.AuthorizedResult
import com.lfnj.sport_app.core.session.RestoreOutcome
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.features.training.session.data.SystemTrainingClock
import com.lfnj.sport_app.features.training.session.data.TrainingClock
import com.lfnj.sport_app.features.training.session.data.TrainingSessionApi
import com.lfnj.sport_app.features.training.session.data.TrainingSessionDto
import com.lfnj.sport_app.features.training.session.data.local.PendingCommand
import com.lfnj.sport_app.features.training.session.data.local.TrainingRecordCodec
import com.lfnj.sport_app.features.training.session.data.local.TrainingSessionDao
import com.lfnj.sport_app.features.training.session.data.local.TransactionRunner
import com.lfnj.sport_app.features.training.session.model.SyncFailure
import com.lfnj.sport_app.features.training.session.model.SyncState
import com.lfnj.sport_app.features.training.session.model.TrainingOwner
import kotlinx.serialization.json.Json
import java.time.Duration

/** Resultado de una ejecución de sincronización. */
enum class SyncRun {
    /** La cola de la partición quedó vacía. */
    COMPLETED,

    /** Sin red, renovación o servicio disponible (`502`/`503`/`504`): reintentar con espera exponencial. */
    RETRY,

    /** No hay sesión del mismo deportista: se detiene hasta que vuelva a autenticarse (ADR-029 offline §4). */
    SUSPENDED,
}

/**
 * Sincroniza la cola de HU032 de un deportista (ADR-029, «Política offline de HU032»):
 *
 * - procesa los comandos en orden de llegada; el fin de una sesión nunca se envía antes de su inicio;
 * - cada comando reutiliza su `Idempotency-Key` y su cuerpo normalizado, por lo que un reintento
 *   tras una respuesta perdida no duplica efectos (CA4, CA7);
 * - solo reintenta pérdida de red, renovación no disponible y `502`/`503`/`504`; un rechazo
 *   funcional deja toda la cadena de la sesión en `SYNC_FAILED` y descarta sus comandos (offline §3 y §7);
 * - un comando con más de 30 días vence: sale de la cola y su sesión queda `SYNC_FAILED` (offline §6);
 * - usa la sesión vigente del deportista dueño de la partición y nunca guarda tokens.
 */
class TrainingSyncEngine(
    private val dao: TrainingSessionDao,
    private val transactions: TransactionRunner,
    private val codec: TrainingRecordCodec,
    private val api: TrainingSessionApi?,
    private val sessionManager: SessionManager,
    private val json: Json,
    private val clock: TrainingClock = SystemTrainingClock,
) {
    suspend fun run(owner: String): SyncRun {
        expirePending()
        val api = api ?: return SyncRun.SUSPENDED
        when (val identity = authenticatedOwner()) {
            Identity.Unreachable -> return SyncRun.RETRY
            Identity.SignedOut -> return SyncRun.SUSPENDED
            is Identity.Owner -> if (identity.key != owner) return SyncRun.SUSPENDED
        }
        while (true) {
            val entity = dao.nextCommand(owner) ?: return SyncRun.COMPLETED
            val command = codec.decodeCommand(entity)
            if (command == null) {
                failChain(owner, entity.sessionId, SyncFailure.UNREADABLE)
                continue
            }
            transactions.run {
                dao.markAttempt(entity.seq, clock.now().toEpochMilli())
                dao.session(owner, entity.sessionId)?.let { session ->
                    if (session.syncState != SyncState.SYNC_FAILED.name) {
                        dao.updateSession(session.copy(syncState = SyncState.PENDING_SYNC.name))
                    }
                }
            }
            val outcome = sessionManager.authorized { auth -> send(api, auth, command) }
            when (outcome) {
                AuthorizedResult.SignedOut -> return SyncRun.SUSPENDED
                AuthorizedResult.RenewalUnavailable -> return SyncRun.RETRY
                is AuthorizedResult.Completed -> when (val result = outcome.result) {
                    is ApiResult.Success -> confirm(owner, command, result.value)
                    ApiResult.NoConnection, ApiResult.Timeout -> return SyncRun.RETRY
                    ApiResult.NotConfigured -> return SyncRun.SUSPENDED
                    ApiResult.InvalidResponse -> failChain(owner, entity.sessionId, SyncFailure.INVALID_RESPONSE)
                    is ApiResult.HttpFailure ->
                        if (result.status in RETRYABLE_STATUSES) {
                            return SyncRun.RETRY
                        } else {
                            failChain(owner, entity.sessionId, SyncFailure.fromHttp(result.code, result.issues))
                        }
                }
            }
        }
    }

    private sealed interface Identity {
        data class Owner(val key: String) : Identity
        /** No hay red o servicio para recuperar la sesión. */
        data object Unreachable : Identity
        data object SignedOut : Identity
    }

    /** Deportista autenticado. Tras un reinicio del proceso recupera la sesión con el refresh token cifrado. */
    private suspend fun authenticatedOwner(): Identity {
        sessionManager.currentUser.value?.let { return Identity.Owner(TrainingOwner.keyOf(it.userId)) }
        return when (val restored = sessionManager.restore()) {
            is RestoreOutcome.Restored -> Identity.Owner(TrainingOwner.keyOf(restored.user.userId))
            RestoreOutcome.NoConnection, is RestoreOutcome.Unavailable -> Identity.Unreachable
            RestoreOutcome.NoSession, RestoreOutcome.Expired, RestoreOutcome.UnsupportedActor, RestoreOutcome.NotConfigured ->
                Identity.SignedOut
        }
    }

    private suspend fun send(api: TrainingSessionApi, authorization: String, command: PendingCommand): ApiResult<TrainingSessionDto> =
        when (command) {
            is PendingCommand.Start -> apiCall(json) { api.start(authorization, command.entity.commandId, command.body) }
            is PendingCommand.Finish ->
                apiCall(json) { api.finish(authorization, command.entity.commandId, command.entity.sessionId, command.body) }
        }

    /**
     * Aplica la respuesta sin degradar la versión local: la confirmación del inicio no borra un fin
     * registrado después en el dispositivo; la sesión queda `SYNCED` solo sin comandos pendientes.
     */
    private suspend fun confirm(owner: String, command: PendingCommand, remote: TrainingSessionDto) {
        val sessionId = command.entity.sessionId
        val valid = remote.sessionId == sessionId && when (command) {
            is PendingCommand.Start -> remote.status == TrainingSessionDto.STATUS_ACTIVE
            is PendingCommand.Finish ->
                remote.status == TrainingSessionDto.STATUS_FINISHED && remote.durationSeconds != null && remote.durationSeconds >= 0
        }
        if (!valid) {
            failChain(owner, sessionId, SyncFailure.INVALID_RESPONSE)
            return
        }
        val readable = transactions.run {
            dao.deleteCommand(command.entity.seq)
            val session = dao.session(owner, sessionId) ?: return@run true
            val details = codec.decodeDetails(session) ?: return@run false
            val updated = if (command is PendingCommand.Finish) details.copy(officialDurationSeconds = remote.durationSeconds) else details
            val state = if (dao.countCommands(owner, sessionId) == 0) SyncState.SYNCED else SyncState.PENDING_SYNC
            dao.updateSession(session.copy(syncState = state.name, failureReason = null, payload = codec.encodeDetails(updated)))
            true
        }
        if (!readable) failChain(owner, sessionId, SyncFailure.UNREADABLE)
    }

    private suspend fun failChain(owner: String, sessionId: String, reason: SyncFailure) = transactions.run {
        dao.deleteCommands(owner, sessionId)
        dao.session(owner, sessionId)?.let {
            dao.updateSession(it.copy(syncState = SyncState.SYNC_FAILED.name, failureReason = reason.name))
        }
    }

    /** Vence los comandos con más de 30 días en todas las particiones del dispositivo. */
    private suspend fun expirePending() {
        val threshold = clock.now().minus(RETENTION).toEpochMilli()
        dao.commandsCreatedBefore(threshold)
            .map { it.ownerKey to it.sessionId }
            .distinct()
            .forEach { (owner, sessionId) -> failChain(owner, sessionId, SyncFailure.EXPIRED) }
    }

    companion object {
        /** Vencimiento de los comandos pendientes (ADR-029 offline §6). */
        val RETENTION: Duration = Duration.ofDays(30)
        private val RETRYABLE_STATUSES = setOf(502, 503, 504)
    }
}
