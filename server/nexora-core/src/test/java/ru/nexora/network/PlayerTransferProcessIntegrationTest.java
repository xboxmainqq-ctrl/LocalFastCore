package ru.nexora.network;

import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.Server;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nexora.inventory.ContainerInventory;
import ru.nexora.inventory.ItemStack;
import ru.nexora.inventory.PlayerEquipment;
import ru.nexora.inventory.PlayerInventory;
import ru.nexora.persistence.JdbcPlayerStateStore;
import ru.nexora.persistence.PlayerStateBundle;
import ru.nexora.persistence.PlayerStateBundleCodec;
import ru.nexora.persistence.PlayerStateCheckpointService;
import ru.nexora.world.PlayerData;
import ru.nexora.world.PlayerVitals;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PlayerTransferProcessIntegrationTest {
    @Test
    void authenticatedTcpTransferRestoresSharedCheckpointAndCommitsAcrossJvm(@TempDir Path directory)
            throws Exception {
        int[] ports = freePorts();
        Server databaseServer = Server.createTcpServer("-tcpPort", "0", "-ifNotExists").start();
        String databaseName = directory.resolve("nexora-transfer").toAbsolutePath()
                .toString().replace('\\', '/');
        String jdbcUrl = "jdbc:h2:tcp://127.0.0.1:" + databaseServer.getPort()
                + "/" + databaseName + ";LOCK_TIMEOUT=10000";
        String secret = Base64.getEncoder().encodeToString(new byte[32]);
        String xuid = "process-transfer-xuid";
        UUID transferId = UUID.randomUUID();
        try {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL(jdbcUrl);
            JdbcPlayerTransferLedger ledgerStorage = new JdbcPlayerTransferLedger(dataSource);
            ledgerStorage.initializeSchema();
            PlayerStateCheckpointService checkpoints = new PlayerStateCheckpointService(
                    new JdbcPlayerStateStore(dataSource), new PlayerStateBundleCodec());
            long revision = checkpoints.checkpointForTransfer(bundle(xuid), System.currentTimeMillis());
            DurableHandoffCoordinator ledger = new DurableHandoffCoordinator(
                    new PlayerTransferCoordinator(ledgerStorage));
            ledger.prepare(new PlayerHandoff(transferId, 77L, "node-a", "node-b", new byte[0]),
                    xuid, revision, System.currentTimeMillis());

            Process destination = startDestination(ports[1], ports[0], secret, jdbcUrl, transferId, xuid);
            try (BufferedReader destinationOutput = new BufferedReader(
                    new InputStreamReader(destination.getInputStream(), StandardCharsets.UTF_8))) {
                assertEquals("READY:node-b", readLine(destinationOutput, 10),
                        "Independent destination JVM must initialize shared ledger/checkpoint storage and bind TCP");

                try (TcpClusterTransport source = new TcpClusterTransport(
                        "node-a", new InetSocketAddress(InetAddress.getLoopbackAddress(), ports[0]),
                        Map.of("node-b", new InetSocketAddress(InetAddress.getLoopbackAddress(), ports[1])),
                        Set.of("node-b"), new AuthenticatedClusterMessageCodec(
                                Base64.getDecoder().decode(secret), 30_000L),
                        new ClusterReplayGuard(128, 30_000L), Duration.ofSeconds(3),
                        Duration.ofSeconds(8), 1, 8)) {
                    source.start(ignored -> fail("Source node must not receive a destination transfer request"));
                    ClusterMessage request = new ClusterMessage(UUID.randomUUID(), "node-a", "node-b",
                            PlayerTransferClusterEndpoint.ACCEPT_MESSAGE_TYPE, System.currentTimeMillis(),
                            PlayerTransferClusterEndpoint.encodeTransferId(transferId));
                    source.send(request).toCompletableFuture().get(10, TimeUnit.SECONDS);
                }

                String destinationResult = readLine(destinationOutput, 10);
                assertTrue(destinationResult.startsWith("ACTIVATED:" + xuid + ":" + revision + ":1:COMMITTED:1"),
                        "Destination must load the exact shared checkpoint, activate under fence 1 and commit before ACK: "
                                + destinationResult);
                assertTrue(destination.waitFor(10, TimeUnit.SECONDS), "Destination JVM must exit after commit");
                String remaining = new String(destination.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(0, destination.exitValue(), remaining);
            } finally {
                destination.destroyForcibly();
                destination.waitFor(5, TimeUnit.SECONDS);
            }

            PlayerTransferRecord committed = ledger.get(transferId).orElseThrow();
            assertEquals(PlayerTransferRecord.State.COMMITTED, committed.state());
            assertEquals(revision, committed.playerRevision());
            assertEquals(1L, committed.fencingToken());
        } finally {
            databaseServer.stop();
        }
    }

    private static Process startDestination(int bindPort, int peerPort, String secret,
                                            String jdbcUrl, UUID transferId, String xuid) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(java, "-cp", classpath, PlayerTransferDestinationProcess.class.getName(),
                "node-b", Integer.toString(bindPort), "node-a", Integer.toString(peerPort),
                secret, jdbcUrl, transferId.toString(), xuid)
                .redirectErrorStream(true)
                .start();
    }

    private static String readLine(BufferedReader reader, long timeoutSeconds) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
            try { return reader.readLine(); }
            catch (java.io.IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
        }).get(timeoutSeconds, TimeUnit.SECONDS);
    }

    private static int[] freePorts() throws Exception {
        try (ServerSocket first = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             ServerSocket second = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return new int[] {first.getLocalPort(), second.getLocalPort()};
        }
    }

    private static PlayerStateBundle bundle(String xuid) {
        PlayerData profile = new PlayerData(xuid, "ProcessTransfer", "survival",
                8.5f, 69.0f, -4.25f, 45.0f, 5.0f, 123L,
                new PlayerVitals(18.0f, 19, 2.5f, 0.25f), 42, 300);
        PlayerInventory inventory = new PlayerInventory();
        inventory.setSlot(3, new ItemStack("minecraft:diamond_sword", 1, 1));
        inventory.setSlot(9, new ItemStack("minecraft:ender_pearl", 8, 16));
        PlayerEquipment equipment = new PlayerEquipment();
        equipment.setArmor(0, new ItemStack("minecraft:diamond_helmet", 1, 1));
        return PlayerStateBundle.capture(profile, inventory,
                new ContainerInventory(PlayerStateBundle.CRAFTING_UI_SLOT_COUNT), equipment);
    }
}
