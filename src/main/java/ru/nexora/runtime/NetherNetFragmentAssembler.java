package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;

/**
 * Reassembles the one-byte fragment framing used by a single NetherNet
 * data-channel direction.
 *
 * <p>Create one instance per channel. The caller must only pass fragments
 * from that channel, in channel delivery order. This class does not reorder
 * fragments or combine fragments from different messages.</p>
 *
 * <p>The header is an unsigned count of fragments remaining after the current
 * fragment. A zero header is a complete, unfragmented message (or the final
 * fragment when an assembly is active). Positive headers begin or continue
 * an assembly and must count down by exactly one.</p>
 *
 * <p>All memory use is bounded by the supplied limits. A malformed sequence,
 * oversized message, or expired assembly clears the partial message before
 * failing, so stale bytes cannot contaminate the next message.</p>
 */
public final class NetherNetFragmentAssembler {
    private final int maxMessageBytes;
    private final int maxFragments;
    private final long timeoutNanos;
    private final LongSupplierNanoTime clock;

    private byte[] assembly;
    private int assemblySize;
    private int expectedRemaining;
    private long lastFragmentNanos;

    public NetherNetFragmentAssembler(int maxMessageBytes, int maxFragments,
                                      long timeoutNanos) {
        this(maxMessageBytes, maxFragments, timeoutNanos, System::nanoTime);
    }

    NetherNetFragmentAssembler(int maxMessageBytes, int maxFragments,
                               long timeoutNanos, LongSupplierNanoTime clock) {
        if (maxMessageBytes < 1) {
            throw new IllegalArgumentException("maxMessageBytes must be positive");
        }
        if (maxFragments < 1 || maxFragments > 255) {
            throw new IllegalArgumentException("maxFragments must be between 1 and 255");
        }
        if (timeoutNanos < 1) {
            throw new IllegalArgumentException("timeoutNanos must be positive");
        }
        this.maxMessageBytes = maxMessageBytes;
        this.maxFragments = maxFragments;
        this.timeoutNanos = timeoutNanos;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Accepts one data-channel message including its NetherNet fragment header.
     *
     * @return a read-only complete payload when the message is complete;
     *         otherwise an empty optional
     */
    public Optional<ByteBuffer> accept(ByteBuffer framedFragment) {
        Objects.requireNonNull(framedFragment, "framedFragment");
        if (!framedFragment.hasRemaining()) {
            reset();
            throw new IllegalArgumentException("Missing NetherNet fragment header");
        }

        long now = clock.nanoTime();
        expireIfNeeded(now);

        ByteBuffer fragment = framedFragment.slice();
        int remaining = Byte.toUnsignedInt(fragment.get());
        int payloadBytes = fragment.remaining();

        if (assembly == null) {
            if (remaining == 0) {
                if (payloadBytes > maxMessageBytes) {
                    throw new IllegalArgumentException("Message exceeds configured byte limit");
                }
                return Optional.of(copyPayload(fragment));
            }
            if (remaining + 1 > maxFragments) {
                throw new IllegalArgumentException("Fragment count exceeds configured limit");
            }
            ensureFits(payloadBytes);
            assembly = new byte[maxMessageBytes];
            assemblySize = 0;
            expectedRemaining = remaining;
            append(fragment);
            lastFragmentNanos = now;
            return Optional.empty();
        }

        if (remaining != expectedRemaining - 1) {
            reset();
            throw new IllegalArgumentException("Unexpected NetherNet fragment sequence");
        }
        ensureFits(payloadBytes);
        append(fragment);
        expectedRemaining = remaining;
        lastFragmentNanos = now;

        if (remaining != 0) {
            return Optional.empty();
        }

        ByteBuffer complete = ByteBuffer.allocate(assemblySize);
        complete.put(assembly, 0, assemblySize).flip();
        reset();
        return Optional.of(complete.asReadOnlyBuffer());
    }

    /** Discards any incomplete message, for example when the channel closes. */
    public void reset() {
        assembly = null;
        assemblySize = 0;
        expectedRemaining = 0;
        lastFragmentNanos = 0;
    }

    public boolean hasIncompleteMessage() {
        return assembly != null;
    }

    private void expireIfNeeded(long now) {
        if (assembly != null && now - lastFragmentNanos >= timeoutNanos) {
            reset();
            throw new IllegalArgumentException("NetherNet fragment assembly timed out");
        }
    }

    private void ensureFits(int additionalBytes) {
        if (additionalBytes > maxMessageBytes - assemblySize) {
            reset();
            throw new IllegalArgumentException("Reassembled message exceeds configured byte limit");
        }
    }

    private void append(ByteBuffer fragment) {
        int count = fragment.remaining();
        fragment.get(assembly, assemblySize, count);
        assemblySize += count;
    }

    private static ByteBuffer copyPayload(ByteBuffer payload) {
        ByteBuffer copy = ByteBuffer.allocate(payload.remaining());
        copy.put(payload).flip();
        return copy.asReadOnlyBuffer();
    }

    @FunctionalInterface
    interface LongSupplierNanoTime {
        long nanoTime();
    }
}
