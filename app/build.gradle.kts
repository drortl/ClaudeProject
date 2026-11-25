plugins {
    id("com.android.application")
}

android {
    namespace = "com.drortl.batterywidget"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.drortl.batterywidget"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Personal sideload build: sign the release APK with the local
            // debug key so it installs without a separate keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
