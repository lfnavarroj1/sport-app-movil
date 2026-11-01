package com.lfnj.sport_app.core.config

import com.lfnj.sport_app.BuildConfig

/**
 * Configuración técnica por variante de compilación. No contiene secretos.
 *
 * @property bffMobileBaseUrl URL base del BFF móvil, terminada en `/`; vacía si la variante no tiene valor aprobado.
 * @property environment Ambiente de la variante (`local` o `production`).
 * @property contractVersion Versión del contrato OpenAPI de `bff-mobile` que consume el cliente.
 * @property healthDataConsentVersion Versión del consentimiento de datos de salud (HU042); vacía si la variante no la tiene.
 */
data class AppConfig(
    val bffMobileBaseUrl: String,
    val environment: String,
    val contractVersion: String,
    val appVersion: String,
    val healthDataConsentVersion: String = "",
) {
    val isBffConfigured: Boolean get() = bffMobileBaseUrl.isNotBlank()

    companion object {
        fun fromBuildConfig(): AppConfig = AppConfig(
            bffMobileBaseUrl = BuildConfig.BFF_MOBILE_BASE_URL,
            environment = BuildConfig.APP_ENVIRONMENT,
            contractVersion = BuildConfig.BFF_MOBILE_CONTRACT_VERSION,
            appVersion = BuildConfig.VERSION_NAME,
            healthDataConsentVersion = BuildConfig.HEALTH_DATA_CONSENT_VERSION,
        )
    }
}
