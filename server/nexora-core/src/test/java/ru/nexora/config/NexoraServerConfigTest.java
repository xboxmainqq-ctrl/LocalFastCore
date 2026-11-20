package ru.nexora.config;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class NexoraServerConfigTest {
    @Test
    void defaultsIceToTheMainAllocationForSinglePortHosts() {
        NexoraServerConfig config = NexoraServerConfig.from(new String[0], Map.of());
        assertEquals("0.0.0.0", config.bindHost());
        assertEquals(19132, config.port());
        assertEquals(19132, config.udpPortMin());
        assertEquals(19132, config.udpPortMax());
        assertEquals("NEXORA Core - Development", config.motd());
        assertEquals(20, config.maxPlayers());
        assertEquals("0.0.0.0", config.httpBindHost());
        assertEquals("data", config.dataDirectory());
        assertEquals(300L, config.autosaveSeconds());
        assertTrue(config.stunUrls().isEmpty());
        assertEquals(8192, config.maximumResidentChunks());
    }

    @Test
    void parsesCommandLineAndEnvironmentConfiguration() {
        NexoraServerConfig config = NexoraServerConfig.from(
                new String[]{"127.0.0.1", "19140", "Test Server", "12"},
                Map.of("NEXORA_HTTP_BIND", "127.0.0.2", "NEXORA_DATA_DIR", "/srv/nexora",
                        "NEXORA_AUTOSAVE_SECONDS", "90", "NEXORA_UDP_PORT_MIN", "31000",
                        "NEXORA_UDP_PORT_MAX", "31010", "NEXORA_STUN_URLS", "stun:one; stun:two ;;",
                        "NEXORA_MAX_RESIDENT_CHUNKS", "4096"));
        assertEquals("127.0.0.1", config.bindHost());
        assertEquals(19140, config.port());
        assertEquals("Test Server", config.motd());
        assertEquals(12, config.maxPlayers());
        assertEquals("127.0.0.2", config.httpBindHost());
        assertEquals("/srv/nexora", config.dataDirectory());
        assertEquals(90L, config.autosaveSeconds());
        assertEquals(31000, config.udpPortMin());
        assertEquals(31010, config.udpPortMax());
        assertEquals(java.util.List.of("stun:one", "stun:two"), config.stunUrls());
        assertEquals(4096, config.maximumResidentChunks());
    }

    @Test
    void hostAllocationAlsoBecomesDefaultFixedIcePort() {
        NexoraServerConfig config = NexoraServerConfig.from(new String[0], Map.of("SERVER_PORT", "23472"));
        assertEquals(23472, config.port());
        assertEquals(23472, config.udpPortMin());
        assertEquals(23472, config.udpPortMax());
    }

    @Test
    void explicitUdpRangeOverridesAllocationDefault() {
        NexoraServerConfig config = NexoraServerConfig.from(new String[0],
                Map.of("SERVER_PORT", "23472", "NEXORA_UDP_PORT_MIN", "30000",
                        "NEXORA_UDP_PORT_MAX", "30020"));
        assertEquals(23472, config.port());
        assertEquals(30000, config.udpPortMin());
        assertEquals(30020, config.udpPortMax());
    }

    @Test
    void nexoraPortOverridesHostPortAndCommandLineStillWins() {
        NexoraServerConfig environmentConfig = NexoraServerConfig.from(
                new String[0], Map.of("SERVER_PORT", "23472", "NEXORA_PORT", "24567"));
        NexoraServerConfig argumentConfig = NexoraServerConfig.from(
                new String[]{"0.0.0.0", "25678"}, Map.of("SERVER_PORT", "23472"));
        assertEquals(24567, environmentConfig.port());
        assertEquals(24567, environmentConfig.udpPortMin());
        assertEquals(25678, argumentConfig.port());
        assertEquals(25678, argumentConfig.udpPortMin());
    }

    @Test
    void rejectsInvalidPortsAndRanges() {
        assertThrows(IllegalArgumentException.class, () -> new NexoraServerConfig(
                "0.0.0.0", 0, "MOTD", 10, "0.0.0.0", "data", 60, 30000, 30100, java.util.List.of()));
        assertThrows(IllegalArgumentException.class, () -> new NexoraServerConfig(
                "0.0.0.0", 19132, "MOTD", 10, "0.0.0.0", "data", 60, 30100, 30000, java.util.List.of()));
        assertThrows(IllegalArgumentException.class, () -> new NexoraServerConfig(
                "0.0.0.0", 19132, "MOTD", 0, "0.0.0.0", "data", 60, 30000, 30100, java.util.List.of()));
    }

    @Test
    void reportsMalformedEnvironmentValuesClearly() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> NexoraServerConfig.from(new String[0], Map.of("NEXORA_AUTOSAVE_SECONDS", "later")));
        assertTrue(exception.getMessage().contains("NEXORA_AUTOSAVE_SECONDS"));
    }

    @Test
    void rejectsInvalidResidentChunkCapacity() {
        assertThrows(IllegalArgumentException.class,
                () -> NexoraServerConfig.from(new String[0], Map.of("NEXORA_MAX_RESIDENT_CHUNKS", "0")));
        assertThrows(IllegalArgumentException.class,
                () -> NexoraServerConfig.from(new String[0], Map.of("NEXORA_MAX_RESIDENT_CHUNKS", "invalid")));
    }

    @Test
    void doesNotExposeMutableStunList() {
        java.util.ArrayList<String> urls = new java.util.ArrayList<>(java.util.List.of("stun:one"));
        NexoraServerConfig config = new NexoraServerConfig("0.0.0.0", 19132, "MOTD", 10, "0.0.0.0",
                "data", 60, 30000, 30100, urls);
        urls.add("stun:two");
        assertEquals(java.util.List.of("stun:one"), config.stunUrls());
        assertThrows(UnsupportedOperationException.class, () -> config.stunUrls().add("stun:three"));
    }
}
