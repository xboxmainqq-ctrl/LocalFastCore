package ru.nexora.world;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class WorldChunkResidencyManagerTest {

    @Test
    void publishesResidentChunkCoordinatesForSpatialQueries() {
        WorldChunkResidencyManager residency = new WorldChunkResidencyManager(1, 0);
        residency.update(List.of(new WorldChunkResidencyManager.PlayerChunk(-2, 3)), 0);

        assertTrue(residency.residentChunkCoordinates().contains(
                new WorldChunkResidencyManager.ChunkCoordinate(-2, 3)));
        assertEquals(residency.residentChunkCount(), residency.residentChunkCoordinates().size());

        residency.update(List.of(), 1);
        assertTrue(residency.residentChunkCoordinates().isEmpty());
    }

    @Test
    void loadsCircularViewAndAvoidsSquareCorners() {
        WorldChunkResidencyManager manager = new WorldChunkResidencyManager(2, 20);
        var plan = manager.update(List.of(new WorldChunkResidencyManager.PlayerChunk(0, 0)), 0);

        assertEquals(13, plan.load().size());
        assertTrue(plan.load().contains(new WorldChunkResidencyManager.ChunkCoordinate(0, 0)));
        assertFalse(plan.load().contains(new WorldChunkResidencyManager.ChunkCoordinate(2, 2)));
        assertEquals(13, plan.residentCount());
    }

    @Test
    void ordersNewResidencyLoadsFromPlayerCenterOutward() {
        WorldChunkResidencyManager manager = new WorldChunkResidencyManager(2, 20);
        var plan = manager.update(List.of(new WorldChunkResidencyManager.PlayerChunk(0, 0)), 0);

        assertEquals(new WorldChunkResidencyManager.ChunkCoordinate(0, 0), plan.load().get(0));
        int previousRing = 0;
        for (WorldChunkResidencyManager.ChunkCoordinate coordinate : plan.load()) {
            int ring = Math.max(Math.abs(coordinate.chunkX()), Math.abs(coordinate.chunkZ()));
            assertTrue(ring >= previousRing, "chunk load order must move outward from the player");
            previousRing = ring;
        }
    }

    @Test
    void keepsChunksDuringGracePeriodAndUnloadsAfterIt() {
        WorldChunkResidencyManager manager = new WorldChunkResidencyManager(2, 20);
        manager.update(List.of(new WorldChunkResidencyManager.PlayerChunk(0, 0)), 0);

        var grace = manager.update(List.of(), 10);
        assertTrue(grace.unload().isEmpty());
        assertEquals(13, grace.residentCount());

        var expired = manager.update(List.of(), 20);
        assertEquals(13, expired.unload().size());
        assertEquals(0, expired.residentCount());
    }

    @Test
    void publishesLockFreeReadSnapshotAfterLoadAndUnload() {
        WorldChunkResidencyManager manager = new WorldChunkResidencyManager(1, 0);

        manager.update(List.of(new WorldChunkResidencyManager.PlayerChunk(0, 0)), 0);
        assertTrue(manager.isResident(0, 0));
        assertTrue(manager.isResidentAtBlockPosition(0.5, 0.5));

        manager.update(List.of(), 1);
        assertFalse(manager.isResident(0, 0));
        assertFalse(manager.isResidentAtBlockPosition(0.5, 0.5));
        assertEquals(0, manager.residentChunkCount());
    }


    @Test
    void pinnedEntityChunksRemainResidentAfterPlayersLeave() {
        WorldChunkResidencyManager manager = new WorldChunkResidencyManager(1, 0);
        manager.update(List.of(new WorldChunkResidencyManager.PlayerChunk(0, 0)), 0);

        var pinned = manager.update(List.of(),
                java.util.Set.of(new WorldChunkResidencyManager.ChunkCoordinate(0, 0)), 1);
        assertEquals(4, pinned.unload().size(), "only the entity-pinned chunk should remain");
        assertTrue(manager.isResident(0, 0));
        assertEquals(1, pinned.residentCount());

        var released = manager.update(List.of(), java.util.Set.of(), 2);
        assertEquals(1, released.unload().size());
        assertFalse(manager.isResident(0, 0));
    }

    @Test
    void repeatedUpdatesKeepLoadAndUnloadPlansConsistent() {
        WorldChunkResidencyManager manager = new WorldChunkResidencyManager(1, 2);
        var initial = manager.update(List.of(new WorldChunkResidencyManager.PlayerChunk(-4, 7)), 0);
        assertEquals(5, initial.load().size());
        assertTrue(initial.unload().isEmpty());

        var unchanged = manager.update(List.of(new WorldChunkResidencyManager.PlayerChunk(-4, 7)), 1);
        assertTrue(unchanged.load().isEmpty());
        assertTrue(unchanged.unload().isEmpty());
        assertEquals(5, unchanged.residentCount());

        var grace = manager.update(List.of(), 2);
        assertTrue(grace.unload().isEmpty());
        var expired = manager.update(List.of(), 3);
        assertEquals(5, expired.unload().size());
        assertEquals(0, expired.residentCount());
    }

    @Test
    void overlappingPlayersShareChunkResidency() {
        WorldChunkResidencyManager manager = new WorldChunkResidencyManager(2, 20);
        var plan = manager.update(List.of(
                new WorldChunkResidencyManager.PlayerChunk(0, 0),
                new WorldChunkResidencyManager.PlayerChunk(1, 0)), 0);

        assertEquals(plan.residentCount(), plan.desiredCount());
        assertTrue(plan.residentCount() > 13);
        assertEquals(plan.residentCount(), manager.residentChunkCount());
    }
}
