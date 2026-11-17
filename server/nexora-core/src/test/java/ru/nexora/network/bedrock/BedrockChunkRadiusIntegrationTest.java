package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import ru.nexora.network.nethernet.NetherNetClientIdentity;
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
    void chunkAreaTracksNegotiatedRadiusAndDoesNotResendExistingColumns() throws Exception {
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
            set(session, "clientInitialized", true);
            set(session, "compressionNegotiated", true);
            set(session, "chunkViewRadius", 2);

            Method sendChunkArea = BedrockSessionManager.class.getDeclaredMethod(
                    "sendChunkArea", String.class, session.getClass(), int.class, int.class);
            sendChunkArea.setAccessible(true);
            sendChunkArea.invoke(manager, "viewer", session, 0, 0);

            assertEquals(25, sentChunks(session).size());
            assertEquals(25, packetsFor(outbound, "viewer").stream()
                    .filter(packet -> packet.packetId() == BedrockLevelChunkCodec.LEVEL_CHUNK_PACKET_ID)
                    .count());

            set(session, "chunkViewRadius", 1);
            sendChunkArea.invoke(manager, "viewer", session, 0, 0);
            assertEquals(9, sentChunks(session).size());
            assertEquals(25, packetsFor(outbound, "viewer").stream()
                    .filter(packet -> packet.packetId() == BedrockLevelChunkCodec.LEVEL_CHUNK_PACKET_ID)
                    .count());

            sendChunkArea.invoke(manager, "viewer", session, 0, 0);
            assertEquals(25, packetsFor(outbound, "viewer").stream()
                    .filter(packet -> packet.packetId() == BedrockLevelChunkCodec.LEVEL_CHUNK_PACKET_ID)
                    .count());
        } finally {
            manager.close();
        }
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
