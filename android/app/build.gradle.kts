plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// -PappId / -PappName build the same app under another id, e.g. to update an older install in place.
val appId = (findProperty("appId") as String?) ?: "dev.touchport"
val appName = (findProperty("appName") as String?) ?: "TouchPort"

android {
    namespace = "dev.touchport"
    compileSdk = 36

    defaultConfig {
        applicationId = appId
        minSdk = 28
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.0"
        manifestPlaceholders["appName"] = appName
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

kotlin { jvmToolchain(17) }
