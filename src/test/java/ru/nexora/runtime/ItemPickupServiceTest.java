package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ItemPickupServiceTest {
    @Test
    void collectsNearbyItemsAndMergesThemIntoStacks() {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(0, 128));
        world.setBlock(new BlockPosition(0, 63, 0), new BlockState("minecraft:stone"));
        EntityRegistry entities = new EntityRegistry();
        UUID playerId = UUID.randomUUID();
        PlayerInventory inventory = new PlayerInventory(playerId);
        UUID first = spawnItem(entities, "minecraft:apple", 0.5, 64, 0.5);
        UUID second = spawnItem(entities, "minecraft:apple", 1.0, 64, 0.5);
        spawnItem(entities, "minecraft:diamond", 0.5, 64, 1.0);
        ItemPickupService service = new ItemPickupService(entities, world);

        ItemPickupService.PickupResult result = service.pickup(playerId, 0.5, 64, 0.5, inventory);

        assertEquals(3, result.collected());
        assertEquals(2, inventory.occupiedSlots());
        assertEquals(2, inventory.count("minecraft:apple"));
        assertEquals(1, inventory.count("minecraft:diamond"));
        assertTrue(entities.find(first).isEmpty());
        assertTrue(entities.find(second).isEmpty());
        assertEquals(0, entities.size());
    }

    @Test
    void leavesItemsOutsidePickupRadiusAndNonItemEntitiesUntouched() {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(0, 128));
        world.setBlock(new BlockPosition(0, 63, 0), new BlockState("minecraft:stone"));
        EntityRegistry entities = new EntityRegistry();
        UUID playerId = UUID.randomUUID();
        PlayerInventory inventory = new PlayerInventory(playerId);
        UUID farItem = spawnItem(entities, "minecraft:apple", 4, 64, 0);
        UUID mobId = UUID.randomUUID();
        entities.spawn(new WorldEntity(mobId, WorldEntity.Type.MOB, "Cow", 0.5, 64, 0.5, 0, -1));

        ItemPickupService.PickupResult result =
                new ItemPickupService(entities, world).pickup(playerId, 0.5, 64, 0.5, inventory);

        assertEquals(0, result.collected());
        assertTrue(entities.find(farItem).isPresent());
        assertTrue(entities.find(mobId).isPresent());
        assertEquals(0, inventory.occupiedSlots());
    }

    @Test
    void fullInventoryLeavesDroppedItemsOnGround() {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(0, 128));
        world.setBlock(new BlockPosition(0, 63, 0), new BlockState("minecraft:stone"));
        EntityRegistry entities = new EntityRegistry();
        UUID playerId = UUID.randomUUID();
        PlayerInventory inventory = new PlayerInventory(playerId, 1);
        for (int i = 0; i < 64; i++) {
            assertTrue(inventory.addOne("minecraft:stone"));
        }
        UUID remaining = spawnItem(entities, "minecraft:apple", 0.5, 64, 0.5);

        ItemPickupService.PickupResult result =
                new ItemPickupService(entities, world).pickup(playerId, 0.5, 64, 0.5, inventory);

        assertEquals(0, result.collected());
        assertTrue(result.inventoryFull());
        assertTrue(entities.find(remaining).isPresent());
        assertEquals(64, inventory.count("minecraft:stone"));
    }

    @Test
    void itemEntityInUnloadedChunkCannotBeCollected() {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(0, 128));
        world.setBlock(new BlockPosition(0, 63, 0), new BlockState("minecraft:stone"));
        EntityRegistry entities = new EntityRegistry();
        UUID playerId = UUID.randomUUID();
        PlayerInventory inventory = new PlayerInventory(playerId);
        UUID unloaded = spawnItem(entities, "minecraft:apple", 32.5, 64, 0.5);

        ItemPickupService.PickupResult result =
                new ItemPickupService(entities, world).pickup(playerId, 32.5, 64, 0.5, inventory);

        assertEquals(0, result.collected());
        assertTrue(entities.find(unloaded).isPresent());
        assertEquals(0, inventory.occupiedSlots());
    }

    @Test
    void rejectsInventoryOwnershipMismatchAndInvalidPickupRadius() {
        UUID playerId = UUID.randomUUID();
        PlayerInventory otherInventory = new PlayerInventory(UUID.randomUUID());
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(0, 128));
        ItemPickupService service = new ItemPickupService(new EntityRegistry(), world);

        assertThrows(IllegalArgumentException.class,
                () -> service.pickup(playerId, 0, 64, 0, otherInventory));
        assertThrows(IllegalArgumentException.class,
                () -> new ItemPickupService(new EntityRegistry(), world, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ItemPickupService(new EntityRegistry(), world, Double.NaN));
    }

    @Test
    void inventoryStacksUpTo64AndUsesNextSlot() {
        PlayerInventory inventory = new PlayerInventory(UUID.randomUUID(), 2);

        for (int i = 0; i < 65; i++) {
            assertTrue(inventory.addOne("minecraft:apple"));
        }

        assertEquals(65, inventory.count("minecraft:apple"));
        assertEquals(2, inventory.occupiedSlots());
        assertEquals(2, inventory.snapshot().size());
        assertEquals(64, inventory.snapshot().get(0).count());
        assertEquals(1, inventory.snapshot().get(1).count());
        assertFalse(inventory.isFull());
        assertFalse(inventory.addOne("minecraft:diamond"));
        for (int i = 0; i < 63; i++) {
            assertTrue(inventory.addOne("minecraft:apple"));
        }
        assertTrue(inventory.isFull());
        assertFalse(inventory.addOne("minecraft:gold_ingot"));
    }

    private static UUID spawnItem(EntityRegistry entities, String itemId,
                                  double x, double y, double z) {
        UUID id = UUID.randomUUID();
        entities.spawn(new WorldEntity(id, WorldEntity.Type.ITEM, itemId, x, y, z, 0, -1));
        return id;
    }
}
