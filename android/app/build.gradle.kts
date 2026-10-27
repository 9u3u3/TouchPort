import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// -PappId / -PappName build the same app under another id, e.g. to update an older install in place.
val appId = (findProperty("appId") as String?) ?: "dev.touchport"
val appName = (findProperty("appName") as String?) ?: "TouchPort"

// Release signing key, kept outside the repository. Without it (or with -PdebugSign),
// release builds are signed with the local debug key.
val keyFile = file((findProperty("touchportKeys") as String?) ?: "${System.getProperty("user.home")}/.android/touchport-release.properties")
val releaseKeys = if (keyFile.exists() && !hasProperty("debugSign")) Properties().apply { keyFile.inputStream().use(::load) } else null

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

    signingConfigs {
        if (releaseKeys != null) create("release") {
            storeFile = file(releaseKeys.getProperty("storeFile"))
            storePassword = releaseKeys.getProperty("storePassword")
            keyAlias = releaseKeys.getProperty("keyAlias")
            keyPassword = releaseKeys.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName(if (releaseKeys != null) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

kotlin { jvmToolchain(17) }
