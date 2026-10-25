package com.lfnj.sport_app.features.training.dashboard.data.local

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Último tablero confirmado para un deportista y una opción del selector de periodo (HU033). En
 * claro solo quedan la partición y la opción; el periodo, los agregados, los indicadores, las
 * fuentes y `last_synced_at` viajan en [payload], cifrado con Android Keystore AES-GCM (ADR-043 §5).
 */
@Entity(tableName = "training_dashboard_cache", primaryKeys = ["owner_key", "period_key"])
class TrainingDashboardCacheEntity(
    /** Huella del `user_id` ([com.lfnj.sport_app.features.training.session.model.TrainingOwner]). */
    @ColumnInfo(name = "owner_key") val ownerKey: String,
    /** Opción del selector ([com.lfnj.sport_app.features.training.dashboard.model.DashboardRange.cacheKey]). */
    @ColumnInfo(name = "period_key") val periodKey: String,
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
)

/** Como máximo una fila por deportista y opción: cada consulta confirmada reemplaza la anterior. */
@Dao
interface TrainingDashboardCacheDao {
    @Query("SELECT * FROM training_dashboard_cache WHERE owner_key = :owner AND period_key = :period")
    fun observe(owner: String, period: String): Flow<TrainingDashboardCacheEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replace(entry: TrainingDashboardCacheEntity)

    @Query("DELETE FROM training_dashboard_cache WHERE owner_key = :owner")
    suspend fun delete(owner: String)

    @Query("DELETE FROM training_dashboard_cache WHERE owner_key != :owner")
    suspend fun deleteAllExcept(owner: String)
}

/**
 * Caché de lectura de HU033, separada de las bases de HU032, HU034 y HU035. Archivo excluido de la
 * copia de seguridad y de la transferencia entre dispositivos (`res/xml/backup_rules.xml` y
 * `data_extraction_rules.xml`). Sin cola de comandos ni WorkManager. Las versiones futuras requieren
 * migraciones explícitas.
 */
@Database(entities = [TrainingDashboardCacheEntity::class], version = 1, exportSchema = true)
abstract class TrainingDashboardDatabase : RoomDatabase() {
    abstract fun trainingDashboardCacheDao(): TrainingDashboardCacheDao

    companion object {
        const val NAME = "sportapp_training_dashboard.db"

        fun create(context: Context): TrainingDashboardDatabase =
            Room.databaseBuilder(context.applicationContext, TrainingDashboardDatabase::class.java, NAME).build()
    }
}
