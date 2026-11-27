
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Читаем локальные настройки проекта
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")

if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use {
        localProperties.load(it)
    }
}

val easyArKey = localProperties.getProperty("easyar.key", "")

android {
    namespace = "cn.easyar.samples.helloar"
    compileSdk = 36

    // Включаем генерацию BuildConfig
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "cn.easyar.samples.helloar"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // Передаём ключ EasyAR в BuildConfig
        buildConfigField(
            "String",
            "EASYAR_KEY",
            "\"$easyArKey\""
        )

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to arrayOf("*.aar"))))
}
