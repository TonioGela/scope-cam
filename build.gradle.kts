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
    }
}

dependencies {
    implementation("com.herohan:UVCAndroid:1.0.13")
    testImplementation("junit:junit:4.13.2")
}
