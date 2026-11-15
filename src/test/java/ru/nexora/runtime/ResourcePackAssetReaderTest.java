package ru.nexora.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ResourcePackAssetReaderTest {
    @TempDir
    Path tempDir;

    private Path createPack(String name, String... entries) throws IOException {
        Path path = tempDir.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (int i = 0; i < entries.length; i += 2) {
                zip.putNextEntry(new ZipEntry(entries[i]));
                zip.write(entries[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return path;
    }

    private static String manifest() {
        return """
                {"id":"nexora:assets_test","version":"1.0.0","api_version":"1.0.0","type":"resource"}
                """;
    }

    @Test
    void readsValidatedBinaryAndUtf8AssetsWithoutExtracting() throws Exception {
        Path archive = createPack("assets.zip",
                "manifest.json", manifest(),
                "texts/welcome.txt", "Привет, NEXORA!",
                "textures/stone.bin", "image-data");
        ResourcePackArchiveValidator.ValidatedArchive validated =
                new ResourcePackArchiveValidator().validate(archive);

        try (ResourcePackAssetReader reader = new ResourcePackAssetReader(validated)) {
            assertEquals("Привет, NEXORA!", reader.readUtf8("texts/welcome.txt"));
            assertArrayEquals("image-data".getBytes(StandardCharsets.UTF_8),
                    reader.read("textures/stone.bin"));
            assertEquals(List.of("manifest.json", "texts/welcome.txt", "textures/stone.bin"),
                    reader.entries());
        }
        try (var files = Files.list(tempDir)) {
            assertEquals(1, files.count(), "reader must not extract pack entries");
        }
    }

    @Test
    void rejectsUnknownTraversalAndDirectoryPaths() throws Exception {
        Path archive = createPack("paths.zip",
                "manifest.json", manifest(),
                "textures/", "");
        ResourcePackArchiveValidator.ValidatedArchive validated =
                new ResourcePackArchiveValidator().validate(archive);

        try (ResourcePackAssetReader reader = new ResourcePackAssetReader(validated)) {
            assertThrows(IOException.class, () -> reader.read("../outside.txt"));
            assertThrows(IOException.class, () -> reader.read("/manifest.json"));
            assertThrows(IOException.class, () -> reader.read("textures/"));
            assertThrows(IOException.class, () -> reader.read("missing.png"));
        }
    }

    @Test
    void rejectsOversizedAssetsAndArchiveChangedAfterValidation() throws Exception {
        Path archive = createPack("bounded.zip",
                "manifest.json", manifest(),
                "large.txt", "x".repeat(32));
        ResourcePackArchiveValidator.ValidatedArchive validated =
                new ResourcePackArchiveValidator().validate(archive);

        try (ResourcePackAssetReader reader = new ResourcePackAssetReader(validated, 16)) {
            assertThrows(IOException.class, () -> reader.read("large.txt"));
        }

        createPack("replacement.zip",
                "manifest.json", manifest(),
                "different.txt", "changed");
        Path replacement = tempDir.resolve("replacement.zip");
        Files.copy(replacement, archive, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertThrows(IOException.class, () -> new ResourcePackAssetReader(validated));
    }

    @Test
    void rejectsMalformedUtf8TextAssets() throws Exception {
        Path archive = tempDir.resolve("invalid-text.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifest().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("broken.txt"));
            zip.write(new byte[]{(byte) 0xc3, (byte) 0x28});
            zip.closeEntry();
        }
        ResourcePackArchiveValidator.ValidatedArchive validated =
                new ResourcePackArchiveValidator().validate(archive);

        try (ResourcePackAssetReader reader = new ResourcePackAssetReader(validated)) {
            assertThrows(IOException.class, () -> reader.readUtf8("broken.txt"));
        }
    }

    @Test
    void rejectsInvalidLimitsAndClosedReader() throws Exception {
        Path archive = createPack("closed.zip", "manifest.json", manifest(), "a.txt", "a");
        ResourcePackArchiveValidator.ValidatedArchive validated =
                new ResourcePackArchiveValidator().validate(archive);

        assertThrows(IllegalArgumentException.class,
                () -> new ResourcePackAssetReader(validated, 0));
        ResourcePackAssetReader reader = new ResourcePackAssetReader(validated);
        reader.close();
        assertThrows(IOException.class, () -> reader.read("a.txt"));
    }
}
