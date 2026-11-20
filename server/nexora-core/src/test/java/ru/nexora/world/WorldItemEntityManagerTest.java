package ru.nexora.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import ru.nexora.inventory.ItemStack;
import ru.nexora.inventory.PlayerInventory;

import static org.junit.jupiter.api.Assertions.*;

class WorldItemEntityManagerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void spawnsAndPicksUpWholeStackWithinRadius() {
        WorldItemEntityManager manager = new WorldItemEntityManager();
        WorldItemEntity entity = manager.spawn(new ItemStack("minecraft:diamond", 3, 64),
                0, 65, 0, 0.1, 0.2, -0.1, 1000, 0, "player-xuid");
        assertTrue(entity.runtimeId() > 0);
        assertTrue(entity.uniqueId() < 0);
        assertEquals(1, manager.size());

        PlayerInventory inventory = new PlayerInventory();
        assertTrue(manager.pickup(entity.runtimeId(), inventory, "player-xuid",
                1.0, 65.0, 0.0, 1001));
        assertEquals(3, inventory.count("minecraft:diamond"));
        assertTrue(manager.find(entity.runtimeId()).isEmpty());
        assertEquals(0, manager.size());
    }

    @Test
    void enforcesPickupDelayOwnerLockAndDistance() {
        WorldItemEntityManager manager = new WorldItemEntityManager();
        WorldItemEntity entity = manager.spawn(new ItemStack("minecraft:iron_ingot", 2, 64),
                0, 65, 0, 0, 0, 0, 1000, 500, "owner-xuid");
        PlayerInventory inventory = new PlayerInventory();

        assertFalse(manager.pickup(entity.runtimeId(), inventory, "owner-xuid", 0, 65, 0, 1499));
        assertFalse(manager.pickup(entity.runtimeId(), inventory, "other-xuid", 0, 65, 0, 1600));
        assertFalse(manager.pickup(entity.runtimeId(), inventory, "owner-xuid", 2, 65, 0, 1600));
        assertTrue(manager.pickup(entity.runtimeId(), inventory, "owner-xuid", 0, 65, 0, 1600));
        assertEquals(2, inventory.count("minecraft:iron_ingot"));
    }

    @Test
    void ownerPickupLockDoesNotOverflowNearMaximumTimestamp() {
        WorldItemEntityManager manager = new WorldItemEntityManager();
        WorldItemEntity entity = manager.spawn(new ItemStack("minecraft:diamond", 1, 64),
                0, 65, 0, 0, 0, 0, Long.MAX_VALUE - 500, 500, "owner-xuid");
        PlayerInventory otherInventory = new PlayerInventory();
        PlayerInventory ownerInventory = new PlayerInventory();

        assertEquals(Long.MAX_VALUE, entity.pickupAvailableAtMillis());
        assertFalse(manager.pickup(entity.runtimeId(), otherInventory, "other-xuid",
                0, 65, 0, Long.MAX_VALUE));
        assertEquals(0, otherInventory.count("minecraft:diamond"));
        assertTrue(manager.pickup(entity.runtimeId(), ownerInventory, "owner-xuid",
                0, 65, 0, Long.MAX_VALUE));
        assertEquals(1, ownerInventory.count("minecraft:diamond"));
    }

    @Test
    void doesNotPartiallyInsertOrDeleteWhenInventoryCannotFitWholeStack() {
        WorldItemEntityManager manager = new WorldItemEntityManager();
        WorldItemEntity entity = manager.spawn(new ItemStack("minecraft:diamond", 2, 64),
                0, 65, 0, 0, 0, 0, 1000, 0, null);
        PlayerInventory inventory = new PlayerInventory(9);
        inventory.setSlot(0, new ItemStack("minecraft:diamond", 63, 64));
        for (int slot = 1; slot < inventory.size(); slot++) {
            inventory.setSlot(slot, new ItemStack("minecraft:iron_ingot", 64, 64));
        }

        assertFalse(manager.pickup(entity.runtimeId(), inventory, "player-xuid", 0, 65, 0, 1001));
        assertEquals(63, inventory.count("minecraft:diamond"));
        assertEquals(2, manager.find(entity.runtimeId()).orElseThrow().stack().count());
    }

    @Test
    void droppedItemFallsAndSettlesOnTheWorldSurface() {
        World world = new World("item-physics", 0L, new FlatWorldGenerator());
        WorldItemEntityManager manager = new WorldItemEntityManager();
        WorldItemEntity spawned = manager.spawn(new ItemStack("minecraft:stone", 1, 64),
                0.5, 70.0, 0.5, 0, 0, 0, 0, 0, null);

        for (int i = 0; i < 120; i++) manager.tick(world, 0.05);

        WorldItemEntity settled = manager.find(spawned.runtimeId()).orElseThrow();
        assertEquals(65.125, settled.y(), 0.001);
        assertEquals(0.0, settled.velocityY(), 0.000001);
        assertEquals(ru.nexora.world.BlockType.GRASS_BLOCK, world.getBlock(0, 64, 0));
    }

    @Test
    void persistsDroppedItemsAcrossRestartAndPersistsPickup() throws IOException {
        WorldItemEntityStorage storage = new WorldItemEntityStorage(temporaryDirectory, "test-world");
        WorldItemEntityManager first = new WorldItemEntityManager(storage);
        WorldItemEntity dropped = first.spawn(new ItemStack("minecraft:diamond", 4, 64,
                        java.util.Map.of("minecraft:unbreaking", 3), 7),
                2.5, 65.0, -1.5, 0.1, 0.2, -0.1, 10_000, 500, "owner-xuid");

        WorldItemEntityManager afterRestart = new WorldItemEntityManager(storage);
        WorldItemEntity restored = afterRestart.find(dropped.runtimeId()).orElseThrow();
        assertEquals(dropped, restored);
        assertEquals(java.util.Map.of("minecraft:unbreaking", 3), restored.stack().enchantments());
        assertEquals(7, restored.stack().repairCost());
        WorldItemEntity next = afterRestart.spawn(new ItemStack("minecraft:iron_ingot", 2, 64),
                0, 65, 0, 0, 0, 0, 10_000, 0, null);
        assertTrue(next.runtimeId() > dropped.runtimeId());

        PlayerInventory inventory = new PlayerInventory();
        assertTrue(afterRestart.pickup(next.runtimeId(), inventory, "another-player",
                0, 65, 0, 10_001));
        assertEquals(2, inventory.count("minecraft:iron_ingot"));

        WorldItemEntityManager afterPickupRestart = new WorldItemEntityManager(storage);
        assertTrue(afterPickupRestart.find(next.runtimeId()).isEmpty());
        assertEquals(dropped, afterPickupRestart.find(dropped.runtimeId()).orElseThrow());
    }

    @Test
    void restoresPreviousVerifiedDroppedItemSnapshotWhenPrimaryIsCorrupted() throws Exception {
        WorldItemEntityStorage storage = new WorldItemEntityStorage(temporaryDirectory, "item-recovery");
        WorldItemEntityManager manager = new WorldItemEntityManager(storage);
        WorldItemEntity first = manager.spawn(new ItemStack("minecraft:diamond", 2, 64),
                1, 65, 1, 0, 0, 0, 1000, 0, null);
        manager.spawn(new ItemStack("minecraft:iron_ingot", 3, 64),
                2, 65, 2, 0, 0, 0, 1000, 0, null);

        Path primary = temporaryDirectory.resolve("item-recovery/items/entities.nxe");
        Path backup = temporaryDirectory.resolve("item-recovery/items/entities.nxe.bak");
        byte[] damaged = Files.readAllBytes(primary);
        damaged[damaged.length / 2] ^= 1;
        Files.write(primary, damaged);

        assertEquals(List.of(first), storage.load());
        assertArrayEquals(Files.readAllBytes(backup), Files.readAllBytes(primary));
    }

    @Test
    void chunkIndexTracksSpawnMovementPickupMergeAndRemoval() {
        World world = new World("item-chunk-index", 0L, new FlatWorldGenerator());
        WorldItemEntityManager manager = new WorldItemEntityManager();
        long west = chunkKey(0, 0);
        long east = chunkKey(1, 0);
        WorldItemEntity crossing = manager.spawn(new ItemStack("minecraft:diamond", 1, 64),
                15.95, 65.125, 0.5, 4.0, 0, 0, 0, 0, null);

        assertEquals(List.of(crossing), manager.snapshotInChunkKeys(java.util.Set.of(west)));
        assertTrue(manager.snapshotInChunkKeys(java.util.Set.of(east)).isEmpty());

        manager.tick(world, 0.05, 100);
        WorldItemEntity moved = manager.find(crossing.runtimeId()).orElseThrow();
        assertTrue(moved.x() >= 16.0);
        assertTrue(manager.snapshotInChunkKeys(java.util.Set.of(west)).isEmpty());
        assertEquals(List.of(moved), manager.snapshotInChunkKeys(java.util.Set.of(east)));

        assertTrue(manager.remove(crossing.runtimeId()));
        assertTrue(manager.snapshotInChunkKeys(java.util.Set.of(east)).isEmpty());
    }

    @Test
    void chunkIndexIsRebuiltFromStorageAndUpdatedAfterPickupAndMerge() throws Exception {
        WorldItemEntityStorage storage = new WorldItemEntityStorage(temporaryDirectory, "item-chunk-index-load");
        WorldItemEntityManager manager = new WorldItemEntityManager(storage);
        WorldItemEntity first = manager.spawn(new ItemStack("minecraft:diamond", 2, 64),
                17, 65.125, 1, 0, 0, 0, 0, 0, "owner");
        WorldItemEntity second = manager.spawn(new ItemStack("minecraft:diamond", 3, 64),
                17.2, 65.125, 1, 0, 0, 0, 0, 0, "owner");
        WorldItemEntityManager restored = new WorldItemEntityManager(storage);
        long east = chunkKey(1, 0);

        assertEquals(List.of(first, second), restored.snapshotInChunkKeys(java.util.Set.of(east)));
        restored.tick(new World("item-chunk-index-merge", 0L, new FlatWorldGenerator()), 0.05, 100);
        assertEquals(1, restored.snapshotInChunkKeys(java.util.Set.of(east)).size());

        WorldItemEntity remaining = restored.snapshotInChunkKeys(java.util.Set.of(east)).getFirst();
        PlayerInventory inventory = new PlayerInventory();
        assertTrue(restored.pickup(remaining.runtimeId(), inventory, "owner", 17, 65.125, 1, 101));
        assertTrue(restored.snapshotInChunkKeys(java.util.Set.of(east)).isEmpty());
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xffff_ffffL);
    }

    @Test
    void mergesNearbyCompatibleStacksAndQueuesRemovedActorForClientSync() {
        World world = new World("item-merge", 0L, new FlatWorldGenerator());
        WorldItemEntityManager manager = new WorldItemEntityManager();
        WorldItemEntity first = manager.spawn(new ItemStack("minecraft:diamond", 2, 64),
                0.5, 65.125, 0.5, 0, 0, 0, 0, 0, "same-owner");
        WorldItemEntity second = manager.spawn(new ItemStack("minecraft:diamond", 3, 64),
                0.9, 65.125, 0.5, 0, 0, 0, 0, 0, "same-owner");

        List<WorldItemEntity> moved = manager.tick(world, 0.05, 100);

        assertEquals(1, manager.size());
        assertEquals(5, manager.find(first.runtimeId()).orElseThrow().stack().count());
        assertTrue(manager.find(second.runtimeId()).isEmpty());
        assertTrue(moved.stream().anyMatch(entity -> entity.runtimeId() == first.runtimeId()));
        assertEquals(List.of(second), manager.drainRemovedByTick());
    }

    @Test
    void mergesLargeSamePositionBatchUsingOneTickAndPreservesAllItems() {
        World world = new World("item-merge-batch", 0L, new FlatWorldGenerator());
        WorldItemEntityManager manager = new WorldItemEntityManager();
        for (int i = 0; i < 130; i++) {
            manager.spawn(new ItemStack("minecraft:diamond", 1, 64),
                    0.5, 65.125, 0.5, 0, 0, 0, 0, 0, "same-owner");
        }

        manager.tick(world, 0.05, 100);

        assertEquals(3, manager.size());
        assertEquals(130, manager.snapshot().stream()
                .mapToInt(entity -> entity.stack().count()).sum());
        assertEquals(List.of(64, 64, 2), manager.snapshot().stream()
                .map(entity -> entity.stack().count()).toList());
        assertEquals(127, manager.drainRemovedByTick().size());
    }

    @Test
    void keepsSpatiallySeparatedDroppedItemsInSeparateMergeBuckets() {
        World world = new World("item-merge-spatial-buckets", 0L, new FlatWorldGenerator());
        WorldItemEntityManager manager = new WorldItemEntityManager();
        for (int i = 0; i < 256; i++) {
            manager.spawn(new ItemStack("minecraft:diamond", 1, 64),
                    i * 3.0 + 0.5, 65.125, 0.5, 0, 0, 0, 0, 0, "same-owner");
        }

        manager.tick(world, 0.05, 100);

        assertEquals(256, manager.size());
        assertEquals(256, manager.snapshot().stream()
                .mapToInt(entity -> entity.stack().count()).sum());
        assertTrue(manager.drainRemovedByTick().isEmpty());
    }

    @Test
    void doesNotMergeStacksWithDifferentEnchantmentMetadata() {
        World world = new World("item-enchantment-merge", 0L, new FlatWorldGenerator());
        WorldItemEntityManager manager = new WorldItemEntityManager();
        WorldItemEntity plain = manager.spawn(new ItemStack("nexora:arcane_shard", 1, 1),
                0.5, 65.125, 0.5, 0, 0, 0, 0, 0, "same-owner");
        WorldItemEntity enchanted = manager.spawn(new ItemStack("nexora:arcane_shard", 1, 1,
                java.util.Map.of("minecraft:sharpness", 3), 1),
                0.9, 65.125, 0.5, 0, 0, 0, 0, 0, "same-owner");

        manager.tick(world, 0.05, 100);

        assertEquals(2, manager.size());
        assertEquals(plain.stack(), manager.find(plain.runtimeId()).orElseThrow().stack());
        assertEquals(enchanted.stack(), manager.find(enchanted.runtimeId()).orElseThrow().stack());
    }

    @Test
    void despawnsOldDroppedItemsAndQueuesRemovalForClientSync() {
        World world = new World("item-despawn", 0L, new FlatWorldGenerator());
        WorldItemEntityManager manager = new WorldItemEntityManager();
        WorldItemEntity old = manager.spawn(new ItemStack("minecraft:stone", 1, 64),
                0.5, 65.125, 0.5, 0, 0, 0, 1_000, 0, null);

        manager.tick(world, 0.05, 1_000 + WorldItemEntityManager.DEFAULT_DESPAWN_AGE_MILLIS);

        assertTrue(manager.find(old.runtimeId()).isEmpty());
        assertEquals(List.of(old), manager.drainRemovedByTick());
        assertEquals(0, manager.size());
    }

    @Test
    void rejectsInvalidPositionVelocityAndOwnerMetadata() {
        WorldItemEntityManager manager = new WorldItemEntityManager();
        assertThrows(IllegalArgumentException.class, () -> manager.spawn(
                new ItemStack("minecraft:diamond", 1, 64),
                Double.NaN, 65, 0, 0, 0, 0, 1000, 0, null));
        assertThrows(IllegalArgumentException.class, () -> manager.spawn(
                new ItemStack("minecraft:diamond", 1, 64),
                0, 65, 0, 0, 0, 0, 1000, 0, "../invalid"));
        assertThrows(IllegalArgumentException.class, () -> manager.spawn(
                new ItemStack("minecraft:diamond", 1, 64),
                0, WorldChunk.MAX_Y + 1.0, 0, 0, 0, 0, 1000, 0, null));
        assertThrows(IllegalArgumentException.class, () -> manager.spawn(
                new ItemStack("minecraft:diamond", 1, 64),
                0, 65, 0, 100.01, 0, 0, 1000, 0, null));
    }

    @Test
    void snapshotsAreStableAndRemovalIsIdempotent() {
        WorldItemEntityManager manager = new WorldItemEntityManager();
        WorldItemEntity first = manager.spawn(new ItemStack("minecraft:diamond", 1, 64),
                0, 65, 0, 0, 0, 0, 1000, 0, null);
        WorldItemEntity second = manager.spawn(new ItemStack("minecraft:iron_ingot", 1, 64),
                1, 65, 0, 0, 0, 0, 1000, 0, null);
        assertEquals(2, manager.snapshot().size());
        assertTrue(manager.remove(first.runtimeId()));
        assertFalse(manager.remove(first.runtimeId()));
        assertEquals(second.runtimeId(), manager.snapshot().getFirst().runtimeId());
    }
}
