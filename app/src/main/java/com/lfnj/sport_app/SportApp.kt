package com.lfnj.sport_app

import android.app.Application
import com.lfnj.sport_app.core.config.AppConfig
import com.lfnj.sport_app.di.AppContainer

class SportApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(AppConfig.fromBuildConfig())
    }
}
