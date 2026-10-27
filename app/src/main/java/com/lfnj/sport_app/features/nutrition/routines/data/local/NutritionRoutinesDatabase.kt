package com.lfnj.sport_app.features.nutrition.routines.data.local

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
 * Última lista de rutinas sugeridas confirmada para un deportista (HU030). En claro solo queda la
 * partición; las rutinas (con sus alérgenos), el contexto faltante, los avisos y `last_synced_at`
 * viajan en [payload], cifrado con Android Keystore AES-GCM (ADR-041 §5, patrón ADR-031).
 */
@Entity(tableName = "nutrition_routines_cache")
class NutritionRoutinesCacheEntity(
    /** Huella del `user_id` ([com.lfnj.sport_app.features.nutrition.routines.model.NutritionRoutinesOwner]). */
    @PrimaryKey @ColumnInfo(name = "owner_key") val ownerKey: String,
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
)

/** Una fila por deportista: cada consulta confirmada reemplaza la lista anterior. */
@Dao
interface NutritionRoutinesCacheDao {
    @Query("SELECT * FROM nutrition_routines_cache WHERE owner_key = :owner")
    fun observe(owner: String): Flow<NutritionRoutinesCacheEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replace(entry: NutritionRoutinesCacheEntity)

    @Query("DELETE FROM nutrition_routines_cache WHERE owner_key = :owner")
    suspend fun delete(owner: String)

    @Query("DELETE FROM nutrition_routines_cache WHERE owner_key != :owner")
    suspend fun deleteAllExcept(owner: String)
}

/**
 * Caché de lectura de HU030, separada de las bases de HU043 y HU038. Archivo excluido de la copia
 * de seguridad y de la transferencia entre dispositivos (`res/xml/backup_rules.xml` y
 * `data_extraction_rules.xml`). Las versiones futuras requieren migraciones explícitas.
 */
@Database(entities = [NutritionRoutinesCacheEntity::class], version = 1, exportSchema = true)
abstract class NutritionRoutinesDatabase : RoomDatabase() {
    abstract fun nutritionRoutinesCacheDao(): NutritionRoutinesCacheDao

    companion object {
        const val NAME = "sportapp_nutrition_routines.db"

        fun create(context: Context): NutritionRoutinesDatabase =
            Room.databaseBuilder(context.applicationContext, NutritionRoutinesDatabase::class.java, NAME).build()
    }
}
