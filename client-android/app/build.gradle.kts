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

    sourceSets["main"].java.srcFiles(
        "../../src/main/java/ru/nexora/runtime/OverworldChunkGenerator.java",
        "../../src/main/java/ru/nexora/runtime/ChunkGenerator.java",
        "../../src/main/java/ru/nexora/runtime/GeneratedChunk.java",
        "../../src/main/java/ru/nexora/runtime/ChunkPosition.java",
        "../../src/main/java/ru/nexora/runtime/WorldHeight.java",
        "../../src/main/java/ru/nexora/runtime/BlockState.java",
        "../../src/main/java/ru/nexora/runtime/BlockPosition.java",
        "../../src/main/java/ru/nexora/runtime/BiomeColumnPosition.java",
        "../../src/main/java/ru/nexora/runtime/BiomeDefinition.java",
        "../../src/main/java/ru/nexora/runtime/BiomeRegistry.java",
        "../../src/main/java/ru/nexora/runtime/BiomeClimateSampler.java",
        "../../src/main/java/ru/nexora/runtime/ClimateSample.java",
        "../../src/main/java/ru/nexora/runtime/TerrainHeightSampler.java",
        "../../src/main/java/ru/nexora/runtime/HydrologyField.java",
        "../../src/main/java/ru/nexora/runtime/BiomeSection.java",
        "../../src/main/java/ru/nexora/runtime/FluidStates.java"
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
