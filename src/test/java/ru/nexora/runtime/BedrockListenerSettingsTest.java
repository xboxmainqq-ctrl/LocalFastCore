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
    void rejectsBlankBindAddress() {
        assertThrows(IllegalArgumentException.class, () -> new BedrockListenerSettings(
                false, "  ", 19132, 0, Path.of("identity.pem"), true));
    }
}
