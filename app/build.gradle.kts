plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nexusfloat.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nexusfloat.app"
        minSdk = 26          // Android 8.0: TYPE_APPLICATION_OVERLAY tersedia
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
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
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
    }
}
// Sengaja tanpa dependensi pihak ketiga: APK kecil, RAM ringan.
