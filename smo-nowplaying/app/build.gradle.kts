plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI provides the run number so every release is a newer version for Obtainium.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toInt() ?: 1
// Signing key comes from GitHub secrets via the release workflow.
val keystorePath = System.getenv("SIGNING_KEYSTORE_PATH")

android {
    namespace = "au.chris.smonowplaying"
    compileSdk = 35
    defaultConfig {
        applicationId = "au.chris.smonowplaying"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
