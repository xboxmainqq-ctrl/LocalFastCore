package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class BedrockInitialTransportRouterTest {
    @Test
    void sendsNetworkSettingsAfterValidRequestOverReliableChannel() {
        BedrockNetworkSettings settings = new BedrockNetworkSettings(
                256, BedrockNetworkSettings.CompressionAlgorithm.ZLIB, false, 0, 0.0f);
        BedrockInitialTransportRouter router = new BedrockInitialTransportRouter(
                settings,
                (connection, login) -> java.util.concurrent.CompletableFuture.completedFuture(false),
                connection -> fail("Login should not be reached in this test"),
                (connection, message) -> fail("Play messages should not be reached in this test"));
        FakeConnection connection = new FakeConnection("test-connection");

        router.accept(connection);
        connection.messageListener.accept(new TransportMessage(
                requestNetworkSettingsFrame(2225), DeliveryMode.RELIABLE_ORDERED));

        assertTrue(connection.open);
        assertEquals(1, connection.sent.size());
        ByteBuffer responseBatch = BedrockBatchCompressionCodec.decodeBeforeNetworkSettings(
                connection.sent.get(0), BedrockPacketBatchCodec.DEFAULT_MAX_BATCH_BYTES);
        List<BedrockGamePacket> packets = BedrockPacketBatchCodec.decodeUncompressed(responseBatch);
        assertEquals(1, packets.size());
        assertEquals(BedrockNetworkSettingsPacketCodec.PACKET_ID,
                packets.get(0).header().packetId());
        assertEquals(settings, BedrockNetworkSettingsPacketCodec.decodePayload(
                packets.get(0).payloadBuffer()));

        router.close();
        assertFalse(connection.open);
    }

    @Test
    void rejectsInitialHandshakeArrivingOnUnreliableChannel() {
        BedrockInitialTransportRouter router = new BedrockInitialTransportRouter(
                new BedrockNetworkSettings(0, BedrockNetworkSettings.CompressionAlgorithm.NONE,
                        false, 0, 0.0f),
                (connection, login) -> java.util.concurrent.CompletableFuture.completedFuture(false),
                connection -> { },
                (connection, message) -> { });
        FakeConnection connection = new FakeConnection("unreliable-test");
        router.accept(connection);
        connection.messageListener.accept(new TransportMessage(
                requestNetworkSettingsFrame(2225), DeliveryMode.UNRELIABLE));
        assertFalse(connection.open);
    }

    private static ByteBuffer requestNetworkSettingsFrame(int protocol) {
        BedrockGamePacket request = new BedrockGamePacket(
                new BedrockPacketHeader(BedrockRequestNetworkSettingsPacketCodec.PACKET_ID, 0, 0),
                bytes(BedrockRequestNetworkSettingsPacketCodec.encodePayload(
                        new BedrockRequestNetworkSettingsPacket(protocol))));
        ByteBuffer batch = BedrockPacketBatchCodec.encodeUncompressed(List.of(request));
        return BedrockBatchCompressionCodec.encodeBeforeNetworkSettings(batch);
    }

    private static byte[] bytes(ByteBuffer buffer) {
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }

    private static final class FakeConnection implements TransportConnection {
        private final String id;
        private final List<ByteBuffer> sent = new ArrayList<>();
        private Consumer<TransportMessage> messageListener;
        private Consumer<TransportCloseEvent> closeListener;
        private boolean open = true;

        private FakeConnection(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void setMessageListener(Consumer<TransportMessage> listener) {
            messageListener = listener;
        }

        @Override
        public void setCloseListener(Consumer<TransportCloseEvent> listener) {
            closeListener = listener;
        }

        @Override
        public TransportSendResult send(ByteBuffer payload, DeliveryMode deliveryMode) {
            if (!open) {
                return TransportSendResult.CLOSED;
            }
            ByteBuffer copy = ByteBuffer.allocate(payload.remaining());
            copy.put(payload.duplicate()).flip();
            sent.add(copy.asReadOnlyBuffer());
            return TransportSendResult.ACCEPTED;
        }

        @Override
        public void close() {
            if (!open) {
                return;
            }
            open = false;
            if (closeListener != null) {
                closeListener.accept(new TransportCloseEvent("fake closed", false));
            }
        }
    }
}
