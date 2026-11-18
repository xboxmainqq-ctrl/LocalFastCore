package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import ru.nexora.network.nethernet.NetherNetClientIdentity;
import ru.nexora.network.nethernet.NetherNetMessageFramer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic concurrency workload for the NetherNet-to-Bedrock session boundary.
 * This exercises parallel identity registration, initial protocol negotiation and
 * disconnect/drain cleanup; it is not a substitute for authenticated real-client load.
 */
class BedrockSessionLoadTest {
    private static final int CLIENTS = 128;

    @Test
    void handlesConcurrentSessionNegotiationAndDrainsAllPeerState() throws Exception {
        ConcurrentLinkedQueue<byte[]> outboundFrames = new ConcurrentLinkedQueue<>();
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, (networkId, channel, frame) -> outboundFrames.add(frame), event -> {});
        var executor = Executors.newFixedThreadPool(8);
        try {
            byte[] protocol = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                    .putInt(2225).array();
            BedrockPacket request = new BedrockPacket(
                    BedrockBatchCodec.REQUEST_NETWORK_SETTINGS_PACKET_ID, 0, 0, protocol);
            byte[] batch = BedrockBatchCodec.encode(List.of(request), false,
                    BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD);
            List<byte[]> frames = NetherNetMessageFramer.fragment(batch, 1200, true);
            assertEquals(1, frames.size());

            List<java.util.concurrent.Callable<Void>> connectTasks = new ArrayList<>(CLIENTS);
            for (int i = 0; i < CLIENTS; i++) {
                final int client = i;
                connectTasks.add(() -> {
                    String id = "load-client-" + client;
                    manager.onIdentity(id, new NetherNetClientIdentity(
                            "token-" + client, new byte[] {1}, "xuid-" + client,
                            "LoadPlayer" + client, "playfab-" + client, "20CA2", null));
                    manager.onFrame(id, "ReliableDataChannel", frames.getFirst());
                    return null;
                });
            }
            for (var future : executor.invokeAll(connectTasks, 10, TimeUnit.SECONDS)) {
                assertFalse(future.isCancelled(), "session negotiation task timed out");
                future.get();
            }

            assertEquals(CLIENTS, manager.activeSessionCount());
            assertEquals(CLIENTS, manager.authenticatedPlayerCount() == 0
                    ? manager.activeSessionCount() : -1,
                    "all registered peers must remain unauthenticated until Login validation");
            assertEquals(CLIENTS, outboundFrames.size(),
                    "every initial settings request must receive a response");
            assertFalse(manager.awaitNoActiveSessions(Duration.ZERO));

            List<java.util.concurrent.Callable<Void>> disconnectTasks = new ArrayList<>(CLIENTS);
            for (int i = 0; i < CLIENTS; i++) {
                final String id = "load-client-" + i;
                disconnectTasks.add(() -> {
                    manager.onDisconnected(id);
                    return null;
                });
            }
            for (var future : executor.invokeAll(disconnectTasks, 10, TimeUnit.SECONDS)) {
                assertFalse(future.isCancelled(), "disconnect task timed out");
                future.get();
            }

            assertTrue(manager.awaitNoActiveSessions(Duration.ofSeconds(2)));
            assertEquals(0, manager.activeSessionCount());
            assertEquals(0, manager.inboundChannelCount());
        } finally {
            executor.shutdownNow();
            manager.close();
        }
    }
}
