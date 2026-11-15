plugins {
    id("java-library")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets {
    main {
        java {
            // Compile the exact same world-generation source files used by
            // the standalone server. No Android-side generator fork.
            srcDir("../../src/main/java")
            include(
                "ru/nexora/runtime/BlockPosition.java",
                "ru/nexora/runtime/BiomeClimateSampler.java",
                "ru/nexora/runtime/BiomeColumnPosition.java",
                "ru/nexora/runtime/BiomeDefinition.java",
                "ru/nexora/runtime/BiomeRegistry.java",
                "ru/nexora/runtime/BiomeSection.java",
                "ru/nexora/runtime/BlockState.java",
                "ru/nexora/runtime/ChunkGenerator.java",
                "ru/nexora/runtime/ChunkPosition.java",
                "ru/nexora/runtime/ClimateSample.java",
                "ru/nexora/runtime/FluidStates.java",
                "ru/nexora/runtime/GeneratedChunk.java",
                "ru/nexora/runtime/HydrologyField.java",
                "ru/nexora/runtime/OverworldChunkGenerator.java",
                "ru/nexora/runtime/TerrainHeightSampler.java",
                "ru/nexora/runtime/WorldHeight.java"
            )
        }
    }
}
