package ru.nexora.network.nethernet;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * NetherNet's one-byte data-channel message header and reliable-channel
 * fragmentation/reassembly.
 */
public final class NetherNetMessageFramer {
    public static final int HEADER_BYTES = 1;
    public static final int MAX_FRAGMENTS = 256;
    public static final int DEFAULT_MAX_MESSAGE_BYTES = 16 * 1024 * 1024;

    private NetherNetMessageFramer() {
    }

    /**
     * Frames a message for one NetherNet data channel.
     *
     * @param message application payload without a NetherNet header
     * @param maxChannelMessageBytes maximum complete SCTP message size, including the header
     * @param reliable whether the channel is ReliableDataChannel
     */
    public static List<byte[]> fragment(
            byte[] message,
            int maxChannelMessageBytes,
            boolean reliable
    ) {
        Objects.requireNonNull(message, "message");
        if (maxChannelMessageBytes < 2) {
            throw new IllegalArgumentException("maxChannelMessageBytes must be at least 2");
        }
        if (message.length > DEFAULT_MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("Message exceeds the 16 MiB safety limit");
        }

        int maxPayload = maxChannelMessageBytes - HEADER_BYTES;
        if (message.length <= maxPayload) {
            return List.of(withHeader(0, message, 0, message.length));
        }
        if (!reliable) {
            throw new IllegalArgumentException("NetherNet fragmentation is only allowed on reliable channels");
        }

        int fragmentCount = (message.length + maxPayload - 1) / maxPayload;
        if (fragmentCount > MAX_FRAGMENTS) {
            throw new IllegalArgumentException("Message requires more than 256 fragments");
        }

        List<byte[]> fragments = new ArrayList<>(fragmentCount);
        int offset = 0;
        for (int index = 0; index < fragmentCount; index++) {
            int length = Math.min(maxPayload, message.length - offset);
            int remainingFragments = fragmentCount - index - 1;
            fragments.add(withHeader(remainingFragments, message, offset, length));
            offset += length;
        }
        return List.copyOf(fragments);
    }

    private static byte[] withHeader(int header, byte[] payload, int offset, int length) {
        byte[] framed = new byte[length + HEADER_BYTES];
        framed[0] = (byte) header;
        System.arraycopy(payload, offset, framed, HEADER_BYTES, length);
        return framed;
    }

    /**
     * Stateful reassembler. Use one instance per incoming data channel because
     * reliable SCTP delivery preserves the fragment order on that channel.
     */
    public static final class Reassembler {
        private final int maxMessageBytes;
        private ByteArrayOutputStream pending;
        private int expectedNextHeader;

        public Reassembler() {
            this(DEFAULT_MAX_MESSAGE_BYTES);
        }

        public Reassembler(int maxMessageBytes) {
            if (maxMessageBytes < 1 || maxMessageBytes > DEFAULT_MAX_MESSAGE_BYTES) {
                throw new IllegalArgumentException("maxMessageBytes must be 1..16 MiB");
            }
            this.maxMessageBytes = maxMessageBytes;
        }

        public synchronized Optional<byte[]> accept(byte[] frame, boolean reliable) {
            Objects.requireNonNull(frame, "frame");
            if (frame.length < HEADER_BYTES) {
                reset();
                throw new IllegalArgumentException("NetherNet frame is missing its header byte");
            }

            int header = Byte.toUnsignedInt(frame[0]);
            int payloadLength = frame.length - HEADER_BYTES;
            if (!reliable && header != 0) {
                reset();
                throw new IllegalArgumentException("Unreliable channel cannot carry fragmented messages");
            }

            if (payloadLength > maxMessageBytes) {
                reset();
                throw new IllegalArgumentException("NetherNet fragment exceeds configured message limit");
            }

            if (pending == null) {
                if (header == 0) {
                    ensureWithinLimit(payloadLength);
                    byte[] payload = new byte[payloadLength];
                    System.arraycopy(frame, HEADER_BYTES, payload, 0, payloadLength);
                    return Optional.of(payload);
                }
                pending = new ByteArrayOutputStream(Math.min(maxMessageBytes, payloadLength + 256));
                expectedNextHeader = header - 1;
            } else if (header != expectedNextHeader) {
                reset();
                throw new IllegalArgumentException("Out-of-order NetherNet fragment: expected header "
                        + expectedNextHeader + " but received " + header);
            }

            if (pending.size() + payloadLength > maxMessageBytes) {
                reset();
                throw new IllegalArgumentException("Reassembled NetherNet message exceeds configured limit");
            }
            pending.write(frame, HEADER_BYTES, payloadLength);

            if (header == 0) {
                byte[] complete = pending.toByteArray();
                reset();
                return Optional.of(complete);
            }

            expectedNextHeader = header - 1;
            return Optional.empty();
        }

        public synchronized void reset() {
            pending = null;
            expectedNextHeader = 0;
        }

        private void ensureWithinLimit(int size) {
            if (size > maxMessageBytes) {
                throw new IllegalArgumentException("NetherNet message exceeds configured limit");
            }
        }
    }
}
