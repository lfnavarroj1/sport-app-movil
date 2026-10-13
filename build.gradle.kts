// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.androidx.room) apply false
}

// Puerta local equivalente a la CI de push: lint, pruebas unitarias (incluye contrato) y APK de depuración.
tasks.register("ciCheck") {
    group = "verification"
    description = "Lint, pruebas unitarias con contrato del BFF móvil y ensamblado de depuración."
    dependsOn(":app:lintDebug", ":app:testDebugUnitTest", ":app:assembleDebug")
}
