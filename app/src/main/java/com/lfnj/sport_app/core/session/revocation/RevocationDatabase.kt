package com.lfnj.sport_app.core.session.revocation

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

/**
 * Revocación de sesión pendiente (HU004 CA6, ADR-026 «Aplicación a HU004»). Solo contiene lo que
 * exige el paquete: identificador local, credencial renovable cifrada ([payload], AES-GCM con una
 * clave propia del Keystore), vencimiento de la credencial, clave de idempotencia, intentos y
 * próxima ejecución. Nunca guarda correo, nombre, perfil, capacidades ni access token.
 */
@Entity(tableName = "revocation_entry", indices = [Index(value = ["idempotency_key"], unique = true)])
class RevocationEntryEntity(
    @PrimaryKey @ColumnInfo(name = "entry_id") val entryId: String,
    /** `Idempotency-Key` que se repite en cada reintento de la misma revocación. */
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String,
    /** Vencimiento de la credencial (`refresh_expires_in`); al llegar se descarta sin enviarla. */
    @ColumnInfo(name = "expires_at_ms") val expiresAtMs: Long,
    @ColumnInfo(name = "attempts") val attempts: Int = 0,
    @ColumnInfo(name = "next_attempt_at_ms") val nextAttemptAtMs: Long,
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
)

@Dao
interface RevocationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: RevocationEntryEntity)

    @Query("SELECT * FROM revocation_entry ORDER BY next_attempt_at_ms ASC")
    suspend fun entries(): List<RevocationEntryEntity>

    @Query("SELECT COUNT(*) FROM revocation_entry")
    suspend fun count(): Int

    @Query("UPDATE revocation_entry SET attempts = attempts + 1, next_attempt_at_ms = :nextAttemptAt WHERE entry_id = :entryId")
    suspend fun markAttempt(entryId: String, nextAttemptAt: Long)

    @Query("DELETE FROM revocation_entry WHERE entry_id = :entryId")
    suspend fun delete(entryId: String)

    /** Descarta sin enviar las entradas cuya credencial ya venció; devuelve cuántas eliminó. */
    @Query("DELETE FROM revocation_entry WHERE expires_at_ms <= :now")
    suspend fun deleteExpired(now: Long): Int
}

/**
 * Cola de revocación de HU004, separada del almacén de sesión de HU003: el flujo de restauración
 * no la conoce ni la puede leer, por lo que una credencial pendiente nunca reabre la sesión.
 * Archivo excluido de la copia de seguridad y de la transferencia entre dispositivos.
 */
@Database(entities = [RevocationEntryEntity::class], version = 1, exportSchema = true)
abstract class RevocationDatabase : RoomDatabase() {
    abstract fun revocationDao(): RevocationDao

    companion object {
        const val NAME = "sportapp_revocation.db"

        fun create(context: Context): RevocationDatabase =
            Room.databaseBuilder(context.applicationContext, RevocationDatabase::class.java, NAME).build()
    }
}
