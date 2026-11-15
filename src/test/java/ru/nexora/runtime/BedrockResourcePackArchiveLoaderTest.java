package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class BedrockResourcePackArchiveLoaderTest {
    @Test
    void loadsResourcePacksInDependencyFirstOrderWithStableIds() throws Exception {
        Path root = Files.createTempDirectory("nexora-bedrock-packs");
        writePack(root.resolve("z-addon.zip"), """
                {"id":"nexora:addon","version":"1.0.0","api_version":"1.0.0",
                 "type":"resource","dependencies":[{"id":"nexora:core","version":"1.0.0"}]}
                """);
        writePack(root.resolve("a-core.zip"), """
                {"id":"nexora:core","version":"1.0.0","api_version":"1.0.0","type":"resource"}
                """);

        List<BedrockResourcePackTransferCodec.Pack> packs =
                new BedrockResourcePackArchiveLoader().load(root, "1.0.0");

        assertEquals(2, packs.size());
        UUID coreId = UUID.nameUUIDFromBytes("nexora:core".getBytes(StandardCharsets.UTF_8));
        UUID addonId = UUID.nameUUIDFromBytes("nexora:addon".getBytes(StandardCharsets.UTF_8));
        assertEquals(coreId + "_1.0.0", packs.get(0).resourceName());
        assertEquals(addonId + "_1.0.0", packs.get(1).resourceName());
        assertArrayEquals(Files.readAllBytes(root.resolve("a-core.zip")), packs.get(0).archive());
    }

    @Test
    void rejectsNonResourcePacksAndMissingDependencies() throws Exception {
        Path root = Files.createTempDirectory("nexora-invalid-packs");
        writePack(root.resolve("theme.zip"), """
                {"id":"nexora:theme","version":"1.0.0","api_version":"1.0.0","type":"theme"}
                """);
        assertThrows(java.io.IOException.class,
                () -> new BedrockResourcePackArchiveLoader().load(root, "1.0.0"));

        Files.delete(root.resolve("theme.zip"));
        writePack(root.resolve("missing.zip"), """
                {"id":"nexora:missing","version":"1.0.0","api_version":"1.0.0",
                 "type":"resource","dependencies":[{"id":"nexora:absent","version":"1.0.0"}]}
                """);
        assertThrows(java.io.IOException.class,
                () -> new BedrockResourcePackArchiveLoader().load(root, "1.0.0"));
    }

    @Test
    void missingPackDirectoryMeansNoConfiguredPacks() throws Exception {
        Path parent = Files.createTempDirectory("nexora-no-packs");
        assertTrue(new BedrockResourcePackArchiveLoader()
                .load(parent.resolve("resource-packs"), "1.0.0").isEmpty());
    }

    private static void writePack(Path destination, String manifest) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("textures/blocks/stone.png"));
            zip.write(new byte[] {1, 2, 3, 4});
            zip.closeEntry();
        }
        Files.write(destination, bytes.toByteArray());
    }
}
