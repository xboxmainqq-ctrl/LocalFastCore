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

    @Test
    void enforcesResidentChunkCapacityAndDefersOuterLoads() {
        WorldChunkResidencyManager manager =
                new WorldChunkResidencyManager(2, 0, WorldChunkResidencyManager.Shape.CIRCULAR, 4);

        var first = manager.update(List.of(new WorldChunkResidencyManager.PlayerChunk(0, 0)), 0);
        assertEquals(4, first.load().size());
        assertEquals(4, first.residentCount());
        assertEquals(new WorldChunkResidencyManager.ChunkCoordinate(0, 0), first.load().get(0));

        var second = manager.update(List.of(new WorldChunkResidencyManager.PlayerChunk(0, 0)), 1);
        assertTrue(second.load().isEmpty(), "remaining desired chunks must stay deferred");
        assertEquals(4, second.residentCount());
        assertEquals(13, second.desiredCount());
    }

    @Test
    void releasesCapacityAndLoadsDeferredChunksOnLaterUpdate() {
        WorldChunkResidencyManager manager =
                new WorldChunkResidencyManager(1, 0, WorldChunkResidencyManager.Shape.CIRCULAR, 3);
        manager.update(List.of(new WorldChunkResidencyManager.PlayerChunk(0, 0)), 0);

        var moved = manager.update(List.of(new WorldChunkResidencyManager.PlayerChunk(10, 0)), 1);
        assertEquals(3, moved.unload().size());
        assertEquals(3, moved.load().size());
        assertEquals(3, moved.residentCount());
        assertTrue(moved.load().contains(new WorldChunkResidencyManager.ChunkCoordinate(10, 0)));
    }

    @Test
    void entityPinnedChunksRemainLoadedEvenWhenTheyExceedCapacity() {
        WorldChunkResidencyManager manager =
                new WorldChunkResidencyManager(1, 0, WorldChunkResidencyManager.Shape.CIRCULAR, 2);
        var pinned = manager.update(List.of(), java.util.Set.of(
                new WorldChunkResidencyManager.ChunkCoordinate(4, 4),
                new WorldChunkResidencyManager.ChunkCoordinate(5, 5),
                new WorldChunkResidencyManager.ChunkCoordinate(6, 6)), 0);

        assertEquals(3, pinned.load().size());
        assertEquals(3, pinned.residentCount(),
                "live entity chunks must not be dropped to satisfy a soft memory target");
    }

    @Test
    void rejectsNonPositiveResidentChunkCapacity() {
        assertThrows(IllegalArgumentException.class,
                () -> new WorldChunkResidencyManager(2, 0,
                        WorldChunkResidencyManager.Shape.CIRCULAR, 0));
    }
    @Test
    void capsWidelySeparatedPlayerViewsAndReusesCapacityAfterMovement() {
        WorldChunkResidencyManager manager = new WorldChunkResidencyManager(
                8, 0, WorldChunkResidencyManager.Shape.SQUARE, 1024);
        List<WorldChunkResidencyManager.PlayerChunk> players = new java.util.ArrayList<>();
        for (int index = 0; index < 128; index++) {
            players.add(new WorldChunkResidencyManager.PlayerChunk(index * 64, -index * 64, 8));
        }

        var first = manager.update(players, 0);
        assertEquals(1024, first.load().size());
        assertEquals(1024, first.residentCount());
        assertTrue(first.desiredCount() > 36_000);
        assertEquals(1024, manager.residentChunkCount());

        var steady = manager.update(players, 1);
        assertTrue(steady.load().isEmpty());
        assertEquals(1024, steady.residentCount());
        assertEquals(first.desiredCount(), steady.desiredCount());

        List<WorldChunkResidencyManager.PlayerChunk> moved = new java.util.ArrayList<>();
        for (int index = 0; index < 128; index++) {
            moved.add(new WorldChunkResidencyManager.PlayerChunk(index * 64 + 10_000,
                    -index * 64 - 10_000, 8));
        }
        var afterMove = manager.update(moved, 2);
        assertEquals(1024, afterMove.unload().size());
        assertEquals(1024, afterMove.load().size());
        assertEquals(1024, afterMove.residentCount());
        assertEquals(1024, manager.residentChunkCount());
    }

    @Test
    void pinnedChunksAreIncludedInLoadPlanBeforePlayerCapacityIsAllocated() {
        WorldChunkResidencyManager manager = new WorldChunkResidencyManager(
                2, 0, WorldChunkResidencyManager.Shape.SQUARE, 3);
        var plan = manager.update(
                List.of(new WorldChunkResidencyManager.PlayerChunk(0, 0)),
                java.util.Set.of(new WorldChunkResidencyManager.ChunkCoordinate(500, 500)), 0);

        assertTrue(plan.load().contains(new WorldChunkResidencyManager.ChunkCoordinate(500, 500)));
        assertEquals(3, plan.residentCount());
        assertTrue(plan.load().size() <= 3);
    }

}
