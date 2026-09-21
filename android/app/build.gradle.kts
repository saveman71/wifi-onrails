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
        versionCode = 8
        versionName = "0.8-poc"
        // MapLibre has about 11 MB of native code per ABI. arm64 covers every phone this POC runs
        // on and keeps the CI artifact small.
        ndk { abiFilters += "arm64-v8a" }
    }

    // Committed so CI builds keep one signature and stay installable over each other.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
    // StateFlow and the service poll loop.
    // HTTP is HttpURLConnection and JSON is org.json, both part of the platform.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // The portal serves PMTiles vector tiles and a MapLibre style, so the map has to be MapLibre.
    // PMTiles are read natively from 11.8.0 on, and cached from 13.5.0 on.
    implementation("org.maplibre.gl:android-sdk:13.6.1")
    // MapLibre pulls okhttp in at runtime only. TrainMap needs it at compile time to give MapLibre
    // a client tied to the train's Wi-Fi. Same version as MapLibre's POM.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
