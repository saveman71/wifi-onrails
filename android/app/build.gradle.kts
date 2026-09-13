import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.onrails.trainwifi"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.onrails.trainwifi"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "0.3-poc"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // The only third-party dependency: StateFlow and the service poll loop.
    // HTTP is HttpURLConnection and JSON is org.json, both part of the platform.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
