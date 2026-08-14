plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.humanrouter"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.humanrouter"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "RUNTIME_BASE_URL", "\"https://github.com/fowov2-jpg/676017/releases/download/runtime-v0.4.3/\"")
    }

    buildFeatures { buildConfig = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.maplibre.gl:android-sdk:11.8.0")
}
