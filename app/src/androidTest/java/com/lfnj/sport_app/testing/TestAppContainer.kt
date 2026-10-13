package com.lfnj.sport_app.testing

import android.content.Context
import androidx.room.Room
import com.lfnj.sport_app.core.config.AppConfig
import com.lfnj.sport_app.core.security.KeystoreTokenCipher
import com.lfnj.sport_app.core.security.TokenCipher
import com.lfnj.sport_app.core.session.SecureTokenStore
import com.lfnj.sport_app.core.session.SessionRevoker
import com.lfnj.sport_app.di.AppContainer
import com.lfnj.sport_app.features.calendar.data.local.CalendarDatabase
import com.lfnj.sport_app.features.training.session.data.TrainingSyncScheduler
import com.lfnj.sport_app.features.training.session.data.local.TrainingDatabase
import java.security.KeyStore

/** Almacenamiento seguro en memoria para recorridos de prueba; el Keystore se prueba aparte. */
class InMemoryTokenStore : SecureTokenStore {
    var value: String? = null
    override fun read() = value
    override fun write(value: String) {
        this.value = value
    }
    override fun clear() {
        value = null
    }
}

/** Registra las solicitudes de sincronización: la prueba ejecuta el motor cuando corresponde. */
class RecordingScheduler : TrainingSyncScheduler {
    val scheduled = mutableListOf<String>()
    var cancellations = 0

    override fun schedule(owner: String) {
        scheduled += owner
    }

    override fun cancelAll() {
        cancellations++
    }
}

/**
 * Contenedor real con bases de HU032 y HU035 en memoria, claves de Keystore de prueba y sincronización
 * controlada por la prueba (sin WorkManager), para no tocar los datos de la aplicación instalada.
 */
class TestAppContainer(
    config: AppConfig,
    store: SecureTokenStore,
    context: Context,
    revoker: SessionRevoker? = null,
) : AppContainer(config, store, context) {
    val database: TrainingDatabase = Room.inMemoryDatabaseBuilder(context, TrainingDatabase::class.java).build()
    val scheduler = RecordingScheduler()

    override val sessionRevoker: SessionRevoker? = revoker
    override val trainingDatabase: TrainingDatabase get() = database
    override val trainingSyncScheduler: TrainingSyncScheduler get() = scheduler
    override val trainingCipher: TokenCipher = KeystoreTokenCipher(TEST_KEY_ALIAS)

    val calendar: CalendarDatabase = Room.inMemoryDatabaseBuilder(context, CalendarDatabase::class.java).build()
    override val calendarDatabase: CalendarDatabase get() = calendar
    override val calendarCipher: TokenCipher = KeystoreTokenCipher(CALENDAR_TEST_KEY_ALIAS)

    fun close() {
        database.close()
        calendar.close()
        deleteTestKey()
        deleteTestKey(CALENDAR_TEST_KEY_ALIAS)
    }

    companion object {
        const val TEST_KEY_ALIAS = "sportapp.training.test"
        const val CALENDAR_TEST_KEY_ALIAS = "sportapp.calendar.test"

        fun deleteTestKey(alias: String = TEST_KEY_ALIAS) = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }
}
