package com.lfnj.sport_app.features.recommendations.data.local

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
 * Última lista de plantillas sugeridas confirmada para un deportista (HU028). En claro solo queda
 * la partición; las plantillas, el contexto faltante y `last_synced_at` viajan en [payload],
 * cifrado con Android Keystore AES-GCM (ADR-041 §5, patrón ADR-031).
 */
@Entity(tableName = "plan_suggestions_cache")
class PlanSuggestionsCacheEntity(
    /** Huella del `user_id` ([com.lfnj.sport_app.features.recommendations.model.PlanSuggestionsOwner]). */
    @PrimaryKey @ColumnInfo(name = "owner_key") val ownerKey: String,
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
)

/** Una fila por deportista: cada consulta confirmada reemplaza la lista anterior. */
@Dao
interface PlanSuggestionsCacheDao {
    @Query("SELECT * FROM plan_suggestions_cache WHERE owner_key = :owner")
    fun observe(owner: String): Flow<PlanSuggestionsCacheEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replace(entry: PlanSuggestionsCacheEntity)

    @Query("DELETE FROM plan_suggestions_cache WHERE owner_key = :owner")
    suspend fun delete(owner: String)

    @Query("DELETE FROM plan_suggestions_cache WHERE owner_key != :owner")
    suspend fun deleteAllExcept(owner: String)
}

/**
 * Caché de lectura de HU028, separada de las bases de HU032, HU034 y HU035. Archivo excluido de la
 * copia de seguridad y de la transferencia entre dispositivos (`res/xml/backup_rules.xml` y
 * `data_extraction_rules.xml`). Las versiones futuras requieren migraciones explícitas.
 */
@Database(entities = [PlanSuggestionsCacheEntity::class], version = 1, exportSchema = true)
abstract class PlanSuggestionsDatabase : RoomDatabase() {
    abstract fun planSuggestionsCacheDao(): PlanSuggestionsCacheDao

    companion object {
        const val NAME = "sportapp_plan_suggestions.db"

        fun create(context: Context): PlanSuggestionsDatabase =
            Room.databaseBuilder(context.applicationContext, PlanSuggestionsDatabase::class.java, NAME).build()
    }
}
