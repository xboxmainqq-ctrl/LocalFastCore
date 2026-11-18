package ru.nexora.network;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * CAS-based transfer state machine. Expired claims can be reclaimed only with a
 * strictly higher fencing token, allowing destinations to reject stale owners.
 */
public final class PlayerTransferCoordinator {
    private static final int MAX_CAS_ATTEMPTS = 32;
    private final PlayerTransferLedger ledger;

    public PlayerTransferCoordinator(PlayerTransferLedger ledger) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    public boolean prepare(PlayerTransferRecord record) throws IOException {
        return ledger.create(Objects.requireNonNull(record, "record"));
    }

    public Optional<PlayerTransferRecord> claim(UUID id, String destinationNode,
                                                long nowEpochMillis, long leaseMillis) throws IOException {
        if (nowEpochMillis < 0 || leaseMillis <= 0) throw new IllegalArgumentException("Invalid claim time/lease");
        long leaseUntil;
        try { leaseUntil = Math.addExact(nowEpochMillis, leaseMillis); }
        catch (ArithmeticException overflow) { throw new IllegalArgumentException("Lease expiry overflow", overflow); }
        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            PlayerTransferRecord current = ledger.get(id).orElseThrow(
                    () -> new IllegalArgumentException("Unknown transfer: " + id));
            if (!current.targetNode().equals(destinationNode)) return Optional.empty();
            if (current.state() != PlayerTransferRecord.State.PREPARED
                    && !(current.state() == PlayerTransferRecord.State.CLAIMED
                    && current.leaseExpired(nowEpochMillis))) return Optional.empty();
            if (current.fencingToken() == Long.MAX_VALUE) throw new IOException("Transfer fencing token exhausted");
            PlayerTransferRecord next = current.claimed(current.fencingToken() + 1, leaseUntil, nowEpochMillis);
            if (ledger.compareAndSet(id, current.version(), next)) return Optional.of(next);
        }
        throw new IOException("Transfer claim CAS contention exceeded retry limit");
    }

    public Optional<PlayerTransferRecord> commit(UUID id, long fencingToken,
                                                 long nowEpochMillis) throws IOException {
        return transitionClaim(id, fencingToken, nowEpochMillis, true);
    }

    public Optional<PlayerTransferRecord> abort(UUID id, long fencingToken,
                                                long nowEpochMillis) throws IOException {
        return transitionClaim(id, fencingToken, nowEpochMillis, false);
    }

    private Optional<PlayerTransferRecord> transitionClaim(UUID id, long fencingToken,
                                                            long now, boolean commit) throws IOException {
        if (fencingToken <= 0 || now < 0) throw new IllegalArgumentException("Invalid fence/time");
        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            PlayerTransferRecord current = ledger.get(id).orElseThrow(
                    () -> new IllegalArgumentException("Unknown transfer: " + id));
            if (current.state() != PlayerTransferRecord.State.CLAIMED
                    || current.fencingToken() != fencingToken || current.leaseExpired(now)) {
                return Optional.empty();
            }
            PlayerTransferRecord next = commit ? current.committed(now) : current.aborted(now);
            if (ledger.compareAndSet(id, current.version(), next)) return Optional.of(next);
        }
        throw new IOException("Transfer transition CAS contention exceeded retry limit");
    }

    public Optional<PlayerTransferRecord> get(UUID id) throws IOException {
        return ledger.get(id);
    }
}
