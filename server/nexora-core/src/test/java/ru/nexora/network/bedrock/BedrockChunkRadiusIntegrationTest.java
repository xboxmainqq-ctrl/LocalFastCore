package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import ru.nexora.network.nethernet.NetherNetClientIdentity;
import ru.nexora.network.nethernet.NetherNetInboundPipeline;
import ru.nexora.network.nethernet.NetherNetMessageFramer;
import ru.nexora.world.FlatWorldGenerator;
import ru.nexora.world.World;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BedrockChunkRadiusIntegrationTest {
    @Test
    void negotiatesClientRadiusAndReconcilesChunkInterestWithoutResendingColumns() throws Exception {
        List<Outbound> outbound = new ArrayList<>();
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new World("chunk-radius-test", 0L, new FlatWorldGenerator()),
                (networkId, label, frame) -> outbound.add(new Outbound(networkId, frame)),
                event -> { });

        try {
            manager.onIdentity("viewer", new NetherNetClientIdentity(
                    "viewer-token", new byte[] {1, 2, 3}, "viewer-xuid",
                    "Viewer", "viewer-playfab", "20CA2", null));
            Object session = session(manager, "viewer");
            set(session, "loginAuthenticated", true);
            set(session, "gameStarted", true);
            set(session, "positionInitialized", true);
            set(session, "clientInitialized", true);
            set(session, "compressionNegotiated", true);
            set(session, "positionX", 0.5f);
            set(session, "positionY", 65.0f);
            set(session, "positionZ", 0.5f);

            Method handleRequest = BedrockSessionManager.class.getDeclaredMethod(
                    "handleChunkRadiusRequest", NetherNetInboundPipeline.InboundMessage.class,
                    session.getClass(), BedrockPacket.class);
            handleRequest.setAccessible(true);

            NetherNetInboundPipeline.InboundMessage message =
                    new NetherNetInboundPipeline.InboundMessage("viewer", "reliable", true, new byte[0]);
            handleRequest.invoke(manager, message, session, request(3, 8));

            assertEquals(3, field(session, "chunkViewRadius"));
            assertEquals(4, sentChunks(session).size(),
                    "Only the first bounded chunk batch should be sent immediately");
            List<BedrockPacket> firstBatch = packetsFor(outbound, "viewer");
            assertEquals(BedrockChunkRadiusCodec.UPDATED_PACKET_ID, firstBatch.getFirst().packetId());
            assertEquals(0x79, firstBatch.get(1).packetId());
            assertEquals(112, Byte.toUnsignedInt(firstBatch.get(1).payload()[4]));
            assertEquals(4, firstBatch.stream()
                    .filter(packet -> packet.packetId() == BedrockLevelChunkCodec.LEVEL_CHUNK_PACKET_ID)
                    .count());

            completeStreaming(manager, session);
            assertEquals(49, sentChunks(session).size());

            // Shrinking the view updates the client radius and local interest set,
            // while already delivered columns are not retransmitted.
            handleRequest.invoke(manager, message, session, request(1, 8));
            assertEquals(1, field(session, "chunkViewRadius"));
            completeStreaming(manager, session);
            assertEquals(9, sentChunks(session).size());
            List<BedrockPacket> allPackets = packetsFor(outbound, "viewer");
            assertEquals(2, allPackets.stream()
                    .filter(packet -> packet.packetId() == BedrockChunkRadiusCodec.UPDATED_PACKET_ID)
                    .count());
            List<BedrockPacket> publisherUpdates = allPackets.stream()
                    .filter(packet -> packet.packetId() == 0x79).toList();
            assertEquals(2, publisherUpdates.size());
            assertEquals(48, Byte.toUnsignedInt(publisherUpdates.get(1).payload()[4]));
            assertEquals(49, allPackets.stream()
                    .filter(packet -> packet.packetId() == BedrockLevelChunkCodec.LEVEL_CHUNK_PACKET_ID)
                    .count());

            String diagnostics = manager.adminRuntimeSummary();
            assertTrue(diagnostics.contains("chunkStreamingBatches=16"));
            assertTrue(diagnostics.contains("chunkStreamingCompleted=2"));
            assertTrue(diagnostics.contains("chunkPacketsEncoded=49"));
            assertTrue(diagnostics.contains("chunkPacketsReused=9"));
            assertTrue(diagnostics.contains("chunkStreamingPending=0"));
            assertTrue(diagnostics.contains("chunkStreamingPendingPeak=45"));
            assertTrue(diagnostics.contains("chunkStreamingP95Ns="));
            assertTrue(diagnostics.contains("outboundDrainP95Ns="));
        } finally {
            manager.close();
        }
    }

    private static void completeStreaming(BedrockSessionManager manager, Object session) throws Exception {
        Method process = BedrockSessionManager.class.getDeclaredMethod(
                "processChunkStreaming", String.class, session.getClass());
        process.setAccessible(true);
        for (int attempt = 0; attempt < 32 && field(session, "chunkStreamingQueue") != null; attempt++) {
            process.invoke(manager, "viewer", session);
        }
        assertNull(field(session, "chunkStreamingQueue"), "Chunk streaming should finish within the test pump budget");
    }

    private static BedrockPacket request(int radius, int clientMaximum) {
        return new BedrockPacket(BedrockChunkRadiusCodec.REQUEST_PACKET_ID, 0, 0,
                new byte[] {(byte) (radius << 1), (byte) clientMaximum});
    }

    @SuppressWarnings("unchecked")
    private static Object session(BedrockSessionManager manager, String id) throws Exception {
        Field field = BedrockSessionManager.class.getDeclaredField("sessions");
        field.setAccessible(true);
        return ((Map<String, Object>) field.get(manager)).get(id);
    }

    @SuppressWarnings("unchecked")
    private static Set<Long> sentChunks(Object session) throws Exception {
        Field field = session.getClass().getDeclaredField("sentChunkKeys");
        field.setAccessible(true);
        return (Set<Long>) field.get(session);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static List<BedrockPacket> packetsFor(List<Outbound> frames, String recipient) {
        List<BedrockPacket> packets = new ArrayList<>();
        NetherNetMessageFramer.Reassembler reassembler = new NetherNetMessageFramer.Reassembler();
        for (Outbound outbound : frames) {
            if (!recipient.equals(outbound.recipient())) continue;
            reassembler.accept(outbound.frame(), true).ifPresent(
                    message -> packets.addAll(BedrockBatchCodec.decode(message, true)));
        }
        return packets;
    }

    private record Outbound(String recipient, byte[] frame) {
    }
}
