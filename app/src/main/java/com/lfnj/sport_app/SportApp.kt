package com.lfnj.sport_app

import android.app.Application
import androidx.work.Configuration
import com.lfnj.sport_app.core.config.AppConfig
import com.lfnj.sport_app.core.security.EncryptedTokenStore
import com.lfnj.sport_app.di.AppContainer
import com.lfnj.sport_app.features.training.session.sync.TrainingSyncWorkerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SportApp : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    /** Ámbito del proceso para coordinar con la sesión la sincronización de HU032 y la caché de HU035. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(AppConfig.fromBuildConfig(), EncryptedTokenStore(this), this)
        container.trainingSyncCoordinator.start(applicationScope)
        container.calendarCacheJanitor.start(applicationScope)
    }

    /** WorkManager crea los trabajos de HU032 con las dependencias del contenedor manual. */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(TrainingSyncWorkerFactory { container.trainingSyncEngine })
            .build()
}
