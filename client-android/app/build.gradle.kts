plugins {
    id("com.android.application")
}

android {
    namespace = "ru.nexora.client"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.nexora.client"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-dev"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("io.github.webrtc-sdk:android-prefixed-stripped:150.7871.04")
    testImplementation("junit:junit:4.13.2")
}
