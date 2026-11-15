package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ResourcePackManagerTest {
    private static ResourcePackManifest pack(String id, String version,
                                             ResourcePackManifest.Dependency... dependencies) {
        return new ResourcePackManifest(id, version, "1.0.0", ResourcePackManifest.Type.RESOURCE,
                List.of(dependencies), Set.of());
    }

    @Test
    void resolvesDependenciesBeforeDependentsIndependentOfDiscoveryOrder() {
        ResourcePackManager first = new ResourcePackManager("1.0.0");
        ResourcePackManager second = new ResourcePackManager("1.0.0");
        ResourcePackManifest ui = pack("nexora:ui", "1.0.0",
                new ResourcePackManifest.Dependency("nexora:base", "1.0.0"));
        ResourcePackManifest world = pack("nexora:world", "1.0.0",
                new ResourcePackManifest.Dependency("nexora:base", "1.0.0"));
        ResourcePackManifest base = pack("nexora:base", "1.0.0");

        first.addAll(List.of(ui, world, base));
        second.addAll(List.of(base, world, ui));

        List<String> expected = List.of("nexora:base", "nexora:ui", "nexora:world");
        assertEquals(expected, first.resolveOrder().stream().map(ResourcePackManifest::id).toList());
        assertEquals(expected, second.resolveOrder().stream().map(ResourcePackManifest::id).toList());
    }

    @Test
    void rejectsMissingAndMismatchedDependencies() {
        ResourcePackManager missing = new ResourcePackManager("1.0.0");
        missing.add(pack("nexora:ui", "1.0.0",
                new ResourcePackManifest.Dependency("nexora:base", "1.0.0")));
        assertThrows(IllegalArgumentException.class, missing::resolveOrder);

        ResourcePackManager mismatch = new ResourcePackManager("1.0.0");
        mismatch.add(pack("nexora:base", "2.0.0"));
        mismatch.add(pack("nexora:ui", "1.0.0",
                new ResourcePackManifest.Dependency("nexora:base", "1.0.0")));
        assertThrows(IllegalArgumentException.class, mismatch::resolveOrder);
    }

    @Test
    void rejectsCyclesAndDuplicatePackIds() {
        ResourcePackManager cyclic = new ResourcePackManager("1.0.0");
        cyclic.add(pack("nexora:a", "1.0.0",
                new ResourcePackManifest.Dependency("nexora:b", "1.0.0")));
        cyclic.add(pack("nexora:b", "1.0.0",
                new ResourcePackManifest.Dependency("nexora:a", "1.0.0")));
        assertThrows(IllegalArgumentException.class, cyclic::resolveOrder);

        ResourcePackManager duplicates = new ResourcePackManager("1.0.0");
        duplicates.add(pack("nexora:base", "1.0.0"));
        assertThrows(IllegalArgumentException.class,
                () -> duplicates.add(pack("nexora:base", "2.0.0")));
    }

    @Test
    void enforcesApiVersionAndBatchRegistrationIsAtomic() {
        ResourcePackManager manager = new ResourcePackManager("1.0.0");
        ResourcePackManifest compatible = pack("nexora:good", "1.0.0");
        ResourcePackManifest incompatible = new ResourcePackManifest("nexora:bad", "1.0.0", "2.0.0",
                ResourcePackManifest.Type.RESOURCE, List.of(), Set.of());

        assertThrows(IllegalArgumentException.class, () -> manager.addAll(List.of(compatible, incompatible)));
        assertTrue(manager.snapshot().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new ResourcePackManager("not-a-version"));
    }

    @Test
    void failedResolutionDoesNotFreezeManagerAndCanBeCorrected() {
        ResourcePackManager manager = new ResourcePackManager("1.0.0");
        manager.add(pack("nexora:ui", "1.0.0",
                new ResourcePackManifest.Dependency("nexora:base", "1.0.0")));

        assertThrows(IllegalArgumentException.class, manager::freezeAndResolve);
        assertFalse(manager.isFrozen());

        manager.add(pack("nexora:base", "1.0.0"));
        assertEquals(List.of("nexora:base", "nexora:ui"),
                manager.freezeAndResolve().stream().map(ResourcePackManifest::id).toList());
        assertTrue(manager.isFrozen());
    }

    @Test
    void frozenPlanAndSnapshotAreImmutableAndRegistrationIsClosed() {
        ResourcePackManager manager = new ResourcePackManager("1.0.0");
        manager.add(pack("nexora:base", "1.0.0"));

        List<ResourcePackManifest> plan = manager.freezeAndResolve();

        assertSame(plan, manager.freezeAndResolve());
        assertThrows(UnsupportedOperationException.class, () -> plan.add(pack("nexora:other", "1.0.0")));
        assertThrows(UnsupportedOperationException.class,
                () -> manager.snapshot().put("nexora:other", pack("nexora:other", "1.0.0")));
        assertThrows(IllegalStateException.class, () -> manager.add(pack("nexora:other", "1.0.0")));
    }
}
