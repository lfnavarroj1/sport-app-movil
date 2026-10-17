package com.lfnj.sport_app.features.training.session.data.local

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/**
 * Sesión de entrenamiento registrada en el dispositivo (HU032). En claro solo quedan
 * identificadores y metadatos técnicos para ordenar y sincronizar (ADR-029 offline §5); deporte,
 * plan e instantes viajan en [payload], cifrado con Android Keystore AES-GCM.
 */
@Entity(tableName = "training_session", indices = [Index("owner_key")])
class TrainingSessionEntity(
    @PrimaryKey @ColumnInfo(name = "session_id") val sessionId: String,
    /** Partición por identidad técnica: huella del `user_id` ([com.lfnj.sport_app.features.training.session.model.TrainingOwner]). */
    @ColumnInfo(name = "owner_key") val ownerKey: String,
    @ColumnInfo(name = "sync_state") val syncState: String,
    @ColumnInfo(name = "failure_reason") val failureReason: String?,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
) {
    fun copy(
        syncState: String = this.syncState,
        failureReason: String? = this.failureReason,
        payload: ByteArray = this.payload,
    ) = TrainingSessionEntity(sessionId, ownerKey, syncState, failureReason, createdAtMs, payload)
}

/**
 * Comando pendiente de una sesión (`START` o `FINISH`) en orden de llegada ([seq]). Conserva su
 * propia clave de idempotencia ([commandId]) y su cuerpo normalizado cifrado. Nunca guarda tokens.
 */
@Entity(
    tableName = "training_command",
    indices = [Index(value = ["command_id"], unique = true), Index("owner_key"), Index("session_id")],
)
class TrainingCommandEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    @ColumnInfo(name = "command_id") val commandId: String,
    @ColumnInfo(name = "owner_key") val ownerKey: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "attempts") val attempts: Int = 0,
    @ColumnInfo(name = "last_attempt_at_ms") val lastAttemptAtMs: Long? = null,
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
)

/** Acceso a la fuente local. Todas las consultas de negocio filtran por `owner_key`. */
@Dao
interface TrainingSessionDao {
    @Query("SELECT * FROM training_session WHERE owner_key = :owner ORDER BY created_at_ms DESC")
    fun observeSessions(owner: String): Flow<List<TrainingSessionEntity>>

    @Query("SELECT * FROM training_session WHERE owner_key = :owner ORDER BY created_at_ms DESC")
    suspend fun sessions(owner: String): List<TrainingSessionEntity>

    @Query("SELECT * FROM training_session WHERE owner_key = :owner AND session_id = :sessionId")
    suspend fun session(owner: String, sessionId: String): TrainingSessionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(session: TrainingSessionEntity)

    @Update
    suspend fun updateSession(session: TrainingSessionEntity)

    @Query("DELETE FROM training_session WHERE owner_key = :owner AND session_id = :sessionId")
    suspend fun deleteSession(owner: String, sessionId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCommand(command: TrainingCommandEntity)

    @Query("SELECT * FROM training_command WHERE owner_key = :owner ORDER BY seq ASC LIMIT 1")
    suspend fun nextCommand(owner: String): TrainingCommandEntity?

    @Query("SELECT COUNT(*) FROM training_command WHERE owner_key = :owner")
    suspend fun countCommands(owner: String): Int

    @Query("SELECT COUNT(*) FROM training_command WHERE owner_key = :owner AND session_id = :sessionId")
    suspend fun countCommands(owner: String, sessionId: String): Int

    @Query("UPDATE training_command SET attempts = attempts + 1, last_attempt_at_ms = :now WHERE seq = :seq")
    suspend fun markAttempt(seq: Long, now: Long)

    @Query("DELETE FROM training_command WHERE seq = :seq")
    suspend fun deleteCommand(seq: Long)

    @Query("DELETE FROM training_command WHERE owner_key = :owner AND session_id = :sessionId")
    suspend fun deleteCommands(owner: String, sessionId: String)

    /** Comandos de cualquier partición creados antes de [threshold] (vencimiento a 30 días). */
    @Query("SELECT * FROM training_command WHERE created_at_ms < :threshold ORDER BY seq ASC")
    suspend fun commandsCreatedBefore(threshold: Long): List<TrainingCommandEntity>
}

/** Ejecuta un bloque como una sola transacción de la fuente local. */
interface TransactionRunner {
    suspend fun <R> run(block: suspend () -> R): R
}

/**
 * Base local de HU032. Archivo excluido de la copia de seguridad y de la transferencia entre
 * dispositivos (`res/xml/backup_rules.xml` y `data_extraction_rules.xml`). Las versiones futuras
 * requieren migraciones explícitas: nunca se borra el contenido de forma destructiva.
 */
@Database(entities = [TrainingSessionEntity::class, TrainingCommandEntity::class], version = 1, exportSchema = true)
abstract class TrainingDatabase : RoomDatabase() {
    abstract fun trainingSessionDao(): TrainingSessionDao

    val transactions: TransactionRunner = object : TransactionRunner {
        override suspend fun <R> run(block: suspend () -> R): R = withTransaction(block)
    }

    companion object {
        const val NAME = "sportapp_training.db"

        fun create(context: Context): TrainingDatabase =
            Room.databaseBuilder(context.applicationContext, TrainingDatabase::class.java, NAME).build()
    }
}
