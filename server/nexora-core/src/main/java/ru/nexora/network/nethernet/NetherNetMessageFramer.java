package ru.nexora.network.nethernet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
    public static final int DEFAULT_MAX_MESSAGE_BYTES = 4 * 1024 * 1024;
    public static final long DEFAULT_MAX_ASSEMBLY_NANOS = 15_000_000_000L;

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
            throw new IllegalArgumentException("Message exceeds the 4 MiB safety limit");
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
        // The backing list is local and never escapes; wrap it without copying its array.
        return Collections.unmodifiableList(fragments);
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
        private final long maxAssemblyNanos;
        private final ReassemblyMemoryBudget memoryBudget;
        private byte[] pendingBytes;
        private int pendingSize;
        private int expectedNextHeader;
        private long pendingStartedAtNanos;
        private boolean closed;

        public Reassembler() {
            this(DEFAULT_MAX_MESSAGE_BYTES, DEFAULT_MAX_ASSEMBLY_NANOS, null);
        }

        public Reassembler(int maxMessageBytes) {
            this(maxMessageBytes, DEFAULT_MAX_ASSEMBLY_NANOS, null);
        }

        public Reassembler(int maxMessageBytes, long maxAssemblyNanos) {
            this(maxMessageBytes, maxAssemblyNanos, null);
        }

        public Reassembler(int maxMessageBytes, long maxAssemblyNanos,
                           ReassemblyMemoryBudget memoryBudget) {
            if (maxMessageBytes < 1 || maxMessageBytes > DEFAULT_MAX_MESSAGE_BYTES) {
                throw new IllegalArgumentException("maxMessageBytes must be 1..4 MiB");
            }
            if (maxAssemblyNanos < 1) {
                throw new IllegalArgumentException("maxAssemblyNanos must be positive");
            }
            this.maxMessageBytes = maxMessageBytes;
            this.maxAssemblyNanos = maxAssemblyNanos;
            this.memoryBudget = memoryBudget;
        }

        public synchronized Optional<byte[]> accept(byte[] frame, boolean reliable) {
            return accept(frame, reliable, System.nanoTime());
        }

        /** Monotonic-time overload for deterministic assembly timeout tests. */
        public synchronized Optional<byte[]> accept(byte[] frame, boolean reliable, long nowNanos) {
            Objects.requireNonNull(frame, "frame");
            if (closed) {
                throw new IllegalStateException("NetherNet reassembler is closed");
            }
            if (pendingBytes != null) {
                long elapsed = nowNanos - pendingStartedAtNanos;
                if (elapsed < 0 || elapsed > maxAssemblyNanos) {
                    reset();
                    throw new IllegalArgumentException("Fragmented NetherNet message assembly timed out");
                }
            }
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

            if (pendingBytes == null) {
                if (header == 0) {
                    ensureWithinLimit(payloadLength);
                    byte[] payload = new byte[payloadLength];
                    System.arraycopy(frame, HEADER_BYTES, payload, 0, payloadLength);
                    return Optional.of(payload);
                }
                int initialCapacity = Math.min(maxMessageBytes, Math.max(256, payloadLength));
                reserveCapacity(initialCapacity);
                pendingBytes = new byte[initialCapacity];
                pendingSize = 0;
                pendingStartedAtNanos = nowNanos;
                expectedNextHeader = header - 1;
            } else if (header != expectedNextHeader) {
                reset();
                throw new IllegalArgumentException("Out-of-order NetherNet fragment: expected header "
                        + expectedNextHeader + " but received " + header);
            }

            if (payloadLength > maxMessageBytes - pendingSize) {
                reset();
                throw new IllegalArgumentException("Reassembled NetherNet message exceeds configured limit");
            }
            ensurePendingCapacity(pendingSize + payloadLength);
            System.arraycopy(frame, HEADER_BYTES, pendingBytes, pendingSize, payloadLength);
            pendingSize += payloadLength;

            if (header == 0) {
                byte[] complete = Arrays.copyOf(pendingBytes, pendingSize);
                reset();
                return Optional.of(complete);
            }

            expectedNextHeader = header - 1;
            return Optional.empty();
        }

        public synchronized void reset() {
            if (pendingBytes != null && memoryBudget != null) {
                memoryBudget.release(pendingBytes.length);
            }
            pendingBytes = null;
            pendingSize = 0;
            expectedNextHeader = 0;
            pendingStartedAtNanos = 0L;
        }

        /** Permanently closes this channel state and releases any retained assembly buffer. */
        public synchronized void close() {
            closed = true;
            reset();
        }

        private void ensurePendingCapacity(int required) {
            if (required <= pendingBytes.length) return;
            int grown = Math.max(required, pendingBytes.length << 1);
            int capacity = Math.min(maxMessageBytes, grown);
            reserveCapacity(capacity - pendingBytes.length);
            pendingBytes = Arrays.copyOf(pendingBytes, capacity);
        }

        private void reserveCapacity(int additionalBytes) {
            if (memoryBudget != null && !memoryBudget.tryReserve(additionalBytes)) {
                reset();
                throw new IllegalStateException("Aggregate NetherNet reassembly memory budget exceeded");
            }
        }

        private void ensureWithinLimit(int size) {
            if (size > maxMessageBytes) {
                throw new IllegalArgumentException("NetherNet message exceeds configured limit");
            }
        }
    }
}
