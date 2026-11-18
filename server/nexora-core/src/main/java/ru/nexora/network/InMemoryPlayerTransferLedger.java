package ru.nexora.network;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Thread-safe test/reference ledger. It is intentionally not durable. */
public final class InMemoryPlayerTransferLedger implements PlayerTransferLedger {
    private final Map<UUID, PlayerTransferRecord> records = new HashMap<>();

    @Override
    public synchronized Optional<PlayerTransferRecord> get(UUID transferId) {
        return Optional.ofNullable(records.get(Objects.requireNonNull(transferId, "transferId")));
    }

    @Override
    public synchronized boolean create(PlayerTransferRecord prepared) {
        Objects.requireNonNull(prepared, "prepared");
        if (prepared.state() != PlayerTransferRecord.State.PREPARED || prepared.version() != 0
                || prepared.fencingToken() != 0) {
            throw new IllegalArgumentException("Initial transfer record must be PREPARED at version zero");
        }
        return records.putIfAbsent(prepared.transferId(), prepared) == null;
    }

    @Override
    public synchronized boolean compareAndSet(UUID transferId, long expectedVersion,
                                              PlayerTransferRecord replacement) {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(replacement, "replacement");
        if (!transferId.equals(replacement.transferId()) || replacement.version() != expectedVersion + 1)
            throw new IllegalArgumentException("Invalid transfer CAS replacement");
        PlayerTransferRecord current = records.get(transferId);
        if (current == null || current.version() != expectedVersion
                || !replacement.isLegalSuccessorOf(current)) return false;
        records.put(transferId, replacement);
        return true;
    }
}
