package ru.nexora.world;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class WorldChunkResidencyManagerTest {

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
