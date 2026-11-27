import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// --- ANT+ plugin library (Garmin) -------------------------------------------
// Fetched from the official ANT-Android-SDKs repo on first build, so it does not
// have to be committed. You can also drop the .aar into app/libs/ by hand.
val antAar = file("libs/antpluginlib_3-9-0.aar")
if (!antAar.exists()) {
    antAar.parentFile.mkdirs()
    val url = "https://raw.githubusercontent.com/ant-wireless/ANT-Android-SDKs/master/" +
        "ANT%2B_Android_SDK/API/antpluginlib_3-9-0.aar"
    logger.lifecycle("Downloading ANT+ plugin library from $url")
    URI(url).toURL().openStream().use { input ->
        antAar.outputStream().use { output -> input.copyTo(output) }
    }
}

android {
    namespace = "com.dror.sensorrx"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dror.sensorrx"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    // Fixed key kept in the repo, so every build installs over the previous one.
    signingConfigs {
        create("fixed") {
            storeFile = file("sensorrx.keystore")
            storePassword = "sensorrx"
            keyAlias = "sensorrx"
            keyPassword = "sensorrx"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(files("libs/antpluginlib_3-9-0.aar"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
