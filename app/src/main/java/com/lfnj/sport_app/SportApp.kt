package com.lfnj.sport_app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.work.Configuration
import androidx.work.DelegatingWorkerFactory
import com.lfnj.sport_app.core.config.AppConfig
import com.lfnj.sport_app.core.security.EncryptedTokenStore
import com.lfnj.sport_app.core.session.revocation.RevocationWorkerFactory
import com.lfnj.sport_app.di.AppContainer
import com.lfnj.sport_app.features.health.sync.HealthReportsSyncWorkerFactory
import com.lfnj.sport_app.features.notifications.sync.NotificationsSyncWorkerFactory
import com.lfnj.sport_app.features.nutrition.intake.sync.FoodIntakeSyncWorkerFactory
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
        // HU027: caché cifrada de eventos sugeridos, eliminada al terminar la sesión.
        container.eventSuggestionsCacheJanitor.start(applicationScope)
        container.supportSuggestionsCacheJanitor.start(applicationScope)
        // HU039: canales del sistema, bandeja cifrada eliminada al terminar la sesión y sincronización cada 15 min.
        container.androidNotificationAnnouncer.ensureChannels()
        container.notificationsCacheJanitor.start(applicationScope)
        container.inboxSyncCoordinator.start(applicationScope)
        // HU042: cola cifrada de reportes de salud; se detiene al cerrar sesión y se reanuda para el mismo deportista.
        container.healthReportsCoordinator.start(applicationScope)
        // HU038: canal de recordatorios, limpieza al terminar la sesión y servicio en primer plano de la sesión activa.
        container.androidReminderNotifier.ensureChannel()
        container.sessionRemindersJanitor.start(applicationScope)
        container.activeSessionTracker.start(applicationScope)
        // HU043: cola cifrada de consumos; se detiene al cerrar sesión y se reanuda para el mismo deportista.
        container.foodIntakeCoordinator.start(applicationScope)
        registerActivityLifecycleCallbacks(ForegroundRetry { container.activeSessionTracker.onAppForeground() })
    }

    /** HU038: Android solo permite iniciar el servicio en primer plano con la aplicación visible. */
    private class ForegroundRetry(private val onStarted: () -> Unit) : ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) = onStarted()
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    /** WorkManager crea los trabajos de HU032, HU034, HU004, HU039, HU042 y HU043 con las dependencias del contenedor manual. */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(
                DelegatingWorkerFactory().apply {
                    addFactory(TrainingSyncWorkerFactory { container.trainingSyncEngine })
                    addFactory(RevocationWorkerFactory { container.revocationQueue })
                    addFactory(MetricsSyncWorkerFactory { container.metricsSyncEngine })
                    addFactory(NotificationsSyncWorkerFactory { container.notificationsSyncEngine })
                    addFactory(HealthReportsSyncWorkerFactory { container.healthReportsSyncEngine })
                    addFactory(FoodIntakeSyncWorkerFactory { container.foodIntakeSyncEngine })
                }
            )
            .build()
}
