package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EntityTickEngineTest {
    @Test
    void droppedItemFallsAndSettlesOnLoadedGround() {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(0, 128));
        world.setBlock(new BlockPosition(0, 63, 0), new BlockState("minecraft:stone"));
        EntityRegistry entities = new EntityRegistry();
        UUID itemId = UUID.randomUUID();
        entities.spawn(new WorldEntity(itemId, WorldEntity.Type.ITEM,
                "Dropped item", 0.5, 66, 0.5, 0, -1));
        EntityTickEngine engine = new EntityTickEngine(entities, world);

        for (int i = 0; i < 20; i++) {
            engine.tick();
        }

        WorldEntity item = entities.find(itemId).orElseThrow();
        assertEquals(64.0, item.y(), 0.0001);
        assertEquals(0, engine.tick() .despawnedItems());
    }

    @Test
    void droppedItemsDespawnAfterConfiguredLifetimeEvenInUnloadedChunks() {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(0, 128));
        EntityRegistry entities = new EntityRegistry();
        UUID itemId = UUID.randomUUID();
        entities.spawn(new WorldEntity(itemId, WorldEntity.Type.ITEM,
                "Dropped item", 1000, 70, 1000, 0, -1));
        EntityTickEngine engine = new EntityTickEngine(entities, world, 2);

        assertEquals(0, engine.tick().despawnedItems());
        assertEquals(1, entities.size());
        assertEquals(1, engine.tick().despawnedItems());
        assertTrue(entities.find(itemId).isEmpty());
    }

    @Test
    void passiveMobWandersOnlyWhenThereIsSafeGround() {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(0, 128));
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                world.setBlock(new BlockPosition(x, 63, z), new BlockState("minecraft:grass_block"));
            }
        }
        EntityRegistry entities = new EntityRegistry();
        UUID mobId = UUID.fromString("00000000-0000-0000-0000-000000000123");
        entities.spawn(new WorldEntity(mobId, WorldEntity.Type.MOB,
                "Sheep", 0.5, 64, 0.5, 0, -1));
        EntityTickEngine engine = new EntityTickEngine(entities, world);
        double startX = 0.5;
        double startZ = 0.5;

        for (int i = 0; i < 100; i++) {
            engine.tick();
        }

        WorldEntity mob = entities.find(mobId).orElseThrow();
        assertTrue(Math.abs(mob.x() - startX) > 0.001 || Math.abs(mob.z() - startZ) > 0.001);
        assertEquals(64, mob.y(), 0.0001);
    }

    @Test
    void mobsAndItemsDoNotMoveInUnloadedChunks() {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(0, 128));
        EntityRegistry entities = new EntityRegistry();
        UUID mobId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        entities.spawn(new WorldEntity(mobId, WorldEntity.Type.MOB, "Cow", 32.5, 64, 32.5, 0, -1));
        entities.spawn(new WorldEntity(itemId, WorldEntity.Type.ITEM, "Dropped item", 32.5, 70, 32.5, 0, -1));
        EntityTickEngine engine = new EntityTickEngine(entities, world);

        for (int i = 0; i < 10; i++) {
            engine.tick();
        }

        assertEquals(32.5, entities.find(mobId).orElseThrow().x(), 0.0001);
        assertEquals(70, entities.find(itemId).orElseThrow().y(), 0.0001);
    }

    @Test
    void rejectsInvalidConfiguration() {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(0, 128));
        assertThrows(IllegalArgumentException.class,
                () -> new EntityTickEngine(new EntityRegistry(), world, 0));
    }
}
