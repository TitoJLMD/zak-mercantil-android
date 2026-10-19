plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.zak.mercantil"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.zak.mercantil.prueba"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2-prueba"
    }
    buildTypes {
        release { isMinifyEnabled = false; isDebuggable = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { testImplementation("junit:junit:4.13.2") }
