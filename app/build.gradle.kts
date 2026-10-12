plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * URL base del BFF móvil por variante. Se puede sobrescribir con `-P<propiedad>=<url>` o en
 * `~/.gradle/gradle.properties`; nunca contiene secretos.
 * - Depuración: composición local de Kubernetes vista desde el emulador (`platform`, salida `bff_mobile_avd`).
 * - Release: sin valor aprobado mientras producción GCP esté pendiente; la app muestra «configuración no disponible».
 */
fun bffMobileBaseUrl(property: String, fallback: String): String {
    val value = (project.findProperty(property) as String?)?.trim() ?: fallback
    require(value.isEmpty() || value.endsWith("/")) { "$property debe terminar en '/': $value" }
    return value
}

android {
    namespace = "com.lfnj.sport_app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.lfnj.sport_app"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Versión exacta del contrato consumido (backend `contracts/openapi/bff-mobile.openapi.json`).
        buildConfigField("String", "BFF_MOBILE_CONTRACT_VERSION", "\"0.3.0\"")
    }

    buildTypes {
        debug {
            buildConfigField("String", "APP_ENVIRONMENT", "\"local\"")
            buildConfigField(
                "String",
                "BFF_MOBILE_BASE_URL",
                "\"${bffMobileBaseUrl("sportapp.bffMobileBaseUrl.debug", "http://10.0.2.2:18081/")}\""
            )
            enableUnitTestCoverage = true
        }
        release {
            buildConfigField("String", "APP_ENVIRONMENT", "\"production\"")
            buildConfigField(
                "String",
                "BFF_MOBILE_BASE_URL",
                "\"${bffMobileBaseUrl("sportapp.bffMobileBaseUrl.release", "")}\""
            )
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        abortOnError = true
        checkDependencies = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        animationsDisabled = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
