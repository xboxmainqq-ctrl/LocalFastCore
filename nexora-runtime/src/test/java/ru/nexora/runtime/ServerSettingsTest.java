package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ServerSettingsTest {
    @Test
    void defaultsDescribeBedrockServerWithoutStartingServices() {
        ServerSettings settings = ServerSettings.defaults(Path.of("server-data"));

        assertEquals("0.0.0.0", settings.bindAddress());
        assertEquals(19132, settings.port());
        assertEquals(20, settings.maxPlayers());
        assertEquals(20, settings.tickRate());
        assertEquals(10, settings.viewDistance());
        assertTrue(settings.onlineMode());
        assertTrue(settings.dataDirectory().isAbsolute());
        assertEquals(settings.dataDirectory(), settings.dataDirectory().normalize());
    }

    @Test
    void trimsBindAddressAndNormalizesDataDirectory() {
        ServerSettings settings = new ServerSettings(
                " 127.0.0.1 ", 19133, 8, 30, 12, false,
                Path.of("data", "..", "nexora")
        );

        assertEquals("127.0.0.1", settings.bindAddress());
        assertEquals(Path.of("nexora").toAbsolutePath().normalize(), settings.dataDirectory());
    }

    @Test
    void rejectsInvalidNetworkAndGameplayLimits() {
        Path data = Path.of("data");

        assertThrows(IllegalArgumentException.class,
                () -> new ServerSettings(" ", 19132, 20, 20, 10, true, data));
        assertThrows(IllegalArgumentException.class,
                () -> new ServerSettings("0.0.0.0", 0, 20, 20, 10, true, data));
        assertThrows(IllegalArgumentException.class,
                () -> new ServerSettings("0.0.0.0", 19132, 0, 20, 10, true, data));
        assertThrows(IllegalArgumentException.class,
                () -> new ServerSettings("0.0.0.0", 19132, 20, 0, 10, true, data));
        assertThrows(IllegalArgumentException.class,
                () -> new ServerSettings("0.0.0.0", 19132, 20, 20, 1, true, data));
    }

    @Test
    void rejectsNullRequiredValues() {
        assertThrows(NullPointerException.class,
                () -> new ServerSettings(null, 19132, 20, 20, 10, true, Path.of("data")));
        assertThrows(NullPointerException.class,
                () -> new ServerSettings("0.0.0.0", 19132, 20, 20, 10, true, null));
    }
}
