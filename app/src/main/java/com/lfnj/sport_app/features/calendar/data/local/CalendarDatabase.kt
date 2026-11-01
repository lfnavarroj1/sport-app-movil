package com.lfnj.sport_app.features.calendar.data.local

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Último periodo del calendario confirmado para un deportista (HU035). En claro solo queda la
 * partición; el periodo, los elementos, las fuentes y `last_synced_at` viajan en [payload],
 * cifrado con Android Keystore AES-GCM (ADR-031, política offline §3).
 */
@Entity(tableName = "calendar_cache")
class CalendarCacheEntity(
    /** Huella del `user_id` ([com.lfnj.sport_app.features.calendar.model.CalendarOwner]). */
    @PrimaryKey @ColumnInfo(name = "owner_key") val ownerKey: String,
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
)

/** Una fila por deportista: cada consulta confirmada reemplaza el periodo anterior. */
@Dao
interface CalendarCacheDao {
    @Query("SELECT * FROM calendar_cache WHERE owner_key = :owner")
    fun observe(owner: String): Flow<CalendarCacheEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replace(entry: CalendarCacheEntity)

    @Query("DELETE FROM calendar_cache WHERE owner_key = :owner")
    suspend fun delete(owner: String)

    @Query("DELETE FROM calendar_cache WHERE owner_key != :owner")
    suspend fun deleteAllExcept(owner: String)
}

/**
 * Caché de lectura de HU035, separada de la base de HU032. Archivo excluido de la copia de
 * seguridad y de la transferencia entre dispositivos (`res/xml/backup_rules.xml` y
 * `data_extraction_rules.xml`). Las versiones futuras requieren migraciones explícitas.
 */
@Database(entities = [CalendarCacheEntity::class], version = 1, exportSchema = true)
abstract class CalendarDatabase : RoomDatabase() {
    abstract fun calendarCacheDao(): CalendarCacheDao

    companion object {
        const val NAME = "sportapp_calendar.db"

        fun create(context: Context): CalendarDatabase =
            Room.databaseBuilder(context.applicationContext, CalendarDatabase::class.java, NAME).build()
    }
}
