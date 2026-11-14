package ru.nexora.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FluidWorldStorageTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void storesFluidSeparatelyAndUnloadsChunkWhenCleared() {
        InMemoryWorld world = new InMemoryWorld("fluids", new WorldHeight(-64, 320));
        BlockPosition position = new BlockPosition(-1, -17, -16);
        BlockState water = new BlockState("minecraft:water", Map.of("liquid_depth", "0"));

        world.setFluid(position, water);

        assertEquals(water, world.fluidAt(position));
        assertEquals(BlockState.AIR, world.blockAt(position));
        assertEquals(Map.of(position, water), world.fluidsInChunk(new ChunkPosition(-1, -1)));

        world.setFluid(position, BlockState.AIR);
        assertEquals(BlockState.AIR, world.fluidAt(position));
        assertEquals(0, world.loadedChunkCount());
    }

    @Test
    void persistsFluidSectionsInNexoraWorldFormatV3() throws IOException {
        FileWorldStorage storage = new FileWorldStorage(temporaryDirectory.resolve("worlds"));
        InMemoryWorld original = new InMemoryWorld("fluidworld", new WorldHeight(-64, 320));
        BlockPosition waterPosition = new BlockPosition(18, 4, -31);
        BlockPosition lavaPosition = new BlockPosition(19, 5, -31);
        BlockState water = new BlockState("minecraft:water", Map.of("liquid_depth", "0"));
        BlockState lava = new BlockState("minecraft:lava", Map.of("liquid_depth", "0"));
        original.setFluid(waterPosition, water);
        original.setFluid(lavaPosition, lava);

        storage.save(original);

        InMemoryWorld loaded = storage.load("fluidworld").orElseThrow();
        assertEquals(water, loaded.fluidAt(waterPosition));
        assertEquals(lava, loaded.fluidAt(lavaPosition));
        assertEquals(BlockState.AIR, loaded.blockAt(waterPosition));
    }
}
