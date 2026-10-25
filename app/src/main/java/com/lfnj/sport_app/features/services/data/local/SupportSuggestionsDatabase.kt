package com.lfnj.sport_app.features.services.data.local

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
 * Última lista de servicios de acompañamiento sugeridos confirmada para un deportista (HU029). En
 * claro solo queda la partición; los servicios, las fuentes y `last_synced_at` viajan en [payload],
 * cifrado con Android Keystore AES-GCM (ADR-041 §5, patrón ADR-031).
 */
@Entity(tableName = "support_suggestions_cache")
class SupportSuggestionsCacheEntity(
    /** Huella del `user_id` ([com.lfnj.sport_app.features.services.model.SupportSuggestionsOwner]). */
    @PrimaryKey @ColumnInfo(name = "owner_key") val ownerKey: String,
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
)

/** Una fila por deportista: cada consulta confirmada reemplaza la lista anterior. */
@Dao
interface SupportSuggestionsCacheDao {
    @Query("SELECT * FROM support_suggestions_cache WHERE owner_key = :owner")
    fun observe(owner: String): Flow<SupportSuggestionsCacheEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replace(entry: SupportSuggestionsCacheEntity)

    @Query("DELETE FROM support_suggestions_cache WHERE owner_key = :owner")
    suspend fun delete(owner: String)

    @Query("DELETE FROM support_suggestions_cache WHERE owner_key != :owner")
    suspend fun deleteAllExcept(owner: String)
}

/**
 * Caché de lectura de HU029, separada de las demás bases (incluida la de HU040). Archivo excluido
 * de la copia de seguridad y de la transferencia entre dispositivos (`res/xml/backup_rules.xml` y
 * `data_extraction_rules.xml`). Las versiones futuras requieren migraciones explícitas.
 */
@Database(entities = [SupportSuggestionsCacheEntity::class], version = 1, exportSchema = true)
abstract class SupportSuggestionsDatabase : RoomDatabase() {
    abstract fun supportSuggestionsCacheDao(): SupportSuggestionsCacheDao

    companion object {
        const val NAME = "sportapp_support_suggestions.db"

        fun create(context: Context): SupportSuggestionsDatabase =
            Room.databaseBuilder(context.applicationContext, SupportSuggestionsDatabase::class.java, NAME).build()
    }
}
