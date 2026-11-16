package ru.nexora.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class NexoraMainTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void parsesDataDirectoryAndSeed() {
        NexoraMain.LaunchOptions options = NexoraMain.parseArguments(new String[] {
                "--data-dir", temporaryDirectory.resolve("server-data").toString(),
                "--seed", "-987654321"
        });

        assertEquals(temporaryDirectory.resolve("server-data").toAbsolutePath().normalize(),
                options.dataDirectory());
        assertEquals(-987654321L, options.seed());
        assertFalse(options.help());
        assertFalse(options.version());
    }

    @Test
    void supportsHelpAndVersionFlags() {
        assertTrue(NexoraMain.parseArguments(new String[] {"--help"}).help());
        assertTrue(NexoraMain.parseArguments(new String[] {"--version"}).version());
    }

    @Test
    void rejectsUnknownAndIncompleteArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> NexoraMain.parseArguments(new String[] {"--unknown"}));
        assertThrows(IllegalArgumentException.class,
                () -> NexoraMain.parseArguments(new String[] {"--data-dir"}));
        assertThrows(IllegalArgumentException.class,
                () -> NexoraMain.parseArguments(new String[] {"--seed", "not-a-number"}));
    }


    @Test
    void parsesExplicitDevelopmentNetworkSettings() {
        NexoraMain.LaunchOptions options = NexoraMain.parseArguments(new String[] {
                "--enable-development-network",
                "--bind-address", "127.0.0.1",
                "--signaling-port", "8181",
                "--tick-rate", "30"
        });

        assertTrue(options.developmentNetworkEnabled());
        assertEquals("127.0.0.1", options.bindAddress());
        assertEquals(8181, options.signalingPort());
        assertEquals(30, options.tickRate());
    }

    @Test
    void parsesAuthenticatedBedrockNetherNetListenerSettings() {
        NexoraMain.LaunchOptions options = NexoraMain.parseArguments(new String[] {
                "--enable-bedrock-network",
                "--bedrock-bind-address", "127.0.0.1",
                "--bedrock-port", "19140",
                "--bedrock-ice-port", "19141",
                "--bedrock-identity-file", "keys/nexora.pem"
        });

        BedrockListenerSettings settings = options.bedrockListenerSettings();
        assertTrue(settings.enabled());
        assertTrue(settings.requireRetailAuthentication());
        assertEquals("127.0.0.1", settings.bindAddress());
        assertEquals(19140, settings.signalingPort());
        assertEquals(19141, settings.separateIcePort());
        assertEquals(Path.of("keys/nexora.pem"), settings.identityFile());
    }

    @Test
    void keepsBedrockListenerDisabledUnlessExplicitlyEnabled() {
        assertFalse(NexoraMain.parseArguments(new String[0])
                .bedrockListenerSettings().enabled());
        assertThrows(IllegalArgumentException.class,
                () -> NexoraMain.parseArguments(new String[] {"--bedrock-port", "19140"}));
        assertThrows(IllegalArgumentException.class,
                () -> NexoraMain.parseArguments(new String[] {
                        "--enable-bedrock-network", "--bedrock-ice-port", "19132"
                }));
    }

    @Test
    void registersBedrockNetherNetModuleOnlyWhenEnabled() {
        NexoraMain.RuntimeHandle offline = NexoraMain.createRuntime(
                NexoraMain.parseArguments(new String[0]), ignored -> { });
        assertFalse(offline.server().moduleIds().contains("bedrock-nethernet"));

        NexoraMain.RuntimeHandle enabled = NexoraMain.createRuntime(
                NexoraMain.parseArguments(new String[] {"--enable-bedrock-network"}),
                ignored -> { });
        assertTrue(enabled.server().moduleIds().contains("bedrock-nethernet"));
    }


    @Test
    void enablesAuthenticatedBedrockListenerFromServerPropertiesWithoutCliFlags() throws Exception {
        Path dataDirectory = temporaryDirectory.resolve("bedrock-properties");
        Files.createDirectories(dataDirectory);
        int port;
        try (java.net.ServerSocket tcp = new java.net.ServerSocket(0)) {
            port = tcp.getLocalPort();
            try (java.net.DatagramSocket udp = new java.net.DatagramSocket(port)) {
                assertTrue(udp.isBound());
            }
        }
        Files.writeString(dataDirectory.resolve("server.properties"), """
                bedrock.network.enabled=true
                bedrock.bind-address=127.0.0.1
                bedrock.port=%d
                bedrock.ice-port=0
                bedrock.identity-file=nethernet-identity.pem
                """.formatted(port));

        NexoraMain.RuntimeHandle runtime = NexoraMain.createRuntime(
                NexoraMain.parseArguments(new String[] {"--data-dir", dataDirectory.toString()}),
                ignored -> { });
        assertTrue(runtime.server().moduleIds().contains("bedrock-nethernet"));
        runtime.server().start();
        try {
            assertTrue(Files.isRegularFile(dataDirectory.resolve("nethernet-identity.pem")));
            assertEquals(ServerState.RUNNING, runtime.server().state());
        } finally {
            runtime.server().stop();
        }
    }

    @Test
    void requiresExplicitFlagBeforeOpeningDevelopmentNetwork() {
        assertThrows(IllegalArgumentException.class,
                () -> NexoraMain.parseArguments(new String[] {
                        "--bind-address", "127.0.0.1"
                }));
        assertFalse(NexoraMain.parseArguments(new String[0]).developmentNetworkEnabled());
    }

    @Test
    void registersPrivateNetherNetModuleOnlyWhenDevelopmentModeIsEnabled() {
        NexoraMain.LaunchOptions offline = NexoraMain.parseArguments(new String[0]);
        NexoraMain.RuntimeHandle offlineRuntime =
                NexoraMain.createRuntime(offline, ignored -> { });
        assertFalse(offlineRuntime.server().moduleIds().contains("nethernet-nexora-private"));

        NexoraMain.LaunchOptions network = NexoraMain.parseArguments(new String[] {
                "--enable-development-network",
                "--bind-address", "127.0.0.1",
                "--signaling-port", "8181"
        });
        NexoraMain.RuntimeHandle networkRuntime =
                NexoraMain.createRuntime(network, ignored -> { });
        assertTrue(networkRuntime.server().moduleIds().contains("nethernet-nexora-private"));
    }

    @Test
    void exposesLoadedWorldMetadataForPrivateClientSessions() {
        NexoraMain.RuntimeHandle runtime = NexoraMain.createRuntime(
                temporaryDirectory.resolve("world-info"), 7L, ignored -> { });
        runtime.server().start();
        try {
            NexoraWorldInfo info = runtime.worldInfo();
            assertEquals("nexora_rpg", info.worldId());
            assertEquals(-64, info.minY());
            assertEquals(320, info.maxYExclusive());
            assertEquals(0, info.spawnX());
            World world = runtime.worlds().get("nexora_rpg").orElseThrow();
            assertEquals(NexoraMain.findSpawnY(world), info.spawnY());
            assertEquals(0, info.spawnZ());
            assertEquals(20, info.tickRate());
            assertEquals(1, info.loadedChunks());
            assertNotNull(runtime.privateProtocolHandler(java.util.UUID.randomUUID(), ignored -> { }));
        } finally {
            runtime.server().stop();
        }
    }

    @Test
    void startsAndStopsTheAuthenticatedBedrockNetherNetListener() throws Exception {
        Path dataDirectory = temporaryDirectory.resolve("bedrock-listener-smoke");
        int port;
        try (java.net.ServerSocket tcp = new java.net.ServerSocket(0)) {
            port = tcp.getLocalPort();
            try (java.net.DatagramSocket udp = new java.net.DatagramSocket(port)) {
                assertTrue(udp.isBound());
            }
        }

        NexoraMain.LaunchOptions options = NexoraMain.parseArguments(new String[] {
                "--data-dir", dataDirectory.toString(),
                "--enable-bedrock-network",
                "--bedrock-port", Integer.toString(port)
        });
        NexoraMain.RuntimeHandle runtime = NexoraMain.createRuntime(options, ignored -> { });
        runtime.server().start();
        try {
            assertEquals(ServerState.RUNNING, runtime.server().state());
            assertTrue(Files.isRegularFile(dataDirectory.resolve("nethernet-identity.pem")));
        } finally {
            runtime.server().stop();
        }
        assertEquals(ServerState.STOPPED, runtime.server().state());
    }

    @Test
    void runtimeUsesServerPropertiesForWorldGeneratorHeightAndTickRate() throws Exception {
        Path dataDirectory = temporaryDirectory.resolve("configured-server");
        Files.createDirectories(dataDirectory);
        Files.writeString(dataDirectory.resolve("server.properties"), """
                world.id=adventure
                world.generator=overworld
                world.seed=12345
                world.min-y=-32
                world.max-y-exclusive=256
                server.tick-rate=30
                """);

        NexoraMain.LaunchOptions options = NexoraMain.parseArguments(new String[] {
                "--data-dir", dataDirectory.toString()
        });
        NexoraMain.RuntimeHandle runtime = NexoraMain.createRuntime(options, ignored -> { });
        runtime.server().start();
        try {
            World world = runtime.worlds().get("adventure").orElseThrow();
            assertEquals(new WorldHeight(-32, 256), world.height());
            assertEquals("adventure", runtime.worldId());
            assertEquals(30, runtime.tickRate());
            assertEquals(30, runtime.gameLoop().tickRate());
            assertTrue(world.isChunkLoaded(new ChunkPosition(0, 0)));
        } finally {
            runtime.server().stop();
        }
        assertTrue(Files.isRegularFile(dataDirectory.resolve("worlds").resolve("adventure.nxw")));
    }


    @Test
    void explicitTickRateArgumentOverridesServerProperties() throws Exception {
        Path dataDirectory = temporaryDirectory.resolve("tick-override");
        Files.createDirectories(dataDirectory);
        Files.writeString(dataDirectory.resolve("server.properties"), """
                server.tick-rate=30
                """);

        NexoraMain.LaunchOptions options = NexoraMain.parseArguments(new String[] {
                "--data-dir", dataDirectory.toString(),
                "--tick-rate", "40"
        });
        NexoraMain.RuntimeHandle runtime = NexoraMain.createRuntime(options, ignored -> { });
        assertEquals(40, runtime.tickRate());
        assertEquals(40, runtime.gameLoop().tickRate());
    }

    @Test
    void runtimeOwnsPlayerInventoriesAndCanCollectDroppedItems() {
        NexoraMain.RuntimeHandle runtime = NexoraMain.createRuntime(
                temporaryDirectory.resolve("inventory-runtime"), 42L, ignored -> { });
        java.util.UUID playerId = java.util.UUID.randomUUID();
        runtime.server().start();
        try {
            assertSame(runtime.inventory(playerId), runtime.inventory(playerId));
            java.util.UUID itemId = java.util.UUID.randomUUID();
            runtime.entities().spawn(new WorldEntity(itemId, WorldEntity.Type.ITEM,
                    "minecraft:apple", 0.5, 65, 0.5, 0, -1));

            ItemPickupService.PickupResult result =
                    runtime.pickupItems(playerId, 0.5, 65, 0.5);

            assertEquals(1, result.collected());
            assertEquals(1, runtime.inventory(playerId).count("minecraft:apple"));
            assertTrue(runtime.entities().find(itemId).isEmpty());
        } finally {
            runtime.server().stop();
        }
    }

    @Test
    void startsWorldAndTickLoopThenPersistsWorldOnStop() {
        Path dataDirectory = temporaryDirectory.resolve("nexora");
        NexoraMain.RuntimeHandle runtime = NexoraMain.createRuntime(dataDirectory, 42L, ignored -> { });

        runtime.server().start();
        try {
            World world = runtime.worlds().get("world").orElseThrow();
            assertTrue(world.isChunkLoaded(new ChunkPosition(0, 0)));
            int spawnY = NexoraMain.findSpawnY(world);
            assertFalse(world.blockAt(new BlockPosition(0, spawnY - 1, 0)).isAir());
            assertTrue(world.blockAt(new BlockPosition(0, spawnY, 0)).isAir());
            assertTrue(world.biomeAt(new BlockPosition(0, spawnY - 1, 0))
                    >= BiomeRegistry.MISTWOOD_ID);
            assertTrue(runtime.gameLoop().isRunning());
            assertTrue(runtime.server().moduleIds().contains("entity-simulation"));
            assertNotNull(runtime.entities());
            assertEquals(0, runtime.entities().size());
            assertEquals(ServerState.RUNNING, runtime.server().state());
        } finally {
            runtime.server().stop();
        }

        assertFalse(runtime.gameLoop().isRunning());
        assertEquals(ServerState.STOPPED, runtime.server().state());
        assertTrue(Files.isRegularFile(dataDirectory.resolve("worlds").resolve("world.nxw")));
    }
}
