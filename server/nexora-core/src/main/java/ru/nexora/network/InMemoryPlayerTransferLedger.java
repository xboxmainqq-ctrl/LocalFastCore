package ru.nexora.network;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
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
    public synchronized Optional<PlayerTransferRecord> findLatestForPlayer(String playerXuid) {
        if (playerXuid == null || !playerXuid.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid player XUID");
        }
        return records.values().stream()
                .filter(record -> record.playerXuid().equals(playerXuid))
                .max(Comparator.comparingLong(PlayerTransferRecord::playerRevision)
                        .thenComparingLong(PlayerTransferRecord::updatedAtEpochMillis)
                        .thenComparing(record -> record.transferId().toString()));
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
    @Override
    public synchronized List<PlayerTransferRecord> listRecoverableForTarget(String targetNode, int limit) {
        validateRecoveryQuery(targetNode, limit);
        return records.values().stream()
                .filter(record -> record.targetNode().equals(targetNode))
                .filter(InMemoryPlayerTransferLedger::isRecoverable)
                .sorted(Comparator.comparingLong(PlayerTransferRecord::playerRevision).reversed()
                        .thenComparing(Comparator.comparingLong(PlayerTransferRecord::updatedAtEpochMillis).reversed())
                        .thenComparing(record -> record.transferId().toString()))
                .limit(limit)
                .toList();
    }

    private static boolean isRecoverable(PlayerTransferRecord record) {
        return record.state() == PlayerTransferRecord.State.CLAIMED
                || record.state() == PlayerTransferRecord.State.COMMITTED;
    }

    private static void validateRecoveryQuery(String targetNode, int limit) {
        if (targetNode == null || !targetNode.matches("[A-Za-z0-9._-]{1,64}"))
            throw new IllegalArgumentException("Invalid target node");
        if (limit < 1 || limit > 10_000) throw new IllegalArgumentException("Recovery query limit must be 1..10000");
    }
}

