package ru.nexora.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ResourcePackAssetCatalogTest {
    @TempDir Path tempDir;

    private Path pack(String file, String id, String body) throws IOException {
        return pack(file, id, body, "");
    }

    private Path pack(String file, String id, String body, String extraManifestFields) throws IOException {
        Path path = tempDir.resolve(file);
        String manifest = "{\"id\":\"" + id + "\",\"version\":\"1.0.0\","
                + "\"api_version\":\"1.0.0\",\"type\":\"resource\"" + extraManifestFields + "}";
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("textures/shared.txt"));
            zip.write(body.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return path;
    }

    private ResourcePackManifest manifest(String id, ResourcePackManifest.Dependency... deps) {
        return new ResourcePackManifest(id, "1.0.0", "1.0.0",
                ResourcePackManifest.Type.RESOURCE, List.of(deps), Set.of());
    }

    @Test
    void resolvesDependentPackOverridesAndReadsNamedPack() throws Exception {
        var validator = new ResourcePackArchiveValidator();
        var base = validator.validate(pack("base.zip", "nexora:base", "base-texture"));
        var theme = validator.validate(pack("theme.zip", "nexora:theme", "theme-texture",
                ",\"dependencies\":[{\"id\":\"nexora:base\",\"version\":\"1.0.0\"}]"));

        ResourcePackManager manager = new ResourcePackManager("1.0.0");
        manager.add(manifest("nexora:theme",
                new ResourcePackManifest.Dependency("nexora:base", "1.0.0")));
        manager.add(manifest("nexora:base"));
        manager.freezeAndResolve();

        Map<String, ResourcePackArchiveValidator.ValidatedArchive> archives = new LinkedHashMap<>();
        archives.put("nexora:base", base);
        archives.put("nexora:theme", theme);

        try (ResourcePackAssetCatalog catalog = ResourcePackAssetCatalog.open(manager, archives)) {
            assertEquals(List.of("nexora:base", "nexora:theme"), catalog.loadOrder());
            assertEquals("base-texture", new String(
                    catalog.read("nexora:base", "textures/shared.txt"), StandardCharsets.UTF_8));
            var resolved = catalog.find("textures/shared.txt").orElseThrow();
            assertEquals("nexora:theme", resolved.packId());
            assertEquals("theme-texture", new String(resolved.bytes(), StandardCharsets.UTF_8));
            byte[] copy = resolved.bytes();
            copy[0] = 0;
            assertEquals("theme-texture", new String(resolved.bytes(), StandardCharsets.UTF_8));
            assertTrue(catalog.find("missing.png").isEmpty());
        }
    }

    @Test
    void requiresFrozenManagerAndExactMatchingArchiveSet() throws Exception {
        var validator = new ResourcePackArchiveValidator();
        var base = validator.validate(pack("base.zip", "nexora:base", "base"));
        ResourcePackManager manager = new ResourcePackManager("1.0.0");
        manager.add(manifest("nexora:base"));
        Map<String, ResourcePackArchiveValidator.ValidatedArchive> archives = Map.of("nexora:base", base);

        assertThrows(IllegalStateException.class, () -> ResourcePackAssetCatalog.open(manager, archives));
        manager.freezeAndResolve();
        assertThrows(IllegalArgumentException.class,
                () -> ResourcePackAssetCatalog.open(manager, Map.of()));
    }

    @Test
    void rejectsManifestMismatchAndClosesOpenedReadersOnFailure() throws Exception {
        var validator = new ResourcePackArchiveValidator();
        var base = validator.validate(pack("base.zip", "nexora:other", "base"));
        ResourcePackManager manager = new ResourcePackManager("1.0.0");
        manager.add(manifest("nexora:base"));
        manager.freezeAndResolve();

        assertThrows(IOException.class,
                () -> ResourcePackAssetCatalog.open(manager, Map.of("nexora:base", base)));
    }

    @Test
    void rejectsReadsAfterClose() throws Exception {
        var validator = new ResourcePackArchiveValidator();
        var base = validator.validate(pack("base.zip", "nexora:base", "base"));
        ResourcePackManager manager = new ResourcePackManager("1.0.0");
        manager.add(manifest("nexora:base"));
        manager.freezeAndResolve();
        ResourcePackAssetCatalog catalog = ResourcePackAssetCatalog.open(
                manager, Map.of("nexora:base", base));
        catalog.close();
        assertThrows(IOException.class, () -> catalog.read("nexora:base", "textures/shared.txt"));
    }
}
