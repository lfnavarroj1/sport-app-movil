package com.lfnj.sport_app.di

import com.lfnj.sport_app.core.config.AppConfig
import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.network.HealthApi
import com.lfnj.sport_app.core.session.SecureTokenStore
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.features.status.HttpStatusRepository
import com.lfnj.sport_app.features.status.StatusRepository
import retrofit2.Retrofit

/** Inyección de dependencias manual: un contenedor por proceso, creado en [com.lfnj.sport_app.SportApp]. */
open class AppContainer(
    val config: AppConfig,
    private val secureTokenStore: SecureTokenStore,
) {
    private val retrofit: Retrofit? by lazy {
        if (config.isBffConfigured) BffMobileClient.retrofit(config.bffMobileBaseUrl) else null
    }

    protected fun <T> bffApi(service: Class<T>): T? = retrofit?.create(service)

    open val statusRepository: StatusRepository by lazy {
        HttpStatusRepository(bffApi(HealthApi::class.java), BffMobileClient.json)
    }

    open val sessionManager: SessionManager by lazy {
        SessionManager(bffApi(SessionApi::class.java), secureTokenStore, BffMobileClient.json)
    }
}
