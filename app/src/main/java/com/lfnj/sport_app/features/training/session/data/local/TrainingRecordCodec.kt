package com.lfnj.sport_app.features.training.session.data.local

import com.lfnj.sport_app.core.security.TokenCipher
import com.lfnj.sport_app.features.training.session.data.FinishTrainingSessionRequestDto
import com.lfnj.sport_app.features.training.session.data.StartTrainingSessionRequestDto
import com.lfnj.sport_app.features.training.session.model.LocalTrainingSession
import com.lfnj.sport_app.features.training.session.model.SyncFailure
import com.lfnj.sport_app.features.training.session.model.SyncState
import com.lfnj.sport_app.features.training.session.model.TrainingSessionDetails
import com.lfnj.sport_app.features.training.session.model.TrainingSport
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.time.Instant

/** Tipo de comando de la cola de HU032; el fin depende siempre del inicio de la misma sesión. */
enum class CommandType { START, FINISH }

/** Comando descifrado y listo para enviar con su clave de idempotencia estable. */
sealed interface PendingCommand {
    val entity: TrainingCommandEntity

    data class Start(override val entity: TrainingCommandEntity, val body: StartTrainingSessionRequestDto) : PendingCommand
    data class Finish(override val entity: TrainingCommandEntity, val body: FinishTrainingSessionRequestDto) : PendingCommand
}

/** Contenido cifrado de [TrainingSessionEntity.payload]. Los instantes se guardan en segundos UTC. */
@Serializable
internal data class SessionPayload(
    val sport: String,
    @SerialName("plan_id") val planId: String? = null,
    @SerialName("started_at") val startedAt: Long,
    @SerialName("ended_at") val endedAt: Long? = null,
    @SerialName("official_duration_seconds") val officialDurationSeconds: Long? = null,
)

/**
 * Cifra y descifra los datos de negocio de HU032 con AES-GCM y una clave no exportable de Android
 * Keystore (ADR-029 offline §5). Un registro que no puede descifrarse se informa como ilegible y
 * nunca se reemplaza por valores inventados.
 */
class TrainingRecordCodec(private val cipher: TokenCipher, private val json: Json) {

    fun encodeDetails(details: TrainingSessionDetails): ByteArray = encrypt(
        json.encodeToString(
            SessionPayload.serializer(),
            SessionPayload(
                sport = details.sport.wire,
                planId = details.planId,
                startedAt = details.startedAt.epochSecond,
                endedAt = details.endedAt?.epochSecond,
                officialDurationSeconds = details.officialDurationSeconds,
            ),
        )
    )

    /** `null` si el registro no puede descifrarse o no corresponde al formato esperado. */
    fun decodeDetails(entity: TrainingSessionEntity): TrainingSessionDetails? = readable {
        val payload = json.decodeFromString(SessionPayload.serializer(), decrypt(entity.payload))
        TrainingSessionDetails(
            sport = TrainingSport.fromWire(payload.sport) ?: return@readable null,
            planId = payload.planId,
            startedAt = Instant.ofEpochSecond(payload.startedAt),
            endedAt = payload.endedAt?.let(Instant::ofEpochSecond),
            officialDurationSeconds = payload.officialDurationSeconds,
        )
    }

    fun toLocalSession(entity: TrainingSessionEntity): LocalTrainingSession {
        val details = decodeDetails(entity)
        return if (details == null) {
            LocalTrainingSession(entity.sessionId, SyncState.SYNC_FAILED, SyncFailure.UNREADABLE, null)
        } else {
            LocalTrainingSession(
                sessionId = entity.sessionId,
                syncState = runCatching { SyncState.valueOf(entity.syncState) }.getOrDefault(SyncState.SYNC_FAILED),
                failure = SyncFailure.fromWire(entity.failureReason),
                details = details,
            )
        }
    }

    fun encodeStart(body: StartTrainingSessionRequestDto): ByteArray =
        encrypt(json.encodeToString(StartTrainingSessionRequestDto.serializer(), body))

    fun encodeFinish(body: FinishTrainingSessionRequestDto): ByteArray =
        encrypt(json.encodeToString(FinishTrainingSessionRequestDto.serializer(), body))

    /** `null` si el comando no puede descifrarse o su tipo es desconocido. */
    fun decodeCommand(entity: TrainingCommandEntity): PendingCommand? = readable {
        val text = decrypt(entity.payload)
        when (runCatching { CommandType.valueOf(entity.type) }.getOrNull()) {
            CommandType.START -> PendingCommand.Start(entity, json.decodeFromString(StartTrainingSessionRequestDto.serializer(), text))
            CommandType.FINISH -> PendingCommand.Finish(entity, json.decodeFromString(FinishTrainingSessionRequestDto.serializer(), text))
            null -> null
        }
    }

    private fun encrypt(text: String): ByteArray = cipher.encrypt(text.toByteArray(Charsets.UTF_8))

    private fun decrypt(bytes: ByteArray): String = cipher.decrypt(bytes).toString(Charsets.UTF_8)

    companion object {
        /** Alias de la clave no exportable de Android Keystore para los registros de HU032. */
        const val KEY_ALIAS = "sportapp.training.v1"
    }

    private inline fun <T> readable(block: () -> T?): T? = try {
        block()
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }
}
