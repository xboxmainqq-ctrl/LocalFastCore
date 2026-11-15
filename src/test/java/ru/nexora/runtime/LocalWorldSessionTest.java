package ru.nexora.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LocalWorldSessionTest {
    @TempDir
    Path temporaryDirectory;

    private FlatChunkGenerator generator() {
        return new FlatChunkGenerator(
                64, 4,
                new BlockState("minecraft:grass_block"),
                new BlockState("minecraft:dirt"),
                new BlockState("minecraft:bedrock")
        );
    }

    @Test
    void createsGeneratesAndRestoresLocalWorld() throws IOException {
        FileWorldStorage storage = new FileWorldStorage(temporaryDirectory);
        BlockPosition surface = new BlockPosition(2, 64, 3);

        try (LocalWorldSession session = LocalWorldSession.open(
                storage, "singleplayer", new WorldHeight(-64, 320), generator(), 1234L
        )) {
            assertEquals(1, session.ensureChunkLoaded(new ChunkPosition(0, 0)));
            assertEquals(new BlockState("minecraft:grass_block"), session.world().blockAt(surface));
            session.world().setBlock(surface, new BlockState("minecraft:diamond_block"));
            session.save();
        }

        assertTrue(sessionClosedAfterClose(storage));
        try (LocalWorldSession restored = LocalWorldSession.open(
                storage, "singleplayer", new WorldHeight(0, 128), generator(), 999L
        )) {
            assertEquals(new WorldHeight(-64, 320), restored.world().height());
            assertEquals(new BlockState("minecraft:diamond_block"), restored.world().blockAt(surface));
            assertFalse(restored.ensureChunkLoaded(new ChunkPosition(0, 0)),
                    "a restored chunk must not be regenerated over player changes");
        }
    }

    @Test
    void loadsAreaIncludingNegativeChunkCoordinates() throws IOException {
        FileWorldStorage storage = new FileWorldStorage(temporaryDirectory);
        try (LocalWorldSession session = LocalWorldSession.open(
                storage, "area", new WorldHeight(0, 128), generator(), 7L
        )) {
            assertEquals(9, session.ensureAreaLoaded(new ChunkPosition(-1, -1), 1));
            assertTrue(session.world().isChunkLoaded(new ChunkPosition(-2, -2)));
            assertTrue(session.world().isChunkLoaded(new ChunkPosition(0, 0)));
            assertEquals(9, session.world().loadedChunkCount());
        }
    }

    @Test
    void rejectsInvalidRadiusAndOperationsAfterClose() throws IOException {
        LocalWorldSession session = LocalWorldSession.open(
                new FileWorldStorage(temporaryDirectory),
                "closed", new WorldHeight(0, 128), generator(), 1L
        );
        assertThrows(IllegalArgumentException.class,
                () -> session.ensureAreaLoaded(new ChunkPosition(0, 0), 9));
        session.close();
        session.close();
        assertTrue(session.isClosed());
        assertThrows(IllegalStateException.class, session::world);
        assertThrows(IllegalStateException.class,
                () -> session.ensureChunkLoaded(new ChunkPosition(0, 0)));
    }

    private boolean sessionClosedAfterClose(FileWorldStorage storage) throws IOException {
        return storage.load("singleplayer").isPresent();
    }
}
