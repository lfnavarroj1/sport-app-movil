package com.lfnj.sport_app.di

import android.content.Context
import com.lfnj.sport_app.core.config.AppConfig
import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.network.HealthApi
import com.lfnj.sport_app.core.security.KeystoreTokenCipher
import com.lfnj.sport_app.core.security.TokenCipher
import com.lfnj.sport_app.core.session.SecureTokenStore
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.core.session.SessionRevoker
import com.lfnj.sport_app.features.status.HttpStatusRepository
import com.lfnj.sport_app.features.status.StatusRepository
import com.lfnj.sport_app.features.training.session.data.TrainingSessionApi
import com.lfnj.sport_app.features.training.session.data.TrainingSessionRepository
import com.lfnj.sport_app.features.training.session.data.TrainingSyncScheduler
import com.lfnj.sport_app.features.training.session.data.local.TrainingDatabase
import com.lfnj.sport_app.features.training.session.data.local.TrainingRecordCodec
import com.lfnj.sport_app.features.training.session.sync.TrainingSyncCoordinator
import com.lfnj.sport_app.features.training.session.sync.TrainingSyncEngine
import com.lfnj.sport_app.features.training.session.sync.WorkManagerTrainingSyncScheduler
import retrofit2.Retrofit

/** Inyección de dependencias manual: un contenedor por proceso, creado en [com.lfnj.sport_app.SportApp]. */
open class AppContainer(
    val config: AppConfig,
    private val secureTokenStore: SecureTokenStore,
    private val appContext: Context,
) {
    private val retrofit: Retrofit? by lazy {
        if (config.isBffConfigured) BffMobileClient.retrofit(config.bffMobileBaseUrl) else null
    }

    protected fun <T> bffApi(service: Class<T>): T? = retrofit?.create(service)

    open val statusRepository: StatusRepository by lazy {
        HttpStatusRepository(bffApi(HealthApi::class.java), BffMobileClient.json)
    }

    /**
     * Revocación remota de la sesión (HU004). `BLOQUEADO`: `bff-mobile` 0.3.0 no expone la
     * operación de cierre, por lo que no hay implementación y «Cerrar sesión» queda deshabilitada.
     */
    protected open val sessionRevoker: SessionRevoker? = null

    open val sessionManager: SessionManager by lazy {
        SessionManager(bffApi(SessionApi::class.java), secureTokenStore, BffMobileClient.json, revoker = sessionRevoker)
    }

    // ------------------------------------------------------------------ HU032 (ADR-029)

    protected open val trainingDatabase: TrainingDatabase by lazy { TrainingDatabase.create(appContext) }

    /** Clave AES-GCM propia de los registros de entrenamiento, distinta de la de la sesión. */
    protected open val trainingCipher: TokenCipher by lazy { KeystoreTokenCipher(TrainingRecordCodec.KEY_ALIAS) }

    protected open val trainingSyncScheduler: TrainingSyncScheduler by lazy { WorkManagerTrainingSyncScheduler(appContext) }

    private val trainingCodec by lazy { TrainingRecordCodec(trainingCipher, BffMobileClient.json) }

    private val trainingApi by lazy { bffApi(TrainingSessionApi::class.java) }

    open val trainingSessionRepository: TrainingSessionRepository by lazy {
        TrainingSessionRepository(
            dao = trainingDatabase.trainingSessionDao(),
            transactions = trainingDatabase.transactions,
            codec = trainingCodec,
            scheduler = trainingSyncScheduler,
            api = trainingApi,
            sessionManager = sessionManager,
            json = BffMobileClient.json,
        )
    }

    open val trainingSyncEngine: TrainingSyncEngine by lazy {
        TrainingSyncEngine(
            dao = trainingDatabase.trainingSessionDao(),
            transactions = trainingDatabase.transactions,
            codec = trainingCodec,
            api = trainingApi,
            sessionManager = sessionManager,
            json = BffMobileClient.json,
        )
    }

    val trainingSyncCoordinator: TrainingSyncCoordinator by lazy {
        TrainingSyncCoordinator(sessionManager.currentUser, trainingSessionRepository::hasPendingCommands, trainingSyncScheduler)
    }
}
