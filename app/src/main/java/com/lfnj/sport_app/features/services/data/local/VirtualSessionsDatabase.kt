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
 * Último catálogo de entrenadores virtuales confirmado para un deportista (HU040). En claro solo
 * queda la partición; los servicios, intervalos, tarifas y `last_synced_at` viajan en [payload],
 * cifrado con Android Keystore AES-GCM (ADR-046 §8, patrón ADR-031).
 */
@Entity(tableName = "virtual_sessions_catalog")
class VirtualSessionsCatalogEntity(
    /** Huella del `user_id` ([com.lfnj.sport_app.features.services.model.VirtualSessionsOwner]). */
    @PrimaryKey @ColumnInfo(name = "owner_key") val ownerKey: String,
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
)

/** Últimas citas confirmadas del deportista («Mis sesiones»), cifradas como el catálogo. */
@Entity(tableName = "virtual_sessions_appointments")
class VirtualSessionsAppointmentsEntity(
    @PrimaryKey @ColumnInfo(name = "owner_key") val ownerKey: String,
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
)

/** Una fila por deportista y lectura: cada respuesta confirmada reemplaza la anterior. */
@Dao
interface VirtualSessionsCacheDao {
    @Query("SELECT * FROM virtual_sessions_catalog WHERE owner_key = :owner")
    fun observeCatalog(owner: String): Flow<VirtualSessionsCatalogEntity?>

    @Query("SELECT * FROM virtual_sessions_catalog WHERE owner_key = :owner")
    suspend fun readCatalog(owner: String): VirtualSessionsCatalogEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceCatalog(entry: VirtualSessionsCatalogEntity)

    @Query("SELECT * FROM virtual_sessions_appointments WHERE owner_key = :owner")
    fun observeAppointments(owner: String): Flow<VirtualSessionsAppointmentsEntity?>

    @Query("SELECT * FROM virtual_sessions_appointments WHERE owner_key = :owner")
    suspend fun readAppointments(owner: String): VirtualSessionsAppointmentsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceAppointments(entry: VirtualSessionsAppointmentsEntity)

    @Query("DELETE FROM virtual_sessions_catalog WHERE owner_key = :owner")
    suspend fun deleteCatalog(owner: String)

    @Query("DELETE FROM virtual_sessions_appointments WHERE owner_key = :owner")
    suspend fun deleteAppointments(owner: String)

    @Query("DELETE FROM virtual_sessions_catalog WHERE owner_key != :owner")
    suspend fun deleteCatalogsExcept(owner: String)

    @Query("DELETE FROM virtual_sessions_appointments WHERE owner_key != :owner")
    suspend fun deleteAppointmentsExcept(owner: String)
}

/**
 * Caché de lectura de HU040, separada de las bases de las demás HU y sin cola de comandos:
 * reservar y cancelar exigen conectividad (ADR-046 §8). Archivo excluido de la copia de seguridad
 * y de la transferencia entre dispositivos (`res/xml/backup_rules.xml` y
 * `data_extraction_rules.xml`). Las versiones futuras requieren migraciones explícitas.
 */
@Database(
    entities = [VirtualSessionsCatalogEntity::class, VirtualSessionsAppointmentsEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class VirtualSessionsDatabase : RoomDatabase() {
    abstract fun virtualSessionsCacheDao(): VirtualSessionsCacheDao

    companion object {
        const val NAME = "sportapp_virtual_sessions.db"

        fun create(context: Context): VirtualSessionsDatabase =
            Room.databaseBuilder(context.applicationContext, VirtualSessionsDatabase::class.java, NAME).build()
    }
}
