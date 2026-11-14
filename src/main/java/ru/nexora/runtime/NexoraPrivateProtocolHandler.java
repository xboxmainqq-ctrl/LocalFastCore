package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Handles the initial NEXORA-only protocol negotiation on a reliable transport.
 * A successful negotiation is not player authentication.
 */
public final class NexoraPrivateProtocolHandler implements Consumer<TransportConnection> {
    private static final int REJECT_UNSUPPORTED_VERSION = 1;
    private static final int REJECT_INVALID_HELLO = 2;

    private final UUID serverId;
    private final int maxMessageBytes;
    private final Consumer<TransportConnection> readyListener;

    public NexoraPrivateProtocolHandler(UUID serverId, int maxMessageBytes,
                                        Consumer<TransportConnection> readyListener) {
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        if (maxMessageBytes < 1 || maxMessageBytes > 8 * 1024 * 1024) {
            throw new IllegalArgumentException("maxMessageBytes is outside the supported range");
        }
        this.maxMessageBytes = maxMessageBytes;
        this.readyListener = Objects.requireNonNull(readyListener, "readyListener");
    }

    @Override
    public void accept(TransportConnection connection) {
        Objects.requireNonNull(connection, "connection");
        AtomicBoolean completed = new AtomicBoolean();
        connection.setCloseListener(event -> completed.set(true));
        connection.setMessageListener(message -> {
            if (completed.get() || !completed.compareAndSet(false, true)) {
                connection.close();
                return;
            }
            if (message.deliveryMode() != DeliveryMode.RELIABLE_ORDERED
                    || message.sizeBytes() > NexoraPrivateProtocolCodec.MAX_MESSAGE_BYTES) {
                connection.close();
                return;
            }
            try {
                NexoraPrivateProtocolCodec.ClientHello hello =
                        NexoraPrivateProtocolCodec.decodeClientHello(message.payload());
                if (hello.majorVersion() != NexoraPrivateProtocolCodec.MAJOR_VERSION
                        || hello.minorVersion() > NexoraPrivateProtocolCodec.MINOR_VERSION) {
                    connection.setMessageListener(ignored -> connection.close());
                    sendReject(connection, REJECT_UNSUPPORTED_VERSION,
                            "Unsupported NEXORA protocol version");
                    return;
                }
                TransportSendResult sent = connection.send(
                        ByteBuffer.wrap(NexoraPrivateProtocolCodec.encodeServerHello(
                                serverId, maxMessageBytes)),
                        DeliveryMode.RELIABLE_ORDERED);
                if (sent != TransportSendResult.ACCEPTED) {
                    connection.close();
                    return;
                }
                connection.setMessageListener(ignored -> {
                    // The next application protocol layer will replace this listener.
                });
                readyListener.accept(connection);
            } catch (RuntimeException malformed) {
                connection.setMessageListener(ignored -> connection.close());
                sendReject(connection, REJECT_INVALID_HELLO, "Invalid NEXORA client hello");
            }
        });
    }

    private static void sendReject(TransportConnection connection, int code, String reason) {
        TransportSendResult sent = connection.send(
                ByteBuffer.wrap(NexoraPrivateProtocolCodec.encodeReject(code, reason)),
                DeliveryMode.RELIABLE_ORDERED);
        if (sent != TransportSendResult.ACCEPTED) {
            connection.close();
        }
    }
}
