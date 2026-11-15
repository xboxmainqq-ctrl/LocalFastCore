package ru.nexora.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ResourcePackArchiveValidatorTest {
    @TempDir
    Path tempDir;

    private Path archive(String fileName, String... entries) throws IOException {
        Path path = tempDir.resolve(fileName);
        try (ZipOutputStream zip = new ZipOutputStream(java.nio.file.Files.newOutputStream(path))) {
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
                {"id":"nexora:test_pack","version":"1.0.0","api_version":"1.0.0","type":"resource"}
                """;
    }

    @Test
    void validatesManifestAndReturnsStableEntrySnapshot() throws Exception {
        Path path = archive("valid.zip",
                "textures/stone.png", "pixels",
                "manifest.json", manifest());

        ResourcePackArchiveValidator.ValidatedArchive result =
                new ResourcePackArchiveValidator().validate(path);

        assertEquals("nexora:test_pack", result.manifest().id());
        assertEquals(List.of("manifest.json", "textures/stone.png"), result.entries());
        assertEquals(manifest().getBytes(StandardCharsets.UTF_8).length + 6, result.expandedBytes());
        assertThrows(UnsupportedOperationException.class, () -> result.entries().add("other"));
    }

    @Test
    void rejectsTraversalAbsoluteWindowsAndNonPortablePaths() throws Exception {
        for (String badPath : List.of("../outside.txt", "textures/../../outside.txt",
                "/absolute.txt", "C:/drive.txt", "textures\\\\escape.txt",
                "textures/../escape.txt", "textures/trailing./file.txt")) {
            Path path = archive("bad-" + Math.abs(badPath.hashCode()) + ".zip",
                    "manifest.json", manifest(), badPath, "bad");
            assertThrows(IOException.class, () -> new ResourcePackArchiveValidator().validate(path),
                    badPath);
        }
    }

    @Test
    void rejectsMissingManifestAndCaseCollidingEntries() throws Exception {
        Path missing = archive("missing.zip", "textures/stone.png", "pixels");
        assertThrows(IOException.class, () -> new ResourcePackArchiveValidator().validate(missing));

        Path duplicate = archive("duplicate.zip",
                "manifest.json", manifest(),
                "Textures/stone.png", "a",
                "textures/STONE.png", "b");
        assertThrows(IOException.class, () -> new ResourcePackArchiveValidator().validate(duplicate));
    }

    @Test
    void enforcesExpandedEntryAndTotalLimits() throws Exception {
        Path path = archive("large.zip", "manifest.json", manifest(), "large.bin", "0123456789");
        ResourcePackArchiveValidator.Limits perEntry = new ResourcePackArchiveValidator.Limits(
                1024 * 1024, 10, 8, 1024, 256);
        assertThrows(IOException.class,
                () -> new ResourcePackArchiveValidator(perEntry).validate(path));

        ResourcePackArchiveValidator.Limits total = new ResourcePackArchiveValidator.Limits(
                1024 * 1024, 10, 1024, 16, 256);
        assertThrows(IOException.class,
                () -> new ResourcePackArchiveValidator(total).validate(path));
    }

    @Test
    void rejectsInvalidUtf8Manifest() throws Exception {
        Path path = tempDir.resolve("invalid-utf8.zip");
        try (ZipOutputStream zip = new ZipOutputStream(java.nio.file.Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(new byte[]{(byte) 0xc3, (byte) 0x28});
            zip.closeEntry();
        }
        assertThrows(IOException.class, () -> new ResourcePackArchiveValidator().validate(path));
    }
}
