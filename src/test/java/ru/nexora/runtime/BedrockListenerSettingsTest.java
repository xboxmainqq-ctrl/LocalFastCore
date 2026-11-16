package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BedrockListenerSettingsTest {
    @Test
    void defaultsToDisabledAndSharedPort() {
        BedrockListenerSettings settings = BedrockListenerSettings.disabled();
        assertFalse(settings.enabled());
        assertEquals(19132, settings.signalingPort());
        assertEquals(19132, settings.mediaUdpPort());
        assertEquals(new InetSocketAddress("0.0.0.0", 19132), settings.signalingBindAddress());
    }

    @Test
    void supportsSeparateFixedIcePort() {
        BedrockListenerSettings settings = new BedrockListenerSettings(
                true, "0.0.0.0", 25565, 25566, Path.of("data/nethernet.pem"), true);
        assertEquals(25566, settings.mediaUdpPort());
        assertEquals(new InetSocketAddress("0.0.0.0", 25566), settings.mediaBindAddress());
    }

    @Test
    void rejectsInvalidPortsAndDisabledRetailAuthentication() {
        assertThrows(IllegalArgumentException.class, () -> new BedrockListenerSettings(
                false, "0.0.0.0", 0, 0, Path.of("identity.pem"), true));
        assertThrows(IllegalArgumentException.class, () -> new BedrockListenerSettings(
                false, "0.0.0.0", 19132, 19132, Path.of("identity.pem"), true));
        assertThrows(IllegalArgumentException.class, () -> new BedrockListenerSettings(
                true, "0.0.0.0", 19132, 0, Path.of("identity.pem"), false));
    }

    @Test
    void parsesConfiguredPublicIceEndpoints() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("nexora-bedrock", ".properties");
        try {
            java.nio.file.Files.writeString(file, """
                    bedrock.network.enabled=true
                    bedrock.port=23472
                    bedrock.advertised-addresses=203.0.113.10:23472, game.example.net:23472
                    """);
            BedrockListenerSettings settings = BedrockListenerSettings.loadFromProperties(file);
            assertEquals(java.util.List.of("203.0.113.10:23472", "game.example.net:23472"),
                    settings.advertisedAddresses());
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    @Test
    void rejectsBlankBindAddress() {
        assertThrows(IllegalArgumentException.class, () -> new BedrockListenerSettings(
                false, "  ", 19132, 0, Path.of("identity.pem"), true));
    }
}
