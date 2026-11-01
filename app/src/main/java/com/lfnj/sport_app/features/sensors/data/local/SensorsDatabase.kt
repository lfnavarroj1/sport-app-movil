package com.lfnj.sport_app.features.sensors.data.local

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
 * Dispositivo del deportista (HU037). En claro solo quedan la partición y la identidad lógica
 * (`device_id`, ya es un hash); el nombre, el estado, la versión y la dirección BLE con la que este
 * teléfono lo vinculó viajan en [payload], cifrado con Android Keystore AES-GCM (ADR-044 §2).
 */
@Entity(tableName = "sensor_device", primaryKeys = ["owner_key", "device_id"])
class SensorDeviceEntity(
    /** Huella del `user_id` ([com.lfnj.sport_app.features.training.session.model.TrainingOwner]). */
    @ColumnInfo(name = "owner_key") val ownerKey: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
)

/**
 * Muestra de frecuencia cardiaca pendiente de confirmación (CA5). La clave primaria reproduce la
 * idempotencia natural del backend `(session_id, device_id, measured_at)`; [measuredAt] (segundos
 * UTC) queda en claro para ordenar y vencer, y el valor viaja cifrado en [payload].
 */
@Entity(tableName = "sensor_sample", primaryKeys = ["session_id", "device_id", "measured_at"])
class SensorSampleEntity(
    @ColumnInfo(name = "owner_key") val ownerKey: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "measured_at") val measuredAt: Long,
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
)

@Dao
interface SensorsDao {
    @Query("SELECT * FROM sensor_device WHERE owner_key = :owner")
    fun observeDevices(owner: String): Flow<List<SensorDeviceEntity>>

    @Query("SELECT * FROM sensor_device WHERE owner_key = :owner")
    suspend fun devices(owner: String): List<SensorDeviceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDevice(entry: SensorDeviceEntity)

    @Query("DELETE FROM sensor_device WHERE owner_key = :owner AND device_id NOT IN (:keep)")
    suspend fun deleteDevicesExcept(owner: String, keep: List<String>)

    /** Un segundo ya capturado se conserva: la primera lectura de cada segundo es la muestra de 1 Hz. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSample(entry: SensorSampleEntity)

    @Query(
        "SELECT * FROM sensor_sample WHERE owner_key = :owner AND session_id = :sessionId AND device_id = :deviceId " +
            "ORDER BY measured_at ASC LIMIT :limit"
    )
    suspend fun oldestSamples(owner: String, sessionId: String, deviceId: String, limit: Int): List<SensorSampleEntity>

    @Query("SELECT COUNT(*) FROM sensor_sample WHERE owner_key = :owner AND session_id = :sessionId")
    suspend fun countSamples(owner: String, sessionId: String): Int

    @Query("DELETE FROM sensor_sample WHERE session_id = :sessionId AND device_id = :deviceId AND measured_at IN (:measuredAt)")
    suspend fun deleteSamples(sessionId: String, deviceId: String, measuredAt: List<Long>)

    @Query("DELETE FROM sensor_sample WHERE owner_key = :owner AND session_id = :sessionId")
    suspend fun deleteSessionSamples(owner: String, sessionId: String)

    @Query("DELETE FROM sensor_sample WHERE owner_key = :owner AND device_id = :deviceId")
    suspend fun deleteDeviceSamples(owner: String, deviceId: String)

    /** Vencimiento local: el backend rechaza (`too_old`) las muestras con más de 15 min. */
    @Query("DELETE FROM sensor_sample WHERE measured_at < :before")
    suspend fun deleteSamplesBefore(before: Long)

    /** Muestras que quedaron de una sesión anterior (p. ej. tras un reinicio): vencen con su sesión. */
    @Query("DELETE FROM sensor_sample WHERE owner_key = :owner AND session_id != :sessionId")
    suspend fun deleteSamplesOfOtherSessions(owner: String, sessionId: String)

    @Query("DELETE FROM sensor_device WHERE owner_key = :owner")
    suspend fun deleteDevices(owner: String)

    @Query("DELETE FROM sensor_sample WHERE owner_key = :owner")
    suspend fun deleteAllSamples(owner: String)

    @Query("DELETE FROM sensor_device WHERE owner_key != :owner")
    suspend fun deleteDevicesOfOthers(owner: String)

    @Query("DELETE FROM sensor_sample WHERE owner_key != :owner")
    suspend fun deleteSamplesOfOthers(owner: String)
}

/**
 * Vinculaciones y muestras pendientes de HU037, separadas de las bases de las demás HU y con su
 * propia clave de Keystore. Archivo excluido de la copia de seguridad y de la transferencia entre
 * dispositivos (`res/xml/backup_rules.xml` y `data_extraction_rules.xml`). Las versiones futuras
 * requieren migraciones explícitas.
 */
@Database(entities = [SensorDeviceEntity::class, SensorSampleEntity::class], version = 1, exportSchema = true)
abstract class SensorsDatabase : RoomDatabase() {
    abstract fun sensorsDao(): SensorsDao

    companion object {
        const val NAME = "sportapp_sensors.db"

        fun create(context: Context): SensorsDatabase =
            Room.databaseBuilder(context.applicationContext, SensorsDatabase::class.java, NAME).build()
    }
}
