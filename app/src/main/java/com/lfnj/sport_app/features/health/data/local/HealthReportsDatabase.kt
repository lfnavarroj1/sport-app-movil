package com.lfnj.sport_app.features.health.data.local

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
 * Reporte de salud capturado en el dispositivo (HU042, ADR-037 §4 con la política de ADR-029).
 * Conserva su `report_id`, su `Idempotency-Key` y el cuerpo cifrado; nunca guarda tokens. En claro
 * solo quedan identificadores y metadatos técnicos para ordenar y reintentar: tipo, zona, severidad,
 * fechas y sesión viajan solo dentro de [payload].
 */
@Entity(
    tableName = "health_report",
    indices = [Index(value = ["idempotency_key"], unique = true), Index("owner_key")],
)
class HealthReportEntity(
    @PrimaryKey @ColumnInfo(name = "report_id") val reportId: String,
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
    fun failed(reason: String, field: String? = null, issue: String? = null) = HealthReportEntity(
        reportId, ownerKey, idempotencyKey, SYNC_FAILED, reason, field, issue, createdAtMs, attempts, lastAttemptAtMs, payload,
    )

    fun synced(payload: ByteArray) = HealthReportEntity(
        reportId, ownerKey, idempotencyKey, SYNCED, null, null, null, createdAtMs, attempts, lastAttemptAtMs, payload,
    )

    companion object {
        const val PENDING_SYNC = "PENDING_SYNC"
        const val SYNCED = "SYNCED"
        const val SYNC_FAILED = "SYNC_FAILED"
    }
}

/** Acceso a la fuente local de HU042. Todas las consultas de negocio filtran por `owner_key`. */
@Dao
interface HealthReportsDao {
    @Query("SELECT * FROM health_report WHERE owner_key = :owner ORDER BY created_at_ms DESC")
    fun observeReports(owner: String): Flow<List<HealthReportEntity>>

    @Query("SELECT * FROM health_report WHERE owner_key = :owner AND sync_state = 'PENDING_SYNC' ORDER BY created_at_ms ASC")
    suspend fun pendingReports(owner: String): List<HealthReportEntity>

    @Query("SELECT COUNT(*) FROM health_report WHERE owner_key = :owner AND sync_state = 'PENDING_SYNC'")
    suspend fun countPending(owner: String): Int

    @Query("SELECT * FROM health_report WHERE owner_key = :owner AND report_id = :reportId")
    suspend fun report(owner: String, reportId: String): HealthReportEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReport(report: HealthReportEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceReport(report: HealthReportEntity)

    @Query("UPDATE health_report SET attempts = attempts + 1, last_attempt_at_ms = :now WHERE report_id = :reportId")
    suspend fun markAttempt(reportId: String, now: Long)

    @Query("DELETE FROM health_report WHERE owner_key = :owner AND report_id = :reportId")
    suspend fun deleteReport(owner: String, reportId: String)

    /** Retira los reportes ya resueltos (confirmados o fallidos) de una partición; nunca los pendientes. */
    @Query("DELETE FROM health_report WHERE owner_key = :owner AND sync_state != 'PENDING_SYNC'")
    suspend fun deleteSettled(owner: String)

    /** Pendientes de cualquier partición creados antes de [threshold] (vencimiento a 30 días). */
    @Query("SELECT * FROM health_report WHERE sync_state = 'PENDING_SYNC' AND created_at_ms < :threshold")
    suspend fun pendingCreatedBefore(threshold: Long): List<HealthReportEntity>
}

/**
 * Base local de HU042, separada de las demás, con su propia clave de Keystore. Archivo excluido de
 * la copia de seguridad y de la transferencia entre dispositivos. Las versiones futuras requieren
 * migraciones explícitas: nunca se borra el contenido de forma destructiva.
 */
@Database(entities = [HealthReportEntity::class], version = 1, exportSchema = true)
abstract class HealthReportsDatabase : RoomDatabase() {
    abstract fun healthReportsDao(): HealthReportsDao

    val transactions: TransactionRunner = object : TransactionRunner {
        override suspend fun <R> run(block: suspend () -> R): R = withTransaction(block)
    }

    companion object {
        const val NAME = "sportapp_health_reports.db"

        fun create(context: Context): HealthReportsDatabase =
            Room.databaseBuilder(context.applicationContext, HealthReportsDatabase::class.java, NAME).build()
    }
}
