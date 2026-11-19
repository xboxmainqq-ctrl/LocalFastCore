package ru.nexora.network.nethernet;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Shared hard cap for the backing arrays retained by incomplete NetherNet messages.
 * Reservations account for allocated capacity (not just received payload length).
 */
public final class ReassemblyMemoryBudget {
    public static final long DEFAULT_MAX_BYTES = 64L * 1024L * 1024L;

    private final long maxBytes;
    private final AtomicLong reservedBytes = new AtomicLong();
    private final LongAdder rejectedReservations = new LongAdder();

    public ReassemblyMemoryBudget() {
        this(DEFAULT_MAX_BYTES);
    }

    public ReassemblyMemoryBudget(long maxBytes) {
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
        this.maxBytes = maxBytes;
    }

    public boolean tryReserve(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("bytes must not be negative");
        if (bytes == 0) return true;
        while (true) {
            long current = reservedBytes.get();
            if (bytes > maxBytes - current) {
                rejectedReservations.increment();
                return false;
            }
            if (reservedBytes.compareAndSet(current, current + bytes)) return true;
        }
    }

    public void release(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("bytes must not be negative");
        if (bytes == 0) return;
        long remaining = reservedBytes.addAndGet(-bytes);
        if (remaining < 0) {
            reservedBytes.addAndGet(bytes);
            throw new IllegalStateException("Reassembly memory reservation underflow");
        }
    }

    public Stats stats() {
        return new Stats(reservedBytes.get(), maxBytes, rejectedReservations.sum());
    }

    public record Stats(long reservedBytes, long maxBytes, long rejectedReservations) { }
}
