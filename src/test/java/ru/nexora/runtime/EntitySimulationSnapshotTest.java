package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EntitySimulationSnapshotTest {
    @Test
    void simulationSnapshotIsDetachedFromLaterRegistryChanges() {
        EntityRegistry registry = new EntityRegistry();
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        registry.spawn(new WorldEntity(first, WorldEntity.Type.MOB, "First", 0, 64, 0, 0, -1));
        registry.spawn(new WorldEntity(second, WorldEntity.Type.ITEM, "Second", 1, 64, 1, 0, -1));

        var tickView = registry.simulationSnapshot();
        assertEquals(2, tickView.size());

        registry.remove(first);
        registry.spawn(new WorldEntity(UUID.randomUUID(), WorldEntity.Type.MOB,
                "Third", 2, 64, 2, 0, -1));

        assertEquals(2, tickView.size(), "a tick must keep its original detached view");
        assertEquals(2, tickView.stream().filter(entity -> entity.id().equals(first)
                || entity.id().equals(second)).count());
        assertEquals(2, registry.simulationSnapshot().size());
    }

    @Test
    void orderedPublicSnapshotRemainsDeterministic() {
        EntityRegistry registry = new EntityRegistry();
        UUID high = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        registry.spawn(new WorldEntity(high, WorldEntity.Type.MOB, "High", 0, 64, 0, 0, -1));
        registry.spawn(new WorldEntity(low, WorldEntity.Type.MOB, "Low", 1, 64, 1, 0, -1));

        assertEquals(low, registry.snapshot().get(0).id());
        assertEquals(high, registry.snapshot().get(1).id());
    }
}
