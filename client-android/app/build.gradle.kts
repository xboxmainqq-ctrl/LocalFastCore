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

    sourceSets["main"].java.srcDir("../../src/main/java")
    sourceSets["main"].java.include(
        "ru/nexora/client/**",
        "ru/nexora/runtime/OverworldChunkGenerator.java",
        "ru/nexora/runtime/ChunkGenerator.java",
        "ru/nexora/runtime/GeneratedChunk.java",
        "ru/nexora/runtime/ChunkPosition.java",
        "ru/nexora/runtime/WorldHeight.java",
        "ru/nexora/runtime/BlockState.java",
        "ru/nexora/runtime/BlockPosition.java",
        "ru/nexora/runtime/BiomeColumnPosition.java",
        "ru/nexora/runtime/BiomeDefinition.java",
        "ru/nexora/runtime/BiomeRegistry.java",
        "ru/nexora/runtime/BiomeClimateSampler.java",
        "ru/nexora/runtime/ClimateSample.java",
        "ru/nexora/runtime/TerrainHeightSampler.java",
        "ru/nexora/runtime/HydrologyField.java",
        "ru/nexora/runtime/BiomeSection.java",
        "ru/nexora/runtime/FluidStates.java"
    )

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
