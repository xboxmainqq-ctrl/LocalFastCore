package ru.nexora.runtime;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class BedrockHudResourcePackTest {
    @Test
    void buildsBedrockPackWithNativeHealthBindingsAndVanillaHeartsDisabled() throws Exception {
        BedrockResourcePackTransferCodec.Pack pack = BedrockHudResourcePack.create();
        assertEquals("1.0.0", pack.version());
        assertEquals("4a0a3b92-2e6d-4c1d-9f6c-2e77bca5f201", pack.uuid().toString());
        assertTrue(pack.archiveSize() > 0);

        Map<String, String> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(pack.archive()), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }

        assertTrue(entries.containsKey("manifest.json"));
        assertTrue(entries.containsKey("ui/hud_screen.json"));
        String manifest = entries.get("manifest.json");
        assertTrue(manifest.contains("\"type\": \"resources\""));

        String hud = entries.get("ui/hud_screen.json");
        assertTrue(hud.contains("\"heart_renderer\": {"));
        assertTrue(hud.contains("\"type\": \"panel\""));
        assertTrue(hud.contains("\"visible\": false"));
        assertTrue(hud.contains("\"binding_name\": \"#health\""));
        assertTrue(hud.contains("\"binding_name\": \"#health_max\""));
        assertTrue(hud.contains("\"target_property_name\": \"#clip_ratio\""));
        assertTrue(hud.contains("\"nexora_health_bar@nexora_health_bar\": {}"));
        assertFalse(hud.contains("#player_health"));
        assertFalse(hud.contains("#player_max_health"));

        assertValidJson(manifest);
        assertValidJson(hud);
    }

    private static void assertValidJson(String json) throws Exception {
        try (JsonParser parser = new JsonFactory().createParser(json)) {
            assertEquals(JsonToken.START_OBJECT, parser.nextToken());
            parser.skipChildren();
            assertNull(parser.nextToken(), "JSON document must not contain trailing content");
        }
    }
}
