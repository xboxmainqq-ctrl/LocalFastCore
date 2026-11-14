package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BedrockServerMetadataTest {
    @Test
    void advertisesThePinnedVersionAndProtocol() {
        BedrockServerMetadata metadata = new BedrockServerMetadata(
                "NEXORA", "Main World", 3, 20, 0);

        assertEquals(
                "{\"name\":\"NEXORA\",\"protocol\":2225,\"version\":\"1.26.60\","
                        + "\"level\":\"Main World\",\"players\":3,\"maxPlayers\":20,\"gameType\":0}",
                metadata.toJson());
    }

    @Test
    void escapesJsonStrings() {
        BedrockServerMetadata metadata = new BedrockServerMetadata(
                "NEXORA \"Core\"", "World\\North\n", 0, 10, 1);

        assertTrue(metadata.toJson().contains("\"name\":\"NEXORA \\\"Core\\\"\""));
        assertTrue(metadata.toJson().contains("\"level\":\"World\\\\North\\n\""));
    }

    @Test
    void rejectsInvalidMetadata() {
        assertThrows(IllegalArgumentException.class,
                () -> new BedrockServerMetadata("NEXORA", "World", -1, 10, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new BedrockServerMetadata("NEXORA", "World", 11, 10, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new BedrockServerMetadata("NEXORA", "World", 0, 10, 3));
    }
}
