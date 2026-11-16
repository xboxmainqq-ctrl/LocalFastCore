package ru.nexora.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds the built-in Bedrock resource pack that replaces vanilla hearts with the NEXORA HP bar. */
public final class BedrockHudResourcePack {
    private static final UUID PACK_ID = UUID.fromString("2b9b8df2-3a24-4e6b-8d15-7d45c1b2a903");
    private static final String VERSION = "1.0.0";
    private static final Map<String, String> ASSETS = Map.of(
            "manifest.json", "/bedrock/nexora-hud/manifest.json",
            "ui/hud_screen.json", "/bedrock/nexora-hud/ui/hud_screen.json"
    );

    private BedrockHudResourcePack() {
    }

    /** Returns an immutable protocol transfer snapshot for the bundled HUD pack. */
    public static BedrockResourcePackTransferCodec.Pack create() {
        try {
            ByteArrayOutputStream archive = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(archive, StandardCharsets.UTF_8)) {
                for (Map.Entry<String, String> asset : new LinkedHashMap<>(ASSETS).entrySet()) {
                    byte[] bytes = readAsset(asset.getValue());
                    ZipEntry entry = new ZipEntry(asset.getKey());
                    entry.setTime(0L);
                    zip.putNextEntry(entry);
                    zip.write(bytes);
                    zip.closeEntry();
                }
            }
            return new BedrockResourcePackTransferCodec.Pack(PACK_ID, VERSION, archive.toByteArray());
        } catch (IOException failure) {
            throw new IllegalStateException("Could not build the bundled NEXORA Bedrock HUD pack", failure);
        }
    }

    private static byte[] readAsset(String path) throws IOException {
        try (InputStream input = BedrockHudResourcePack.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("Missing bundled HUD resource: " + path);
            }
            return input.readAllBytes();
        }
    }
}