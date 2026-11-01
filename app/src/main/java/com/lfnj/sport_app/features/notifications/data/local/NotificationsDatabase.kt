package com.lfnj.sport_app.features.notifications.data.local

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
 * Última bandeja confirmada para un deportista (HU039). En claro solo queda la partición; las
 * notificaciones, el cursor, `last_synced_at` y los identificadores ya anunciados viajan en
 * [payload], cifrado con Android Keystore AES-GCM (ADR-026 §7).
 */
@Entity(tableName = "notification_inbox_cache")
class NotificationsCacheEntity(
    /** Huella del `user_id` ([com.lfnj.sport_app.features.notifications.model.NotificationsOwner]). */
    @PrimaryKey @ColumnInfo(name = "owner_key") val ownerKey: String,
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
)

/** Una fila por deportista: cada primera página confirmada reemplaza la bandeja anterior. */
@Dao
interface NotificationsCacheDao {
    @Query("SELECT * FROM notification_inbox_cache WHERE owner_key = :owner")
    fun observe(owner: String): Flow<NotificationsCacheEntity?>

    @Query("SELECT * FROM notification_inbox_cache WHERE owner_key = :owner")
    suspend fun read(owner: String): NotificationsCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replace(entry: NotificationsCacheEntity)

    @Query("DELETE FROM notification_inbox_cache WHERE owner_key = :owner")
    suspend fun delete(owner: String)

    @Query("DELETE FROM notification_inbox_cache WHERE owner_key != :owner")
    suspend fun deleteAllExcept(owner: String)
}

/**
 * Bandeja de HU039, separada de las demás bases. Archivo excluido de la copia de seguridad y de la
 * transferencia entre dispositivos (`res/xml/backup_rules.xml` y `data_extraction_rules.xml`). Las
 * versiones futuras requieren migraciones explícitas.
 */
@Database(entities = [NotificationsCacheEntity::class], version = 1, exportSchema = true)
abstract class NotificationsDatabase : RoomDatabase() {
    abstract fun notificationsCacheDao(): NotificationsCacheDao

    companion object {
        const val NAME = "sportapp_notifications.db"

        fun create(context: Context): NotificationsDatabase =
            Room.databaseBuilder(context.applicationContext, NotificationsDatabase::class.java, NAME).build()
    }
}
