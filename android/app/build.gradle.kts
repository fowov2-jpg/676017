plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ciVersionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
val releaseKeystorePath = System.getenv("VH_RELEASE_KEYSTORE")
val releaseStorePassword = System.getenv("VH_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("VH_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("VH_RELEASE_KEY_PASSWORD")
val sentryDsn = System.getenv("SENTRY_DSN").orEmpty()
val gitSha = System.getenv("GITHUB_SHA").orEmpty()
val releaseSigningConfigured = listOf(
    releaseKeystorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

fun String.asBuildConfigString(): String = "\"" +
    replace("\\", "\\\\").replace("\"", "\\\"") +
    "\""

android {
    namespace = "app.humanrouter"
    compileSdk = 35

    signingConfigs {
        getByName("debug") {
            val ciKeystore = rootProject.file("ci-debug.keystore")
            if (ciKeystore.exists()) {
                storeFile = ciKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(checkNotNull(releaseKeystorePath))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    defaultConfig {
        applicationId = "app.humanrouter"
        minSdk = 26
        targetSdk = 35
        versionCode = ciVersionCode
        versionName = "0.1.$ciVersionCode"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "RUNTIME_BASE_URL", "\"https://github.com/fowov2-jpg/676017/releases/download/runtime-current/\"")
        buildConfigField("boolean", "REALTIME_TRANSIT_CONFIGURED", "false")
        buildConfigField("String", "SENTRY_DSN", sentryDsn.asBuildConfigString())
        buildConfigField("String", "GIT_SHA", gitSha.asBuildConfigString())
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures { buildConfig = true }

    testOptions {
        animationsDisabled = true
    }

    lint {
        checkTestSources = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("org.maplibre.gl:android-sdk:11.8.0")
    implementation("io.sentry:sentry-android:8.50.1")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// `assembleRelease` is intentionally allowed to produce an unsigned APK when the four
// VH_RELEASE_* environment variables are absent. A production publish job must require those
// secrets and verify the final signature; QA/debug signing is never silently reused for release.
