package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ResourcePackManifestTest {
    @Test
    void parsesPackMetadataDependenciesAndCapabilities() {
        ResourcePackManifest manifest = ResourcePackManifest.parse("""
                {
                  "id":"nexora:crystal_ui",
                  "version":"1.2.0",
                  "api_version":"1.0.0",
                  "type":"theme",
                  "dependencies":[{"id":"nexora:base_assets","version":"1.0.0"}],
                  "capabilities":["textures","ui"]
                }
                """);

        assertEquals("nexora:crystal_ui", manifest.id());
        assertEquals("1.2.0", manifest.version());
        assertEquals("1.0.0", manifest.apiVersion());
        assertEquals(ResourcePackManifest.Type.THEME, manifest.type());
        assertEquals(List.of(new ResourcePackManifest.Dependency("nexora:base_assets", "1.0.0")),
                manifest.dependencies());
        assertEquals(Set.of("textures", "ui"), manifest.capabilities());
    }

    @Test
    void rejectsMalformedOrUnknownManifestFields() {
        assertThrows(IllegalArgumentException.class, () -> ResourcePackManifest.parse("[]"));
        assertThrows(IllegalArgumentException.class, () -> ResourcePackManifest.parse("{}"));
        assertThrows(IllegalArgumentException.class, () -> ResourcePackManifest.parse("""
                {"id":"nexora:test","version":"1.0","api_version":"1.0.0","type":"resource"}
                """));
        assertThrows(IllegalArgumentException.class, () -> ResourcePackManifest.parse("""
                {"id":"nexora:test","version":"1.0.0","api_version":"1.0.0","type":"script"}
                """));
        assertThrows(IllegalArgumentException.class, () -> ResourcePackManifest.parse("""
                {"id":"nexora:test","version":"1.0.0","api_version":"1.0.0","type":"resource","unexpected":true}
                """));
        assertThrows(IllegalArgumentException.class, () -> ResourcePackManifest.parse("""
                {"id":"nexora:test","id":"nexora:other","version":"1.0.0","api_version":"1.0.0","type":"resource"}
                """));
    }

    @Test
    void rejectsUnsafeIdsInvalidCapabilitiesAndDuplicateDependencies() {
        assertThrows(IllegalArgumentException.class, () -> new ResourcePackManifest(
                "../escape", "1.0.0", "1.0.0", ResourcePackManifest.Type.RESOURCE, List.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new ResourcePackManifest(
                "nexora:test", "1.0.0", "1.0.0", ResourcePackManifest.Type.RESOURCE,
                List.of(new ResourcePackManifest.Dependency("nexora:base", "1.0.0"),
                        new ResourcePackManifest.Dependency("nexora:base", "1.0.0")), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new ResourcePackManifest(
                "nexora:test", "1.0.0", "1.0.0", ResourcePackManifest.Type.RESOURCE,
                List.of(new ResourcePackManifest.Dependency("nexora:test", "1.0.0")), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new ResourcePackManifest(
                "nexora:test", "1.0.0", "1.0.0", ResourcePackManifest.Type.RESOURCE,
                List.of(), Set.of("../code")));
    }

    @Test
    void exposesImmutableDependencyAndCapabilityCollections() {
        ResourcePackManifest manifest = new ResourcePackManifest(
                "nexora:test", "1.0.0", "1.0.0", ResourcePackManifest.Type.RESOURCE,
                List.of(), Set.of("textures"));
        assertThrows(UnsupportedOperationException.class,
                () -> manifest.dependencies().add(new ResourcePackManifest.Dependency("nexora:base", "1.0.0")));
        assertThrows(UnsupportedOperationException.class, () -> manifest.capabilities().add("scripts"));
    }
}
