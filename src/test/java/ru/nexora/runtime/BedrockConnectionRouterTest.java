package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BedrockConnectionRouterTest {
    private static final BedrockNetworkSettings SETTINGS = new BedrockNetworkSettings(
            1, BedrockNetworkSettings.CompressionAlgorithm.ZLIB, false, 0, 0.0f);

    @Test
    void routesReliableFramesSendsSettingsAndHandsLoginToAuthentication() throws Exception {
        FakeTransport transport = new FakeTransport();
        AtomicReference<BedrockPendingLogin> pending = new AtomicReference<>();
        BedrockConnectionRouter router = new BedrockConnectionRouter(
                transport, SETTINGS, pending::set);
        router.start();

        FakeConnection connection = new FakeConnection("peer-1");
        transport.accept(connection);
        connection.receive(frame(BedrockRequestNetworkSettingsPacketCodec.PACKET_ID,
                BedrockRequestNetworkSettingsPacketCodec.encodePayload(
                        new BedrockRequestNetworkSettingsPacket(2225)), false));

        assertEquals(1, connection.sent.size());
        assertEquals(DeliveryMode.RELIABLE_ORDERED, connection.lastSendMode);
        ByteBuffer responseBatch = BedrockBatchCompressionCodec.decodeBeforeNetworkSettings(
                connection.sent.get(0), 1024);
        List<BedrockGamePacket> response = BedrockPacketBatchCodec.decodeUncompressed(responseBatch);
        assertEquals(BedrockNetworkSettingsPacketCodec.PACKET_ID,
                response.get(0).header().packetId());

        connection.receive(frame(BedrockLoginPacketCodec.PACKET_ID,
                BedrockLoginPacketCodec.encodePayload(
                        new BedrockLoginPacket(2225, "opaque-auth-request")), true));

        BedrockPendingLogin login = pending.get();
        assertNotNull(login);
        assertEquals("opaque-auth-request", login.login().connectionRequest());
        assertEquals("peer-1", login.connection().id());
        assertEquals(BedrockHandshakeState.AUTHENTICATION_PENDING, login.state());

        assertEquals(BedrockHandshakeResult.ACCEPTED, login.markAuthenticated());
        assertEquals(BedrockHandshakeState.PLAY, login.state());

        router.stop();
        assertFalse(connection.isOpen());
        assertTrue(transport.stopped);
    }

    @Test
    void closesConnectionWhenHandshakeArrivesOnUnreliableChannel() throws Exception {
        FakeTransport transport = new FakeTransport();
        BedrockConnectionRouter router = new BedrockConnectionRouter(
                transport, SETTINGS, ignored -> fail("Authentication must not be requested"));
        router.start();
        FakeConnection connection = new FakeConnection("peer-2");
        transport.accept(connection);

        connection.receive(new TransportMessage(ByteBuffer.wrap(new byte[]{1}),
                DeliveryMode.UNRELIABLE));

        assertFalse(connection.isOpen());
        router.stop();
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

    private static final class FakeTransport implements NetworkTransport {
        private Consumer<TransportConnection> listener;
        private boolean stopped;

        @Override public String id() { return "fake"; }
        @Override public TransportCapabilities capabilities() {
            return new TransportCapabilities(true, true, true, false);
        }
        @Override public void setConnectionListener(Consumer<TransportConnection> listener) {
            this.listener = listener;
        }
        @Override public void start() { stopped = false; }
        @Override public void stop() { stopped = true; }
        void accept(TransportConnection connection) { listener.accept(connection); }
    }

    private static final class FakeConnection implements TransportConnection {
        private final String id;
        private Consumer<TransportMessage> messageListener;
        private Consumer<TransportCloseEvent> closeListener;
        private final java.util.ArrayList<ByteBuffer> sent = new java.util.ArrayList<>();
        private DeliveryMode lastSendMode;
        private boolean open = true;

        FakeConnection(String id) { this.id = id; }
        @Override public String id() { return id; }
        @Override public boolean isOpen() { return open; }
        @Override public void setMessageListener(Consumer<TransportMessage> listener) {
            this.messageListener = listener;
        }
        @Override public void setCloseListener(Consumer<TransportCloseEvent> listener) {
            this.closeListener = listener;
        }
        @Override public TransportSendResult send(ByteBuffer payload, DeliveryMode mode) {
            if (!open) return TransportSendResult.CLOSED;
            ByteBuffer copy = ByteBuffer.allocate(payload.remaining());
            copy.put(payload.duplicate()).flip();
            sent.add(copy.asReadOnlyBuffer());
            lastSendMode = mode;
            return TransportSendResult.ACCEPTED;
        }
        @Override public void close() {
            if (!open) return;
            open = false;
            if (closeListener != null) closeListener.accept(
                    new TransportCloseEvent("closed by test", false));
        }
        void receive(TransportMessage message) { messageListener.accept(message); }
    }
}
