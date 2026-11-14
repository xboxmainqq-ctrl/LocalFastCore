package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorldRegistryTest {
    @Test
    void registersAndFindsWorldsByTrimmedIdentifier() {
        WorldRegistry registry = new WorldRegistry();
        World world = new InMemoryWorld(" overworld ", new WorldHeight(-64, 320));

        registry.register(world);

        assertSame(world, registry.get("overworld").orElseThrow());
        assertSame(world, registry.get(" overworld ").orElseThrow());
        assertEquals(1, registry.size());
    }

    @Test
    void rejectsDuplicateIdentifiers() {
        WorldRegistry registry = new WorldRegistry();
        registry.register(new InMemoryWorld("overworld", new WorldHeight(-64, 320)));

        assertThrows(IllegalArgumentException.class,
                () -> registry.register(new InMemoryWorld(" overworld ", new WorldHeight(0, 256))));
        assertEquals(1, registry.size());
    }

    @Test
    void preservesRegistrationOrderAndReturnsImmutableSnapshots() {
        WorldRegistry registry = new WorldRegistry();
        World overworld = new InMemoryWorld("overworld", new WorldHeight(-64, 320));
        World nether = new InMemoryWorld("nether", new WorldHeight(0, 128));
        registry.register(overworld);
        registry.register(nether);

        List<World> snapshot = registry.worlds();
        assertEquals(List.of("overworld", "nether"), registry.ids());
        assertEquals(List.of(overworld, nether), snapshot);
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.add(new InMemoryWorld("end", new WorldHeight(0, 256))));
        assertThrows(UnsupportedOperationException.class, () -> registry.ids().add("end"));
    }

    @Test
    void unregistersOnlyTheRequestedWorldAndAllowsReRegistration() {
        WorldRegistry registry = new WorldRegistry();
        World overworld = new InMemoryWorld("overworld", new WorldHeight(-64, 320));
        registry.register(overworld);

        assertTrue(registry.unregister(" overworld "));
        assertFalse(registry.unregister("overworld"));
        assertTrue(registry.get("overworld").isEmpty());
        assertTrue(registry.isEmpty());

        registry.register(overworld);
        assertSame(overworld, registry.get("overworld").orElseThrow());
    }

    @Test
    void rejectsNullAndBlankIdentifiers() {
        WorldRegistry registry = new WorldRegistry();

        assertThrows(NullPointerException.class, () -> registry.register(null));
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(new InMemoryWorld(" ", new WorldHeight(0, 1))));
        assertThrows(NullPointerException.class, () -> registry.get(null));
        assertThrows(IllegalArgumentException.class, () -> registry.unregister(" "));
    }
}
