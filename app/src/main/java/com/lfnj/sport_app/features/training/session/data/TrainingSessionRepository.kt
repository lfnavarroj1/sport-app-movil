package com.lfnj.sport_app.features.training.session.data

import android.os.SystemClock
import com.lfnj.sport_app.core.network.ApiResult
import com.lfnj.sport_app.core.network.apiCallAllowingNoContent
import com.lfnj.sport_app.core.session.AuthorizedResult
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.features.training.session.data.local.CommandType
import com.lfnj.sport_app.features.training.session.data.local.TrainingCommandEntity
import com.lfnj.sport_app.features.training.session.data.local.TrainingRecordCodec
import com.lfnj.sport_app.features.training.session.data.local.TrainingSessionDao
import com.lfnj.sport_app.features.training.session.data.local.TrainingSessionEntity
import com.lfnj.sport_app.features.training.session.data.local.TransactionRunner
import com.lfnj.sport_app.features.training.session.model.LocalTrainingSession
import com.lfnj.sport_app.features.training.session.model.SyncState
import com.lfnj.sport_app.features.training.session.model.TrainingSessionDetails
import com.lfnj.sport_app.features.training.session.model.TrainingSport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Reloj de pared (UTC) para los instantes enviados y monotónico para el cronómetro vivo (ADR-029 offline §8). */
interface TrainingClock {
    fun now(): Instant
    fun elapsedRealtimeMillis(): Long
}

object SystemTrainingClock : TrainingClock {
    override fun now(): Instant = Instant.now()
    override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
}

/** Programa la sincronización durable de la partición de un deportista. */
interface TrainingSyncScheduler {
    fun schedule(owner: String)

    /** Detiene toda sincronización en curso o programada (cierre de sesión, ADR-029 offline §4). */
    fun cancelAll()
}

sealed interface StartResult {
    data class Started(val sessionId: String) : StartResult
    /** Ya existe una sesión abierta en el dispositivo (ADR-029 §3: como máximo una `ACTIVE`). */
    data object AlreadyOpen : StartResult
    data object StorageFailed : StartResult
}

enum class FinishResult { FINISHED, NOT_OPEN, STORAGE_FAILED }

/** Resultado de consultar la sesión activa remota al abrir la pantalla. */
enum class RemoteCheck { CHECKED, NO_CONNECTION, UNAVAILABLE, SIGNED_OUT }

/** Operaciones de la pantalla de sesión; las implementa [TrainingSessionRepository]. */
interface TrainingSessionGateway {
    fun observe(owner: String): Flow<List<LocalTrainingSession>>
    /** Marca monotónica del inicio si ocurrió en este proceso; `null` tras un reinicio. */
    fun monotonicStartMillis(sessionId: String): Long?
    suspend fun start(owner: String, sport: TrainingSport): StartResult
    suspend fun finish(owner: String, sessionId: String): FinishResult
    suspend fun dismiss(owner: String, sessionId: String): Boolean
    suspend fun checkRemoteActive(owner: String): RemoteCheck
}

/** Formato de los instantes del contrato: UTC con sufijo `Z` y precisión de segundos (ADR-029 §5). */
fun Instant.toWireInstant(): String = DateTimeFormatter.ISO_INSTANT.format(truncatedTo(ChronoUnit.SECONDS))

/**
 * Repositorio offline first de HU032 (ADR-026 y ADR-029). La pantalla observa la fuente local;
 * iniciar y finalizar guardan primero la sesión y su comando cifrado en una sola transacción y
 * después programan la sincronización. Nunca marca una acción como confirmada sin respuesta del
 * backend ni calcula `duration_seconds` para enviarlo.
 */
class TrainingSessionRepository(
    private val dao: TrainingSessionDao,
    private val transactions: TransactionRunner,
    private val codec: TrainingRecordCodec,
    private val scheduler: TrainingSyncScheduler,
    private val api: TrainingSessionApi?,
    private val sessionManager: SessionManager,
    private val json: Json,
    private val clock: TrainingClock = SystemTrainingClock,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : TrainingSessionGateway {
    private val monotonicStarts = ConcurrentHashMap<String, Long>()

    override fun observe(owner: String): Flow<List<LocalTrainingSession>> =
        dao.observeSessions(owner).map { sessions -> sessions.map(codec::toLocalSession) }

    override fun monotonicStartMillis(sessionId: String): Long? = monotonicStarts[sessionId]

    /** Si el deportista tiene una sesión abierta en el dispositivo; una falla local se informa como `false`. */
    fun observeOpenSession(owner: String): Flow<Boolean> =
        observe(owner).map { sessions -> sessions.any(LocalTrainingSession::open) }.distinctUntilChanged().catch { emit(false) }

    suspend fun hasPendingCommands(owner: String): Boolean = dao.countCommands(owner) > 0

    override suspend fun start(owner: String, sport: TrainingSport): StartResult {
        val startedAt = clock.now().truncatedTo(ChronoUnit.SECONDS)
        val monotonic = clock.elapsedRealtimeMillis()
        // `session_id` (UUID v4) y la clave del comando se generan y persisten antes de cambiar la UI.
        val sessionId = newId()
        val details = TrainingSessionDetails(sport, planId = null, startedAt = startedAt, endedAt = null, officialDurationSeconds = null)
        val body = StartTrainingSessionRequestDto(sessionId, planId = null, sport = sport.wire, startedAt = startedAt.toWireInstant())
        // Antes de la transacción: la fuente observable puede emitir la sesión nueva al confirmarse.
        monotonicStarts[sessionId] = monotonic
        val result = storage(StartResult.StorageFailed) {
            transactions.run {
                if (dao.sessions(owner).any { codec.toLocalSession(it).open }) {
                    StartResult.AlreadyOpen
                } else {
                    dao.insertSession(
                        TrainingSessionEntity(sessionId, owner, SyncState.LOCAL.name, null, startedAt.toEpochMilli(), codec.encodeDetails(details))
                    )
                    dao.insertCommand(command(owner, sessionId, CommandType.START, codec.encodeStart(body)))
                    StartResult.Started(sessionId)
                }
            }
        }
        if (result is StartResult.Started) scheduler.schedule(owner) else monotonicStarts.remove(sessionId)
        return result
    }

    override suspend fun finish(owner: String, sessionId: String): FinishResult {
        val endedAt = clock.now().truncatedTo(ChronoUnit.SECONDS)
        val result = storage(FinishResult.STORAGE_FAILED) {
            transactions.run {
                val entity = dao.session(owner, sessionId)
                val local = entity?.let(codec::toLocalSession)
                if (entity == null || local == null || !local.open) {
                    FinishResult.NOT_OPEN
                } else {
                    // Un registro ya confirmado vuelve a LOCAL: su nueva versión aún no se envió.
                    val state = if (local.syncState == SyncState.SYNCED) SyncState.LOCAL else local.syncState
                    val details = checkNotNull(local.details).copy(endedAt = endedAt)
                    dao.updateSession(entity.copy(syncState = state.name, payload = codec.encodeDetails(details)))
                    val body = FinishTrainingSessionRequestDto(endedAt.toWireInstant())
                    dao.insertCommand(command(owner, sessionId, CommandType.FINISH, codec.encodeFinish(body)))
                    FinishResult.FINISHED
                }
            }
        }
        if (result == FinishResult.FINISHED) {
            monotonicStarts.remove(sessionId)
            scheduler.schedule(owner)
        }
        return result
    }

    /** Retira del dispositivo un registro confirmado y terminado, o uno rechazado ya informado. */
    override suspend fun dismiss(owner: String, sessionId: String): Boolean = storage(false) {
        transactions.run {
            val entity = dao.session(owner, sessionId)
            if (entity == null || !codec.toLocalSession(entity).dismissible) {
                false
            } else {
                dao.deleteCommands(owner, sessionId)
                dao.deleteSession(owner, sessionId)
                monotonicStarts.remove(sessionId)
                true
            }
        }
    }

    /**
     * Recupera la sesión activa remota (`GET /v1/training-sessions/active`), p. ej. iniciada en otro
     * dispositivo o antes de reinstalar. Solo se adopta si el dispositivo no tiene una sesión
     * abierta ni conoce ese `session_id`: nunca contradice una versión local más reciente.
     */
    override suspend fun checkRemoteActive(owner: String): RemoteCheck {
        val api = api ?: return RemoteCheck.UNAVAILABLE
        return when (val outcome = sessionManager.authorized { auth -> apiCallAllowingNoContent(json) { api.active(auth) } }) {
            AuthorizedResult.SignedOut -> RemoteCheck.SIGNED_OUT
            AuthorizedResult.RenewalUnavailable -> RemoteCheck.UNAVAILABLE
            is AuthorizedResult.Completed -> when (val result = outcome.result) {
                is ApiResult.Success -> {
                    result.value?.let { adopt(owner, it) }
                    RemoteCheck.CHECKED
                }
                ApiResult.NoConnection, ApiResult.Timeout -> RemoteCheck.NO_CONNECTION
                else -> RemoteCheck.UNAVAILABLE
            }
        }
    }

    private suspend fun adopt(owner: String, remote: TrainingSessionDto) {
        if (remote.status != TrainingSessionDto.STATUS_ACTIVE) return
        val sport = TrainingSport.fromWire(remote.sport) ?: return
        val startedAt = runCatching { Instant.parse(remote.startedAt) }.getOrNull() ?: return
        storage(Unit) {
            transactions.run {
                val sessions = dao.sessions(owner)
                val known = sessions.any { it.sessionId == remote.sessionId }
                if (!known && sessions.none { codec.toLocalSession(it).open }) {
                    val details = TrainingSessionDetails(sport, remote.planId, startedAt, endedAt = null, officialDurationSeconds = null)
                    dao.insertSession(
                        TrainingSessionEntity(remote.sessionId, owner, SyncState.SYNCED.name, null, startedAt.toEpochMilli(), codec.encodeDetails(details))
                    )
                }
            }
        }
    }

    private fun command(owner: String, sessionId: String, type: CommandType, payload: ByteArray) = TrainingCommandEntity(
        commandId = newId(),
        ownerKey = owner,
        sessionId = sessionId,
        type = type.name,
        createdAtMs = clock.now().toEpochMilli(),
        payload = payload,
    )

    /** Errores de Room o del Keystore se informan como falla de almacenamiento, sin detalles. */
    private suspend fun <T> storage(onFailure: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        onFailure
    }
}
