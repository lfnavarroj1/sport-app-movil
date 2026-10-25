package com.lfnj.sport_app

import android.app.Application
import androidx.work.Configuration
import androidx.work.DelegatingWorkerFactory
import com.lfnj.sport_app.core.config.AppConfig
import com.lfnj.sport_app.core.security.EncryptedTokenStore
import com.lfnj.sport_app.core.session.revocation.RevocationWorkerFactory
import com.lfnj.sport_app.di.AppContainer
import com.lfnj.sport_app.features.training.metrics.sync.MetricsSyncWorkerFactory
import com.lfnj.sport_app.features.training.session.sync.TrainingSyncWorkerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SportApp : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    /** Ámbito del proceso para la sincronización de HU032 y HU034, las cachés de HU035, HU028, HU033, HU026, HU040, HU041 y HU029 y la cola de revocación de HU004. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(AppConfig.fromBuildConfig(), EncryptedTokenStore(this), this)
        container.trainingSyncCoordinator.start(applicationScope)
        container.calendarCacheJanitor.start(applicationScope)
        container.revocationResumer.start(applicationScope)
        container.metricsSyncCoordinator.start(applicationScope)
        container.planSuggestionsCacheJanitor.start(applicationScope)
        container.trainingDashboardCacheJanitor.start(applicationScope)
        container.routeSuggestionsCacheJanitor.start(applicationScope)
        container.virtualSessionsCacheJanitor.start(applicationScope)
        container.eventRegistrationsCacheJanitor.start(applicationScope)
        container.supportSuggestionsCacheJanitor.start(applicationScope)
    }

    /** WorkManager crea los trabajos de HU032, HU034 y HU004 con las dependencias del contenedor manual. */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(
                DelegatingWorkerFactory().apply {
                    addFactory(TrainingSyncWorkerFactory { container.trainingSyncEngine })
                    addFactory(RevocationWorkerFactory { container.revocationQueue })
                    addFactory(MetricsSyncWorkerFactory { container.metricsSyncEngine })
                }
            )
            .build()
}
