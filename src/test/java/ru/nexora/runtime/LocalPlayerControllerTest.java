package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LocalPlayerControllerTest {
    private InMemoryWorld worldWithFloor() {
        InMemoryWorld world = new InMemoryWorld("movement", new WorldHeight(0, 128));
        var blocks = new java.util.HashMap<BlockPosition, BlockState>();
        for (int x = -16; x < 32; x++) {
            for (int z = -16; z < 32; z++) {
                blocks.put(new BlockPosition(x, 63, z), new BlockState("minecraft:stone"));
            }
        }
        world.loadChunk(new GeneratedChunk(new ChunkPosition(-1, -1), filter(blocks, -1, -1)));
        world.loadChunk(new GeneratedChunk(new ChunkPosition(0, -1), filter(blocks, 0, -1)));
        world.loadChunk(new GeneratedChunk(new ChunkPosition(-1, 0), filter(blocks, -1, 0)));
        world.loadChunk(new GeneratedChunk(new ChunkPosition(0, 0), filter(blocks, 0, 0)));
        return world;
    }

    private java.util.Map<BlockPosition, BlockState> filter(
            java.util.Map<BlockPosition, BlockState> blocks, int chunkX, int chunkZ
    ) {
        java.util.Map<BlockPosition, BlockState> selected = new java.util.HashMap<>();
        blocks.forEach((position, state) -> {
            if (position.chunkPosition().equals(new ChunkPosition(chunkX, chunkZ))) {
                selected.put(position, state);
            }
        });
        return selected;
    }

    @Test
    void playerFallsAndLandsOnFloor() {
        LocalPlayerController player = new LocalPlayerController(worldWithFloor(), 2.5, 70, 2.5, 0);
        for (int i = 0; i < 80; i++) {
            player.tick(LocalPlayerController.Input.idle());
        }
        assertTrue(player.state().onGround());
        assertEquals(64.0, player.state().y(), 0.0001);
    }

    @Test
    void movesForwardRelativeToYawAndCountsTicks() {
        LocalPlayerController player = new LocalPlayerController(worldWithFloor(), 2.5, 64, 2.5, 0);
        var moved = player.tick(new LocalPlayerController.Input(1, 0, false, false, 0));
        assertEquals(2.5, moved.x(), 0.0001);
        assertTrue(moved.z() > 2.5);
        assertEquals(1, moved.tick());
    }

    @Test
    void unloadedChunksBlockMovement() {
        InMemoryWorld world = new InMemoryWorld("empty", new WorldHeight(0, 128));
        LocalPlayerController player = new LocalPlayerController(world, 15.5, 64, 8.5, -90);
        var state = player.tick(new LocalPlayerController.Input(1, 0, false, false, 0));
        assertEquals(15.5, state.x(), 0.0001);
        assertEquals(8.5, state.z(), 0.0001);
    }

    @Test
    void rejectsNonFiniteInput() {
        assertThrows(IllegalArgumentException.class,
                () -> new LocalPlayerController.Input(Double.NaN, 0, false, false, 0));
    }
}
