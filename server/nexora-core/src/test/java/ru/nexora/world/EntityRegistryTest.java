package ru.nexora.world;

import org.junit.jupiter.api.Test;
import ru.nexora.runtime.StateOwnership;

import static org.junit.jupiter.api.Assertions.*;

class EntityRegistryTest {
    @Test
    void registryOwnsEntityMembership() {
        StateOwnership ownership = new StateOwnership();
        EntityRegistry registry = new EntityRegistry(ownership);
        EntityState entity = new EntityState(ownership);

        registry.add(42L, entity);

        assertEquals(1, registry.size());
        assertSame(entity, registry.get(42L));
        assertSame(entity, registry.remove(42L));
        assertEquals(0, registry.size());
    }
}
