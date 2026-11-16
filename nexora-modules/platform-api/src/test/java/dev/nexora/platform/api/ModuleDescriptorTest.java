package dev.nexora.platform.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModuleDescriptorTest {
    @Test
    void acceptsValidDescriptorAndCopiesDependencies() {
        var dependencies = new java.util.ArrayList<>(List.of("nexora.permissions"));
        var descriptor = new ModuleDescriptor(
                "nexora.example", "Example", "1.2.0", "0.1", dependencies
        );

        dependencies.add("nexora.world");

        assertEquals(List.of("nexora.permissions"), descriptor.dependencies());
        assertThrows(UnsupportedOperationException.class,
                () -> descriptor.dependencies().add("nexora.world"));
    }

    @Test
    void rejectsInvalidIdsAndSelfDependencies() {
        assertThrows(IllegalArgumentException.class,
                () -> new ModuleDescriptor("Bad Id", "Bad", "1", "0.1", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ModuleDescriptor("nexora.example", "Example", "1", "0.1",
                        List.of("nexora.example")));
    }

    @Test
    void rejectsDuplicateDependencies() {
        assertThrows(IllegalArgumentException.class,
                () -> new ModuleDescriptor("nexora.example", "Example", "1", "0.1",
                        List.of("nexora.world", "nexora.world")));
    }
}
