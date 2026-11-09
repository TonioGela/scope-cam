plugins {
    id("com.android.application") version "9.5.1"
}

android {
    namespace = "dev.toniogela.scopecamera"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "dev.toniogela.scopecamera"
        minSdk = 30 // WindowInsetsController for immersive fullscreen
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // Phones are ARM; x86 only matters for emulators and a few Chromebooks, and its native libs doubled the APK.
        ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // UVCAndroid ships its own keep rules for the classes its native code calls back into.
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}

dependencies {
    implementation("com.herohan:UVCAndroid:1.0.13")
    testImplementation("junit:junit:4.13.2")
}
