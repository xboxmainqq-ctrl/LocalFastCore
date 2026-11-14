package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class NexoraPrivateProtocolHandlerTest {
    @Test
    void repliesToSupportedClientAndSignalsReady() {
        UUID serverId = UUID.randomUUID();
        AtomicInteger ready = new AtomicInteger();
        FakeConnection connection = new FakeConnection();
        new NexoraPrivateProtocolHandler(serverId, 1024 * 1024,
                ignored -> ready.incrementAndGet()).accept(connection);

        connection.deliver(NexoraPrivateProtocolCodec.encodeClientHello("android-dev"));

        assertEquals(1, ready.get());
        assertFalse(connection.closed);
        NexoraPrivateProtocolCodec.ServerResponse response =
                NexoraPrivateProtocolCodec.decodeServerResponse(ByteBuffer.wrap(connection.sent));
        assertTrue(response.accepted());
        assertEquals(serverId, response.serverId());
        assertEquals(1024 * 1024, response.maxMessageBytes());
    }

    @Test
    void rejectsUnsupportedMajorVersionDeterministically() {
        FakeConnection connection = new FakeConnection();
        new NexoraPrivateProtocolHandler(UUID.randomUUID(), 1024, ignored -> fail("must not become ready"))
                .accept(connection);
        byte[] hello = NexoraPrivateProtocolCodec.encodeClientHello("android-dev");
        hello[4] = 2;

        connection.deliver(hello);

        NexoraPrivateProtocolCodec.ServerResponse response =
                NexoraPrivateProtocolCodec.decodeServerResponse(ByteBuffer.wrap(connection.sent));
        assertFalse(response.accepted());
        assertEquals(1, response.rejectionCode());
    }

    @Test
    void rejectsMalformedHelloWithoutPromotingConnection() {
        FakeConnection connection = new FakeConnection();
        new NexoraPrivateProtocolHandler(UUID.randomUUID(), 1024, ignored -> fail("must not become ready"))
                .accept(connection);

        connection.deliver(new byte[] {1, 2, 3});

        NexoraPrivateProtocolCodec.ServerResponse response =
                NexoraPrivateProtocolCodec.decodeServerResponse(ByteBuffer.wrap(connection.sent));
        assertFalse(response.accepted());
        assertEquals(2, response.rejectionCode());
    }

    private static final class FakeConnection implements TransportConnection {
        private Consumer<TransportMessage> messageListener;
        private Consumer<TransportCloseEvent> closeListener;
        private byte[] sent;
        private boolean closed;

        void deliver(byte[] bytes) {
            messageListener.accept(new TransportMessage(
                    ByteBuffer.wrap(bytes), DeliveryMode.RELIABLE_ORDERED));
        }

        @Override public String id() { return "test-peer"; }
        @Override public boolean isOpen() { return !closed; }
        @Override public void setMessageListener(Consumer<TransportMessage> listener) {
            messageListener = listener;
        }
        @Override public void setCloseListener(Consumer<TransportCloseEvent> listener) {
            closeListener = listener;
        }
        @Override public TransportSendResult send(ByteBuffer payload, DeliveryMode mode) {
            ByteBuffer copy = payload.duplicate();
            sent = new byte[copy.remaining()];
            copy.get(sent);
            return closed ? TransportSendResult.CLOSED : TransportSendResult.ACCEPTED;
        }
        @Override public void close() {
            if (!closed) {
                closed = true;
                if (closeListener != null) closeListener.accept(new TransportCloseEvent("closed"));
            }
        }
    }
}
