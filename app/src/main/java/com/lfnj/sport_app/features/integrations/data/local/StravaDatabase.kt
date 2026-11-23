package com.lfnj.sport_app.features.integrations.data.local

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
 * Último estado de vinculación con Strava confirmado para un deportista (HU031). En claro solo queda
 * la partición; el estado, los scopes, la última corrida y `synced_at` viajan en [payload], cifrado
 * con Android Keystore AES-GCM. Nunca contiene tokens, `code`, `state` ni el identificador del atleta.
 */
@Entity(tableName = "strava_integration_cache")
class StravaCacheEntity(
    /** Huella del `user_id` ([com.lfnj.sport_app.features.training.session.model.TrainingOwner]). */
    @PrimaryKey @ColumnInfo(name = "owner_key") val ownerKey: String,
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
)

/** Como máximo una fila por deportista: cada estado confirmado reemplaza el anterior. */
@Dao
interface StravaCacheDao {
    @Query("SELECT * FROM strava_integration_cache WHERE owner_key = :owner")
    fun observe(owner: String): Flow<StravaCacheEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replace(entry: StravaCacheEntity)

    @Query("DELETE FROM strava_integration_cache WHERE owner_key = :owner")
    suspend fun delete(owner: String)

    @Query("DELETE FROM strava_integration_cache WHERE owner_key != :owner")
    suspend fun deleteAllExcept(owner: String)
}

/**
 * Caché de lectura del estado de Strava (ADR-042 §13: sin cola offline, solo el estado con vigencia de
 * 24 h). Archivo excluido de la copia de seguridad y de la transferencia entre dispositivos
 * (`res/xml/backup_rules.xml` y `data_extraction_rules.xml`). Sin WorkManager. Las versiones futuras
 * requieren migraciones explícitas.
 */
@Database(entities = [StravaCacheEntity::class], version = 1, exportSchema = true)
abstract class StravaDatabase : RoomDatabase() {
    abstract fun stravaCacheDao(): StravaCacheDao

    companion object {
        const val NAME = "sportapp_strava.db"

        fun create(context: Context): StravaDatabase =
            Room.databaseBuilder(context.applicationContext, StravaDatabase::class.java, NAME).build()
    }
}
