package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EntityRegistryTest {
    @Test
    void spawnsFindsAndRemovesEntities() {
        EntityRegistry registry = new EntityRegistry();
        UUID id = UUID.randomUUID();
        WorldEntity player = WorldEntity.player(id, "NexoraPlayer", 1, 65, 2, 90, 3);

        registry.spawn(player);

        assertEquals(1, registry.size());
        assertEquals(player, registry.find(id).orElseThrow());
        assertEquals(player, registry.remove(id).orElseThrow());
        assertTrue(registry.isEmpty());
        assertTrue(registry.find(id).isEmpty());
    }

    @Test
    void rejectsDuplicateEntityIds() {
        EntityRegistry registry = new EntityRegistry();
        UUID id = UUID.randomUUID();
        registry.spawn(WorldEntity.player(id, "First", 0, 64, 0, 0, 0));

        assertThrows(IllegalStateException.class,
                () -> registry.spawn(WorldEntity.player(id, "Second", 1, 64, 0, 0, 1)));
        assertEquals("First", registry.find(id).orElseThrow().name());
    }

    @Test
    void updatesPositionAtomicallyAndPreservesEntityMetadata() {
        EntityRegistry registry = new EntityRegistry();
        UUID id = UUID.randomUUID();
        registry.spawn(WorldEntity.player(id, "Player", 0, 64, 0, 0, 4));

        assertTrue(registry.updatePosition(id, 4.5, 70, -2, -45));

        WorldEntity updated = registry.find(id).orElseThrow();
        assertEquals(4.5, updated.x());
        assertEquals(70, updated.y());
        assertEquals(-2, updated.z());
        assertEquals(-45, updated.yaw());
        assertEquals("Player", updated.name());
        assertEquals(4, updated.skinIndex());
        assertFalse(registry.updatePosition(UUID.randomUUID(), 0, 0, 0, 0));
    }

    @Test
    void returnsDetachedStableSnapshots() {
        EntityRegistry registry = new EntityRegistry();
        WorldEntity first = WorldEntity.player(UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "Second", 0, 64, 0, 0, 0);
        WorldEntity second = WorldEntity.player(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "First", 1, 64, 0, 0, 1);
        registry.spawn(first);
        registry.spawn(second);

        List<WorldEntity> snapshot = registry.snapshot();

        assertEquals(List.of(second, first), snapshot);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(first));
        registry.remove(second.id());
        assertEquals(2, snapshot.size());
        assertEquals(1, registry.snapshot().size());
    }

    @Test
    void findsNearbyEntitiesByDistanceAndRadius() {
        EntityRegistry registry = new EntityRegistry();
        WorldEntity far = new WorldEntity(UUID.randomUUID(), WorldEntity.Type.MOB,
                "Zombie", 4, 64, 0, 0, -1);
        WorldEntity near = WorldEntity.player(UUID.randomUUID(), "Player", 1, 64, 0, 0, 2);
        WorldEntity outside = new WorldEntity(UUID.randomUUID(), WorldEntity.Type.ITEM,
                "Dropped item", 8, 64, 0, 0, -1);
        registry.spawn(far);
        registry.spawn(outside);
        registry.spawn(near);

        assertEquals(List.of(near, far), registry.within(0, 64, 0, 4));
        assertTrue(registry.within(0, 64, 0, 0.5).isEmpty());
    }

    @Test
    void validatesEntityDataAndSearchArguments() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
                () -> WorldEntity.player(id, " ", 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> WorldEntity.player(id, "Player", Double.NaN, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> WorldEntity.player(id, "Player", 0, 0, 0, 0, 5));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldEntity(id, WorldEntity.Type.MOB, "Mob", 0, 0, 0, 0, 0));

        EntityRegistry registry = new EntityRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.within(0, 0, 0, -1));
    }
}
