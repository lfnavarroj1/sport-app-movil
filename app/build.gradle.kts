plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

/**
 * URL base del BFF móvil por variante. Se puede sobrescribir con `-P<propiedad>=<url>` o en
 * `~/.gradle/gradle.properties`; nunca contiene secretos.
 * - Depuración: composición local de Kubernetes vista desde el emulador (`platform`, salida `bff_mobile_avd`).
 * - Release: URL de `bff-mobile` en Cloud Run, que pasa `scripts/sportapp-release-movil.ps1` de `platform` al
 *   compilar el APK de producción; sin ella (CI) la app muestra «configuración no disponible».
 */
fun bffMobileBaseUrl(property: String, fallback: String): String {
    val value = (project.findProperty(property) as String?)?.trim() ?: fallback
    require(value.isEmpty() || value.endsWith("/")) { "$property debe terminar en '/': $value" }
    return value
}

/**
 * Versión del APK. La fija `scripts/sportapp-release-movil.ps1` de `sport-app-platform` al compilar una
 * release: `sportapp.versionName` es la versión del tag del móvil (sin la v), `sportapp.versionCode`
 * se deriva de ella y `sportapp.systemVersion` es la versión de plataforma (sistema) a la que
 * pertenece. Sin estas propiedades se usan los valores de desarrollo.
 */
fun sportappProperty(name: String): String? =
    (project.findProperty(name) as String?)?.trim()?.takeIf { it.isNotEmpty() }

val appVersionName = sportappProperty("sportapp.versionName") ?: "0.1.0"
val appVersionCode = sportappProperty("sportapp.versionCode")?.toInt() ?: 1
val systemVersion = sportappProperty("sportapp.systemVersion") ?: "dev"
require(Regex("""^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$""").matches(appVersionName)) {
    "sportapp.versionName debe ser SemVer X.Y.Z sin la v: $appVersionName"
}
require(appVersionCode > 0) { "sportapp.versionCode debe ser mayor que 0: $appVersionCode" }

/**
 * Firma de release con un almacén de claves fuera del repositorio. Solo se aplica cuando las cuatro
 * variables de entorno están definidas (las define el script de release en la estación del
 * operador); sin ellas, como en CI, el APK de release queda sin firmar.
 */
val releaseSigning = listOf(
    "SPORTAPP_SIGNING_STORE_FILE",
    "SPORTAPP_SIGNING_STORE_PASSWORD",
    "SPORTAPP_SIGNING_KEY_ALIAS",
    "SPORTAPP_SIGNING_KEY_PASSWORD",
).associateWith { System.getenv(it)?.takeIf(String::isNotEmpty) }

android {
    namespace = "com.lfnj.sport_app"
    compileSdk {
        version = release(37)
    }

    signingConfigs {
        if (releaseSigning.values.all { it != null }) {
            create("release") {
                storeFile = file(releaseSigning.getValue("SPORTAPP_SIGNING_STORE_FILE")!!)
                storePassword = releaseSigning.getValue("SPORTAPP_SIGNING_STORE_PASSWORD")
                keyAlias = releaseSigning.getValue("SPORTAPP_SIGNING_KEY_ALIAS")
                keyPassword = releaseSigning.getValue("SPORTAPP_SIGNING_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.lfnj.sport_app"
        minSdk = 28
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // El E2E contra la composición local solo corre cuando se le pasan las cuentas sintéticas
        // (ver `e2e/Hu003IntegratedE2eTest`); en la ejecución normal y en CI se excluye su paquete.
        if (project.findProperty("android.testInstrumentationRunnerArguments.e2ePassword") == null) {
            testInstrumentationRunnerArguments["notPackage"] = "com.lfnj.sport_app.e2e"
        }

        // Versión exacta del contrato consumido (backend `contracts/openapi/bff-mobile.openapi.json`).
        buildConfigField("String", "BFF_MOBILE_CONTRACT_VERSION", "\"0.8.0\"")
        // Versión de plataforma (release-manifest.yaml) con la que se publicó este APK.
        buildConfigField("String", "SYSTEM_VERSION", "\"$systemVersion\"")
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
            signingConfig = signingConfigs.findByName("release")
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

// Esquemas exportados de Room: base de las pruebas de migración (HU032, ADR-029).
room {
    schemaDirectory("$projectDir/schemas")
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
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.work.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
