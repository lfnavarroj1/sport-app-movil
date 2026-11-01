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
 * Última lista de eventos sugeridos confirmada para un deportista (HU027). En claro solo queda la
 * partición; los eventos, sus lugares, tarifas, inscripciones, el contexto faltante y
 * `last_synced_at` viajan en [payload], cifrado con Android Keystore AES-GCM (ADR-041 §5, patrón ADR-031).
 */
@Entity(tableName = "event_suggestions_cache")
class EventSuggestionsCacheEntity(
    /** Huella del `user_id` ([com.lfnj.sport_app.features.events.model.EventSuggestionsOwner]). */
    @PrimaryKey @ColumnInfo(name = "owner_key") val ownerKey: String,
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
)

/** Una fila por deportista: cada consulta confirmada reemplaza la lista anterior. */
@Dao
interface EventSuggestionsCacheDao {
    @Query("SELECT * FROM event_suggestions_cache WHERE owner_key = :owner")
    fun observe(owner: String): Flow<EventSuggestionsCacheEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replace(entry: EventSuggestionsCacheEntity)

    @Query("DELETE FROM event_suggestions_cache WHERE owner_key = :owner")
    suspend fun delete(owner: String)

    @Query("DELETE FROM event_suggestions_cache WHERE owner_key != :owner")
    suspend fun deleteAllExcept(owner: String)
}

/**
 * Caché de lectura de HU027, separada de las bases de HU026, HU028, HU032–HU035 y HU041. Archivo
 * excluido de la copia de seguridad y de la transferencia entre dispositivos
 * (`res/xml/backup_rules.xml` y `data_extraction_rules.xml`). Las versiones futuras requieren
 * migraciones explícitas.
 */
@Database(entities = [EventSuggestionsCacheEntity::class], version = 1, exportSchema = true)
abstract class EventSuggestionsDatabase : RoomDatabase() {
    abstract fun eventSuggestionsCacheDao(): EventSuggestionsCacheDao

    companion object {
        const val NAME = "sportapp_event_suggestions.db"

        fun create(context: Context): EventSuggestionsDatabase =
            Room.databaseBuilder(context.applicationContext, EventSuggestionsDatabase::class.java, NAME).build()
    }
}
