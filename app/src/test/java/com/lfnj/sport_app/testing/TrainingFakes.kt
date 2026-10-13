package com.lfnj.sport_app.testing

import com.lfnj.sport_app.core.security.TokenCipher
import com.lfnj.sport_app.features.training.session.data.TrainingClock
import com.lfnj.sport_app.features.training.session.data.TrainingSyncScheduler
import com.lfnj.sport_app.features.training.session.data.local.TrainingCommandEntity
import com.lfnj.sport_app.features.training.session.data.local.TrainingSessionDao
import com.lfnj.sport_app.features.training.session.data.local.TrainingSessionEntity
import com.lfnj.sport_app.features.training.session.data.local.TransactionRunner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.GeneralSecurityException
import java.time.Instant

/** Datos sintéticos de HU032 alineados con los ejemplos de ADR-029. */
object TrainingFixtures {
    const val USER_ID = "7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b"
    const val OTHER_USER_ID = "0f0e0d0c-0b0a-4908-8706-050403020100"
    val START: Instant = Instant.parse("2026-10-12T12:30:00Z")

    fun sessionBody(
        sessionId: String,
        status: String = "ACTIVE",
        version: Int = if (status == "ACTIVE") 1 else 2,
        sport: String = "running",
        startedAt: String = "2026-10-12T12:30:00Z",
        endedAt: String? = null,
        durationSeconds: Long? = null,
    ): String {
        val end = endedAt?.let { ""","ended_at":"$it"""" } ?: ""
        val duration = durationSeconds?.let { ""","duration_seconds":$it""" } ?: ""
        return """{"session_id":"$sessionId","athlete_id":"$USER_ID","plan_id":null,"sport":"$sport",""" +
            """"started_at":"$startedAt"$end$duration,"status":"$status","version":$version,""" +
            """"created_at":"2026-10-12T12:30:01Z","updated_at":"2026-10-12T12:30:01Z"}"""
    }

    fun validationError(vararg issues: String): String =
        """{"code":"validation_error","message":"mensaje","correlation_id":"8c1f2d3e-0000-4000-8000-000000000002",""" +
            """"details":[${issues.joinToString(",") { """{"field":"started_at","issue":"$it"}""" }}]}"""
}

/** Reloj controlado: pared en UTC y monotónico independientes, como en el dispositivo. */
class FakeTrainingClock(var instant: Instant = TrainingFixtures.START, var elapsed: Long = 50_000L) : TrainingClock {
    override fun now(): Instant = instant
    override fun elapsedRealtimeMillis(): Long = elapsed

    fun advanceSeconds(seconds: Long) {
        instant = instant.plusSeconds(seconds)
        elapsed += seconds * 1000
    }
}

/**
 * Cifrado reversible para pruebas JVM: invierte los bytes y antepone una marca, de modo que el
 * texto en claro nunca aparece en el almacenamiento simulado. El AES-GCM real se prueba en el dispositivo.
 */
class FakeCipher : TokenCipher {
    var broken = false

    override fun encrypt(plaintext: ByteArray): ByteArray = MARK + plaintext.reversedArray()

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        if (broken || ciphertext.size < MARK.size || !ciphertext.copyOfRange(0, MARK.size).contentEquals(MARK)) {
            throw GeneralSecurityException("dato alterado")
        }
        return ciphertext.copyOfRange(MARK.size, ciphertext.size).reversedArray()
    }

    private companion object {
        val MARK = byteArrayOf(0x7f, 0x01)
    }
}

class FakeScheduler : TrainingSyncScheduler {
    val scheduled = mutableListOf<String>()
    var cancellations = 0

    override fun schedule(owner: String) {
        scheduled += owner
    }

    override fun cancelAll() {
        cancellations++
    }
}

/** Serializa las transacciones como SQLite; no hay reversión parcial porque los bloques no fallan a medias. */
class SerialTransactionRunner : TransactionRunner {
    private val mutex = Mutex()
    override suspend fun <R> run(block: suspend () -> R): R = mutex.withLock { block() }
}

/** Fuente local en memoria con la misma semántica de consultas que el DAO de Room. */
class InMemoryTrainingDao : TrainingSessionDao {
    val sessions = linkedMapOf<String, TrainingSessionEntity>()
    val commands = mutableListOf<TrainingCommandEntity>()
    private var nextSeq = 1L
    private val changes = MutableStateFlow(0)

    private fun changed() {
        changes.value++
    }

    override fun observeSessions(owner: String): Flow<List<TrainingSessionEntity>> = changes.map { sessionsOf(owner) }

    override suspend fun sessions(owner: String): List<TrainingSessionEntity> = sessionsOf(owner)

    private fun sessionsOf(owner: String) = sessions.values.filter { it.ownerKey == owner }.sortedByDescending { it.createdAtMs }

    override suspend fun session(owner: String, sessionId: String): TrainingSessionEntity? =
        sessions[sessionId]?.takeIf { it.ownerKey == owner }

    override suspend fun insertSession(session: TrainingSessionEntity) {
        check(session.sessionId !in sessions) { "UNIQUE constraint failed" }
        sessions[session.sessionId] = session
        changed()
    }

    override suspend fun updateSession(session: TrainingSessionEntity) {
        if (session.sessionId in sessions) sessions[session.sessionId] = session
        changed()
    }

    override suspend fun deleteSession(owner: String, sessionId: String) {
        if (sessions[sessionId]?.ownerKey == owner) sessions.remove(sessionId)
        changed()
    }

    override suspend fun insertCommand(command: TrainingCommandEntity) {
        check(commands.none { it.commandId == command.commandId }) { "UNIQUE constraint failed" }
        commands += TrainingCommandEntity(
            nextSeq++, command.commandId, command.ownerKey, command.sessionId, command.type,
            command.createdAtMs, command.attempts, command.lastAttemptAtMs, command.payload,
        )
    }

    override suspend fun nextCommand(owner: String): TrainingCommandEntity? =
        commands.filter { it.ownerKey == owner }.minByOrNull { it.seq }

    override suspend fun countCommands(owner: String): Int = commands.count { it.ownerKey == owner }

    override suspend fun countCommands(owner: String, sessionId: String): Int =
        commands.count { it.ownerKey == owner && it.sessionId == sessionId }

    override suspend fun markAttempt(seq: Long, now: Long) {
        val index = commands.indexOfFirst { it.seq == seq }
        if (index >= 0) {
            val c = commands[index]
            commands[index] = TrainingCommandEntity(c.seq, c.commandId, c.ownerKey, c.sessionId, c.type, c.createdAtMs, c.attempts + 1, now, c.payload)
        }
    }

    override suspend fun deleteCommand(seq: Long) {
        commands.removeAll { it.seq == seq }
    }

    override suspend fun deleteCommands(owner: String, sessionId: String) {
        commands.removeAll { it.ownerKey == owner && it.sessionId == sessionId }
    }

    override suspend fun commandsCreatedBefore(threshold: Long): List<TrainingCommandEntity> =
        commands.filter { it.createdAtMs < threshold }.sortedBy { it.seq }
}
