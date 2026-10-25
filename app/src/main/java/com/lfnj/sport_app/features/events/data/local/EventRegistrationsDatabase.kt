package com.lfnj.sport_app.features.events.data.local

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
 * Últimas «Mis inscripciones» confirmadas para un deportista (HU041). En claro solo queda la
 * partición; las inscripciones, el cursor y `last_synced_at` viajan en [payload], cifrado con
 * Android Keystore AES-GCM (ADR-032 §9).
 */
@Entity(tableName = "event_registrations_cache")
class EventRegistrationsCacheEntity(
    /** Huella del `user_id` ([com.lfnj.sport_app.features.events.model.EventRegistrationsOwner]). */
    @PrimaryKey @ColumnInfo(name = "owner_key") val ownerKey: String,
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
)

/** Una fila por deportista: cada consulta confirmada reemplaza la lista anterior. */
@Dao
interface EventRegistrationsCacheDao {
    @Query("SELECT * FROM event_registrations_cache WHERE owner_key = :owner")
    fun observe(owner: String): Flow<EventRegistrationsCacheEntity?>

    @Query("SELECT * FROM event_registrations_cache WHERE owner_key = :owner")
    suspend fun read(owner: String): EventRegistrationsCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replace(entry: EventRegistrationsCacheEntity)

    @Query("DELETE FROM event_registrations_cache WHERE owner_key = :owner")
    suspend fun delete(owner: String)

    @Query("DELETE FROM event_registrations_cache WHERE owner_key != :owner")
    suspend fun deleteAllExcept(owner: String)
}

/**
 * Caché de lectura de HU041, separada de las demás bases. Archivo excluido de la copia de
 * seguridad y de la transferencia entre dispositivos (`res/xml/backup_rules.xml` y
 * `data_extraction_rules.xml`). Las versiones futuras requieren migraciones explícitas.
 */
@Database(entities = [EventRegistrationsCacheEntity::class], version = 1, exportSchema = true)
abstract class EventRegistrationsDatabase : RoomDatabase() {
    abstract fun eventRegistrationsCacheDao(): EventRegistrationsCacheDao

    companion object {
        const val NAME = "sportapp_event_registrations.db"

        fun create(context: Context): EventRegistrationsDatabase =
            Room.databaseBuilder(context.applicationContext, EventRegistrationsDatabase::class.java, NAME).build()
    }
}
