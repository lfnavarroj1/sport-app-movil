package com.lfnj.sport_app.features.nutrition.reminders.data.local

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
 * Última configuración confirmada (ruleset + preferencias) de un deportista (HU038). En claro solo
 * queda la partición; el contenido viaja en [payload], cifrado con Android Keystore AES-GCM.
 */
@Entity(tableName = "session_reminder_config")
class SessionReminderConfigEntity(
    /** Huella del `user_id` ([com.lfnj.sport_app.features.training.session.model.TrainingOwner]). */
    @PrimaryKey @ColumnInfo(name = "owner_key") val ownerKey: String,
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
)

/**
 * Registro local de una ocurrencia presentada (CA2, CA3). La clave técnica `(owner_key,
 * session_id, occurrence)` —`occurrence` = `TYPE:n`— garantiza que una ocurrencia se presente una
 * sola vez aunque el servicio o la aplicación se reinicien; `delivered_at` y la forma de
 * presentación viajan cifrados en [payload]. No se envía al backend (ADR-045 §6).
 */
@Entity(tableName = "session_reminder_delivery", primaryKeys = ["owner_key", "session_id", "occurrence"])
class SessionReminderDeliveryEntity(
    @ColumnInfo(name = "owner_key") val ownerKey: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "occurrence") val occurrence: String,
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
)

@Dao
interface SessionRemindersDao {
    @Query("SELECT * FROM session_reminder_config WHERE owner_key = :owner")
    fun observeConfig(owner: String): Flow<SessionReminderConfigEntity?>

    @Query("SELECT * FROM session_reminder_config WHERE owner_key = :owner")
    suspend fun config(owner: String): SessionReminderConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceConfig(entry: SessionReminderConfigEntity)

    @Query("DELETE FROM session_reminder_config WHERE owner_key = :owner")
    suspend fun deleteConfig(owner: String)

    /** Reclama la ocurrencia: `-1` si ya estaba registrada (nunca se presenta dos veces). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun claim(delivery: SessionReminderDeliveryEntity): Long

    @Query("SELECT * FROM session_reminder_delivery WHERE owner_key = :owner AND session_id = :sessionId ORDER BY occurrence")
    suspend fun deliveries(owner: String, sessionId: String): List<SessionReminderDeliveryEntity>

    /** Retención: solo se conserva el registro de la sesión en curso. */
    @Query("DELETE FROM session_reminder_delivery WHERE owner_key = :owner AND session_id != :sessionId")
    suspend fun deleteDeliveriesExceptSession(owner: String, sessionId: String)

    @Query("DELETE FROM session_reminder_delivery WHERE owner_key = :owner")
    suspend fun deleteDeliveries(owner: String)

    @Query("DELETE FROM session_reminder_config WHERE owner_key != :owner")
    suspend fun deleteConfigsExcept(owner: String)

    @Query("DELETE FROM session_reminder_delivery WHERE owner_key != :owner")
    suspend fun deleteDeliveriesExcept(owner: String)
}

/**
 * Base de HU038, separada de las demás (incluida la de HU032). Archivo excluido de la copia de
 * seguridad y de la transferencia entre dispositivos (`res/xml/backup_rules.xml` y
 * `data_extraction_rules.xml`). Las versiones futuras requieren migraciones explícitas.
 */
@Database(entities = [SessionReminderConfigEntity::class, SessionReminderDeliveryEntity::class], version = 1, exportSchema = true)
abstract class SessionRemindersDatabase : RoomDatabase() {
    abstract fun sessionRemindersDao(): SessionRemindersDao

    companion object {
        const val NAME = "sportapp_session_reminders.db"

        fun create(context: Context): SessionRemindersDatabase =
            Room.databaseBuilder(context.applicationContext, SessionRemindersDatabase::class.java, NAME).build()
    }
}
