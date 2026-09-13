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
        versionCode = 7
        versionName = "0.7-poc"
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
    // Map view for the live train position (OpenStreetMap-based, pure Java, ~1 MB).
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
