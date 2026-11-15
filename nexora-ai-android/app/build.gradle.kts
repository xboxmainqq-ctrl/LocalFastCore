plugins {
    id("com.android.application")
}

android {
    namespace = "ru.nexora.ai.server"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.nexora.ai.server"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
