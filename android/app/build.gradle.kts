plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Bei jedem Build auf GitHub steigt die Versionsnummer, damit Updates
// einfach über die alte App installiert werden können.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "app.camwater"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.camwater"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // Fester Schlüssel, damit jede neue APK als Update installiert werden kann.
    signingConfigs {
        create("release") {
            storeFile = file("camwater.keystore")
            storePassword = "camwater"
            keyAlias = "camwater"
            keyPassword = "camwater"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
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
        checkReleaseBuilds = false
        abortOnError = false
    }
}
