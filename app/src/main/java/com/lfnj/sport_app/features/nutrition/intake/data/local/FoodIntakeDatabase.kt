package com.lfnj.sport_app.features.nutrition.intake.data.local

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
 * Último catálogo de alimentos confirmado para un deportista (HU043). En claro solo queda la
 * partición; alimentos, `ETag` y `last_synced_at` viajan en [payload], cifrado con Android Keystore.
 */
@Entity(tableName = "food_catalog_cache")
class FoodCatalogCacheEntity(
    @PrimaryKey @ColumnInfo(name = "owner_key") val ownerKey: String,
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
)

/**
 * Consumo capturado en el dispositivo (HU043, ADR-037 §5 con la política de ADR-029). Conserva su
 * `intake_id`, su `Idempotency-Key` y el cuerpo cifrado; nunca guarda tokens. En claro solo quedan
 * identificadores y metadatos técnicos para ordenar y reintentar: comida, fecha, alimentos y
 * cantidades viajan solo dentro de [payload].
 */
@Entity(
    tableName = "food_intake",
    indices = [Index(value = ["idempotency_key"], unique = true), Index("owner_key")],
)
class FoodIntakeEntity(
    @PrimaryKey @ColumnInfo(name = "intake_id") val intakeId: String,
    @ColumnInfo(name = "owner_key") val ownerKey: String,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String,
    @ColumnInfo(name = "sync_state") val syncState: String,
    @ColumnInfo(name = "failure_reason") val failureReason: String? = null,
    /** Campo y causa estables de un `422` (sin valores). */
    @ColumnInfo(name = "failure_field") val failureField: String? = null,
    @ColumnInfo(name = "failure_issue") val failureIssue: String? = null,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "attempts") val attempts: Int = 0,
    @ColumnInfo(name = "last_attempt_at_ms") val lastAttemptAtMs: Long? = null,
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
) {
    fun failed(reason: String, field: String? = null, issue: String? = null) = FoodIntakeEntity(
        intakeId, ownerKey, idempotencyKey, SYNC_FAILED, reason, field, issue, createdAtMs, attempts, lastAttemptAtMs, payload,
    )

    fun synced(payload: ByteArray) = FoodIntakeEntity(
        intakeId, ownerKey, idempotencyKey, SYNCED, null, null, null, createdAtMs, attempts, lastAttemptAtMs, payload,
    )

    companion object {
        const val PENDING_SYNC = "PENDING_SYNC"
        const val SYNCED = "SYNCED"
        const val SYNC_FAILED = "SYNC_FAILED"
    }
}

/** Acceso a la fuente local de HU043. Todas las consultas de negocio filtran por `owner_key`. */
@Dao
interface FoodIntakeDao {
    @Query("SELECT * FROM food_catalog_cache WHERE owner_key = :owner")
    fun observeCatalog(owner: String): Flow<FoodCatalogCacheEntity?>

    @Query("SELECT * FROM food_catalog_cache WHERE owner_key = :owner")
    suspend fun catalog(owner: String): FoodCatalogCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceCatalog(entry: FoodCatalogCacheEntity)

    @Query("DELETE FROM food_catalog_cache WHERE owner_key = :owner")
    suspend fun deleteCatalog(owner: String)

    @Query("SELECT * FROM food_intake WHERE owner_key = :owner ORDER BY created_at_ms DESC")
    fun observeIntakes(owner: String): Flow<List<FoodIntakeEntity>>

    @Query("SELECT * FROM food_intake WHERE owner_key = :owner AND sync_state = 'PENDING_SYNC' ORDER BY created_at_ms ASC")
    suspend fun pendingIntakes(owner: String): List<FoodIntakeEntity>

    @Query("SELECT COUNT(*) FROM food_intake WHERE owner_key = :owner AND sync_state = 'PENDING_SYNC'")
    suspend fun countPending(owner: String): Int

    @Query("SELECT * FROM food_intake WHERE owner_key = :owner AND intake_id = :intakeId")
    suspend fun intake(owner: String, intakeId: String): FoodIntakeEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertIntake(intake: FoodIntakeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceIntake(intake: FoodIntakeEntity)

    @Query("UPDATE food_intake SET attempts = attempts + 1, last_attempt_at_ms = :now WHERE intake_id = :intakeId")
    suspend fun markAttempt(intakeId: String, now: Long)

    @Query("DELETE FROM food_intake WHERE owner_key = :owner AND intake_id = :intakeId")
    suspend fun deleteIntake(owner: String, intakeId: String)

    /** Retira los consumos ya resueltos (confirmados o fallidos) de una partición; nunca los pendientes. */
    @Query("DELETE FROM food_intake WHERE owner_key = :owner AND sync_state != 'PENDING_SYNC'")
    suspend fun deleteSettled(owner: String)

    /** Pendientes de cualquier partición creados antes de [threshold] (vencimiento a 30 días). */
    @Query("SELECT * FROM food_intake WHERE sync_state = 'PENDING_SYNC' AND created_at_ms < :threshold")
    suspend fun pendingCreatedBefore(threshold: Long): List<FoodIntakeEntity>
}

/**
 * Base local de HU043, separada de las demás, con su propia clave de Keystore. Archivo excluido de
 * la copia de seguridad y de la transferencia entre dispositivos. Las versiones futuras requieren
 * migraciones explícitas: nunca se borra el contenido de forma destructiva.
 */
@Database(entities = [FoodCatalogCacheEntity::class, FoodIntakeEntity::class], version = 1, exportSchema = true)
abstract class FoodIntakeDatabase : RoomDatabase() {
    abstract fun foodIntakeDao(): FoodIntakeDao

    val transactions: TransactionRunner = object : TransactionRunner {
        override suspend fun <R> run(block: suspend () -> R): R = withTransaction(block)
    }

    companion object {
        const val NAME = "sportapp_food_intakes.db"

        fun create(context: Context): FoodIntakeDatabase =
            Room.databaseBuilder(context.applicationContext, FoodIntakeDatabase::class.java, NAME).build()
    }
}
