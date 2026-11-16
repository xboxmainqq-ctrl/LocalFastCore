package ru.nexora.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ServerConfigurationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void createsDefaultsAndLoadsThemAgain() throws IOException {
        Path file = temporaryDirectory.resolve("server.properties");
        ServerConfiguration created = ServerConfiguration.loadOrCreate(file);
        assertEquals(ServerConfiguration.defaults(), created);
        assertEquals("nexora_rpg", created.worldId());
        assertEquals(20261116L, created.seed());
        assertTrue(Files.isRegularFile(file));
        assertEquals(created, ServerConfiguration.loadOrCreate(file));
    }

    @Test
    void readsCustomWorldAndServerSettings() throws IOException {
        Path file = temporaryDirectory.resolve("server.properties");
        Files.writeString(file, """
                world.id=adventure
                world.generator=overworld
                world.seed=987654321
                world.min-y=-32
                world.max-y-exclusive=256
                server.tick-rate=30
                """);

        ServerConfiguration configuration = ServerConfiguration.loadOrCreate(file);
        assertEquals("adventure", configuration.worldId());
        assertEquals(987654321L, configuration.seed());
        assertEquals(new WorldHeight(-32, 256), configuration.worldHeight());
        assertEquals(30, configuration.tickRate());
    }

    @Test
    void rejectsInvalidSettingsWithUsefulIoFailure() throws IOException {
        Path file = temporaryDirectory.resolve("server.properties");
        Files.writeString(file, "world.id=../outside\n");
        IOException failure = assertThrows(IOException.class,
                () -> ServerConfiguration.loadOrCreate(file));
        assertTrue(failure.getMessage().contains("Invalid NEXORA server setting"));
    }

    @Test
    void rejectsUnsupportedWorldHeightsAndTickRates() {
        assertThrows(IllegalArgumentException.class, () -> new ServerConfiguration(
                "world", "overworld", 0, new WorldHeight(0, 7), 20));
        assertThrows(IllegalArgumentException.class, () -> new ServerConfiguration(
                "world", "overworld", 0, new WorldHeight(0, 256), 121));
    }
}
