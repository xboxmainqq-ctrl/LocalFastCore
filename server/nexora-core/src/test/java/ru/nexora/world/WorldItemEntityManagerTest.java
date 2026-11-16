package ru.nexora.world;

import org.junit.jupiter.api.Test;
import ru.nexora.inventory.ItemStack;
import ru.nexora.inventory.PlayerInventory;

import static org.junit.jupiter.api.Assertions.*;

class WorldItemEntityManagerTest {
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
    void rejectsInvalidPositionVelocityAndOwnerMetadata() {
        WorldItemEntityManager manager = new WorldItemEntityManager();
        assertThrows(IllegalArgumentException.class, () -> manager.spawn(
                new ItemStack("minecraft:diamond", 1, 64),
                Double.NaN, 65, 0, 0, 0, 0, 1000, 0, null));
        assertThrows(IllegalArgumentException.class, () -> manager.spawn(
                new ItemStack("minecraft:diamond", 1, 64),
                0, 65, 0, 0, 0, 0, 1000, 0, "../invalid"));
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
