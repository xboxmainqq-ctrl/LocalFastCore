package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class BedrockInitialTransportRouterTest {
    private static final BedrockNetworkSettings SETTINGS = new BedrockNetworkSettings(
            1, BedrockNetworkSettings.CompressionAlgorithm.ZLIB, false, 0, 0.0f);

    @Test
    void sendsSettingsAuthenticatesThenForwardsPlayMessages() {
        AtomicInteger playStarts = new AtomicInteger();
        AtomicInteger playMessages = new AtomicInteger();
        BedrockInitialTransportRouter router = new BedrockInitialTransportRouter(
                SETTINGS,
                (connection, login) -> {
                    assertEquals("test-login", login.connectionRequest());
                    return CompletableFuture.completedFuture(true);
                },
                connection -> playStarts.incrementAndGet(),
                (connection, message) -> playMessages.incrementAndGet());

        FakeConnection connection = new FakeConnection("peer-1");
        router.accept(connection);
        connection.receive(frame(BedrockRequestNetworkSettingsPacketCodec.PACKET_ID,
                BedrockRequestNetworkSettingsPacketCodec.encodePayload(
                        new BedrockRequestNetworkSettingsPacket(2225)), false));

        assertEquals(1, connection.sent.size());
        assertEquals(BedrockNetworkSettingsPacketCodec.PACKET_ID,
                BedrockPacketBatchCodec.decodeUncompressed(
                        BedrockBatchCompressionCodec.decodeBeforeNetworkSettings(
                                connection.sent.get(0), 1024)).get(0).header().packetId());

        connection.receive(frame(BedrockLoginPacketCodec.PACKET_ID,
                BedrockLoginPacketCodec.encodePayload(new BedrockLoginPacket(2225, "test-login")),
                true));

        assertEquals(1, playStarts.get());
        assertTrue(connection.isOpen());
        connection.receive(new TransportMessage(ByteBuffer.wrap(new byte[]{9, 8, 7}),
                DeliveryMode.UNRELIABLE));
        assertEquals(1, playMessages.get());

        router.close();
        assertFalse(connection.isOpen());
    }

    @Test
    void rejectsLoginWhenAuthenticatorDeniesIt() {
        BedrockInitialTransportRouter router = new BedrockInitialTransportRouter(
                SETTINGS,
                (connection, login) -> CompletableFuture.completedFuture(false),
                connection -> fail("Rejected login must not enter play"),
                (connection, message) -> fail("Rejected login must not receive play messages"));
        FakeConnection connection = new FakeConnection("peer-2");
        router.accept(connection);

        connection.receive(frame(BedrockRequestNetworkSettingsPacketCodec.PACKET_ID,
                BedrockRequestNetworkSettingsPacketCodec.encodePayload(
                        new BedrockRequestNetworkSettingsPacket(2225)), false));
        connection.receive(frame(BedrockLoginPacketCodec.PACKET_ID,
                BedrockLoginPacketCodec.encodePayload(new BedrockLoginPacket(2225, "denied")),
                true));

        assertFalse(connection.isOpen());
        router.close();
    }

    @Test
    void rejectsHandshakeFramesOnUnreliableDelivery() {
        BedrockInitialTransportRouter router = new BedrockInitialTransportRouter(
                SETTINGS,
                (connection, login) -> CompletableFuture.completedFuture(true),
                connection -> fail("Invalid handshake must not enter play"),
                (connection, message) -> fail("Invalid handshake must not reach play"));
        FakeConnection connection = new FakeConnection("peer-3");
        router.accept(connection);

        connection.receive(new TransportMessage(ByteBuffer.wrap(new byte[]{1}),
                DeliveryMode.UNRELIABLE));

        assertFalse(connection.isOpen());
        router.close();
    }

    private static ByteBuffer frame(int packetId, ByteBuffer payload, boolean compressed) {
        byte[] bytes = new byte[payload.remaining()];
        payload.get(bytes);
        ByteBuffer batch = BedrockPacketBatchCodec.encodeUncompressed(List.of(
                new BedrockGamePacket(new BedrockPacketHeader(packetId, 0, 0), bytes)));
        return compressed
                ? BedrockBatchCompressionCodec.encodeAfterNetworkSettings(batch, SETTINGS)
                : BedrockBatchCompressionCodec.encodeBeforeNetworkSettings(batch);
    }

    private static final class FakeConnection implements TransportConnection {
        private final String id;
        private Consumer<TransportMessage> messageListener;
        private Consumer<TransportCloseEvent> closeListener;
        private final List<ByteBuffer> sent = new ArrayList<>();
        private boolean open = true;

        private FakeConnection(String id) { this.id = id; }
        @Override public String id() { return id; }
        @Override public boolean isOpen() { return open; }
        @Override public void setMessageListener(Consumer<TransportMessage> listener) {
            messageListener = listener;
        }
        @Override public void setCloseListener(Consumer<TransportCloseEvent> listener) {
            closeListener = listener;
        }
        @Override public TransportSendResult send(ByteBuffer payload, DeliveryMode mode) {
            if (!open) return TransportSendResult.CLOSED;
            ByteBuffer copy = ByteBuffer.allocate(payload.remaining());
            copy.put(payload.duplicate()).flip();
            sent.add(copy.asReadOnlyBuffer());
            return TransportSendResult.ACCEPTED;
        }
        @Override public void close() {
            if (!open) return;
            open = false;
            if (closeListener != null) closeListener.accept(
                    new TransportCloseEvent("closed in test", false));
        }
        private void receive(TransportMessage message) {
            assertNotNull(messageListener);
            messageListener.accept(message);
        }
    }
}
