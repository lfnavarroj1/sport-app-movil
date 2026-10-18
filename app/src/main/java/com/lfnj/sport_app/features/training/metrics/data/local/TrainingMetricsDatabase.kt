package com.lfnj.sport_app.features.training.metrics.data.local

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
import androidx.room.withTransaction
import com.lfnj.sport_app.features.training.session.data.local.TransactionRunner
import kotlinx.coroutines.flow.Flow

/**
 * Último catálogo confirmado de un deportista (HU034). En claro solo queda la partición; métodos,
 * `ETag` y `last_synced_at` viajan en [payload], cifrado con Android Keystore AES-GCM (ADR-030 offline §1).
 */
@Entity(tableName = "metric_catalog_cache")
class MetricCatalogCacheEntity(
    @PrimaryKey @ColumnInfo(name = "owner_key") val ownerKey: String,
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
)

/** Última página confirmada del historial propio; resultados y entradas solo cifrados. */
@Entity(tableName = "metric_result_cache")
class MetricResultCacheEntity(
    @PrimaryKey @ColumnInfo(name = "owner_key") val ownerKey: String,
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
)

/**
 * Cálculo capturado y aún no confirmado (ADR-030 offline §2–§4). Conserva su `calculation_id`, su
 * `Idempotency-Key` y el cuerpo normalizado cifrado; nunca guarda tokens ni un resultado local. En
 * claro solo quedan identificadores y metadatos técnicos para ordenar y reintentar.
 */
@Entity(
    tableName = "metric_command",
    indices = [Index(value = ["idempotency_key"], unique = true), Index("owner_key")],
)
class MetricCommandEntity(
    @PrimaryKey @ColumnInfo(name = "calculation_id") val calculationId: String,
    @ColumnInfo(name = "owner_key") val ownerKey: String,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String,
    @ColumnInfo(name = "sync_state") val syncState: String,
    @ColumnInfo(name = "failure_reason") val failureReason: String? = null,
    /** Campo y causa estables de un `422` (sin valores), para señalar la entrada rechazada. */
    @ColumnInfo(name = "failure_field") val failureField: String? = null,
    @ColumnInfo(name = "failure_issue") val failureIssue: String? = null,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "attempts") val attempts: Int = 0,
    @ColumnInfo(name = "last_attempt_at_ms") val lastAttemptAtMs: Long? = null,
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
) {
    fun failed(reason: String, field: String? = null, issue: String? = null) = MetricCommandEntity(
        calculationId, ownerKey, idempotencyKey, SYNC_FAILED, reason, field, issue, createdAtMs, attempts, lastAttemptAtMs, payload,
    )

    companion object {
        const val PENDING_SYNC = "PENDING_SYNC"
        const val SYNC_FAILED = "SYNC_FAILED"
    }
}

/** Acceso a la fuente local de HU034. Todas las consultas de negocio filtran por `owner_key`. */
@Dao
interface TrainingMetricsDao {
    @Query("SELECT * FROM metric_catalog_cache WHERE owner_key = :owner")
    fun observeCatalog(owner: String): Flow<MetricCatalogCacheEntity?>

    @Query("SELECT * FROM metric_catalog_cache WHERE owner_key = :owner")
    suspend fun catalog(owner: String): MetricCatalogCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceCatalog(entry: MetricCatalogCacheEntity)

    @Query("SELECT * FROM metric_result_cache WHERE owner_key = :owner")
    fun observeResults(owner: String): Flow<MetricResultCacheEntity?>

    @Query("SELECT * FROM metric_result_cache WHERE owner_key = :owner")
    suspend fun results(owner: String): MetricResultCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceResults(entry: MetricResultCacheEntity)

    @Query("SELECT * FROM metric_command WHERE owner_key = :owner ORDER BY created_at_ms DESC")
    fun observeCommands(owner: String): Flow<List<MetricCommandEntity>>

    @Query("SELECT * FROM metric_command WHERE owner_key = :owner AND sync_state = 'PENDING_SYNC' ORDER BY created_at_ms ASC")
    suspend fun pendingCommands(owner: String): List<MetricCommandEntity>

    @Query("SELECT COUNT(*) FROM metric_command WHERE owner_key = :owner AND sync_state = 'PENDING_SYNC'")
    suspend fun countPending(owner: String): Int

    @Query("SELECT * FROM metric_command WHERE owner_key = :owner AND calculation_id = :calculationId")
    suspend fun command(owner: String, calculationId: String): MetricCommandEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCommand(command: MetricCommandEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceCommand(command: MetricCommandEntity)

    @Query("UPDATE metric_command SET attempts = attempts + 1, last_attempt_at_ms = :now WHERE calculation_id = :calculationId")
    suspend fun markAttempt(calculationId: String, now: Long)

    @Query("DELETE FROM metric_command WHERE owner_key = :owner AND calculation_id = :calculationId")
    suspend fun deleteCommand(owner: String, calculationId: String)

    /** Pendientes de cualquier partición creados antes de [threshold] (vencimiento a 30 días). */
    @Query("SELECT * FROM metric_command WHERE sync_state = 'PENDING_SYNC' AND created_at_ms < :threshold")
    suspend fun pendingCreatedBefore(threshold: Long): List<MetricCommandEntity>
}

/**
 * Base local de HU034, separada de las de HU032 y HU035, con su propia clave de Keystore. Archivo
 * excluido de la copia de seguridad y de la transferencia entre dispositivos. Las versiones futuras
 * requieren migraciones explícitas: nunca se borra el contenido de forma destructiva.
 */
@Database(
    entities = [MetricCatalogCacheEntity::class, MetricResultCacheEntity::class, MetricCommandEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class TrainingMetricsDatabase : RoomDatabase() {
    abstract fun trainingMetricsDao(): TrainingMetricsDao

    val transactions: TransactionRunner = object : TransactionRunner {
        override suspend fun <R> run(block: suspend () -> R): R = withTransaction(block)
    }

    companion object {
        const val NAME = "sportapp_training_metrics.db"

        fun create(context: Context): TrainingMetricsDatabase =
            Room.databaseBuilder(context.applicationContext, TrainingMetricsDatabase::class.java, NAME).build()
    }
}
