package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import ru.nexora.network.nethernet.NetherNetMessageFramer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BedrockSessionManagerTest {
    @Test
    void negotiatesNetworkSettingsThenDecodesCompressedLoginBatch() {
        List<byte[]> outboundFrames = new ArrayList<>();
        AtomicReference<BedrockSessionManager.PacketEvent> received = new AtomicReference<>();
        BedrockSessionManager manager = new BedrockSessionManager(
                2225,
                (networkId, label, frame) -> outboundFrames.add(frame),
                received::set);

        byte[] protocol = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2225).array();
        BedrockPacket request = new BedrockPacket(
                BedrockBatchCodec.REQUEST_NETWORK_SETTINGS_PACKET_ID, 0, 0, protocol);
        sendClientBatch(manager, "client-1", BedrockBatchCodec.encode(List.of(request), false, 256));

        assertEquals(1, outboundFrames.size());
        byte[] response = reassemble(outboundFrames);
        List<BedrockPacket> responsePackets = BedrockBatchCodec.decode(response, false);
        assertEquals(BedrockBatchCodec.NETWORK_SETTINGS_PACKET_ID, responsePackets.getFirst().packetId());
        assertEquals(1, manager.activeSessionCount());

        byte[] loginPayload = new byte[512];
        java.util.Arrays.fill(loginPayload, (byte) 0x42);
        BedrockPacket login = new BedrockPacket(1, 0, 0, loginPayload);
        byte[] compressedLogin = BedrockBatchCodec.encode(List.of(login), true, 256);
        assertEquals(0, Byte.toUnsignedInt(compressedLogin[0]));
        sendClientBatch(manager, "client-1", compressedLogin);

        assertNotNull(received.get());
        assertEquals(1, received.get().packet().packetId());
        assertArrayEquals(loginPayload, received.get().packet().payload());
    }

    @Test
    void sendsClientOutdatedPlayStatusForOlderProtocol() {
        List<byte[]> outboundFrames = new ArrayList<>();
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, (networkId, label, frame) -> outboundFrames.add(frame), event -> {});

        byte[] protocol = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2193).array();
        BedrockPacket request = new BedrockPacket(
                BedrockBatchCodec.REQUEST_NETWORK_SETTINGS_PACKET_ID, 0, 0, protocol);
        sendClientBatch(manager, "old-client", BedrockBatchCodec.encode(List.of(request), false, 256));

        BedrockPacket response = BedrockBatchCodec.decode(reassemble(outboundFrames), false).getFirst();
        assertEquals(BedrockBatchCodec.PLAY_STATUS_PACKET_ID, response.packetId());
        assertEquals(1, ByteBuffer.wrap(response.payload()).order(ByteOrder.BIG_ENDIAN).getInt());
    }

    @Test
    void requiresInitialSettingsRequestOnReliableChannel() {
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, (networkId, label, frame) -> {}, event -> {});
        byte[] protocol = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2225).array();
        BedrockPacket request = new BedrockPacket(
                BedrockBatchCodec.REQUEST_NETWORK_SETTINGS_PACKET_ID, 0, 0, protocol);
        byte[] batch = BedrockBatchCodec.encode(List.of(request), false, 256);
        byte[] frame = NetherNetMessageFramer.fragment(batch, 1200, false).getFirst();

        assertThrows(IllegalArgumentException.class,
                () -> manager.onFrame("client-2", "UnreliableDataChannel", frame));
    }

    private static void sendClientBatch(BedrockSessionManager manager, String id, byte[] batch) {
        for (byte[] frame : NetherNetMessageFramer.fragment(batch, 1200, true)) {
            manager.onFrame(id, "ReliableDataChannel", frame);
        }
    }

    private static byte[] reassemble(List<byte[]> frames) {
        NetherNetMessageFramer.Reassembler reassembler = new NetherNetMessageFramer.Reassembler();
        byte[] complete = null;
        for (byte[] frame : frames) {
            var result = reassembler.accept(frame, true);
            if (result.isPresent()) complete = result.get();
        }
        assertNotNull(complete);
        return complete;
    }
}
