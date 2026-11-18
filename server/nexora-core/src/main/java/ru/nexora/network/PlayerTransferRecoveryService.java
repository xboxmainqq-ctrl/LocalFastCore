package ru.nexora.network;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Rehydrates destination-side pending player restores after a process restart.
 *
 * The durable transfer ledger remains authoritative. Only a current CLAIMED
 * lease or a COMMITTED ownership record may be registered for Bedrock login;
 * expired claims are never restored from stale ownership.
 */
public final class PlayerTransferRecoveryService {
    public record RecoveryReport(int scanned, int registered, int alreadyRegistered,
                                 int expiredClaims, int supersededRecords) {
        public RecoveryReport {
            if (scanned < 0 || registered < 0 || alreadyRegistered < 0
                    || expiredClaims < 0 || supersededRecords < 0) {
                throw new IllegalArgumentException("Recovery counts must not be negative");
            }
        }
    }

    private final DurableHandoffCoordinator ledger;
    private final PendingPlayerTransferRestoreRegistry registry;
    private final String nodeId;
    private final LongSupplier epochMillis;

    public PlayerTransferRecoveryService(
            DurableHandoffCoordinator ledger,
            PendingPlayerTransferRestoreRegistry registry,
            String nodeId,
            LongSupplier epochMillis
    ) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.registry = Objects.requireNonNull(registry, "registry");
        if (nodeId == null || !nodeId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("Invalid recovery node ID");
        }
        this.nodeId = nodeId;
        this.epochMillis = Objects.requireNonNull(epochMillis, "epochMillis");
    }

    /**
     * Rebuilds a bounded set of login reservations. Newest records win when
     * the ledger contains historical transfers for the same XUID.
     */
    public RecoveryReport recover(int limit) throws IOException {
        if (limit < 1 || limit > 10_000) {
            throw new IllegalArgumentException("Recovery limit must be 1..10000");
        }
        long now = epochMillis.getAsLong();
        if (now < 0) throw new IllegalStateException("Recovery clock returned a negative timestamp");

        List<PlayerTransferRecord> records = ledger.listRecoverableForTarget(nodeId, limit);
        Set<String> seenPlayers = new HashSet<>();
        int registered = 0;
        int alreadyRegistered = 0;
        int expiredClaims = 0;
        int superseded = 0;

        for (PlayerTransferRecord record : records) {
            if (!nodeId.equals(record.targetNode())) {
                throw new IOException("Transfer recovery returned a record for another destination");
            }
            if (!seenPlayers.add(record.playerXuid())) {
                superseded++;
                continue;
            }
            if (record.state() == PlayerTransferRecord.State.CLAIMED
                    && record.leaseExpired(now)) {
                expiredClaims++;
                continue;
            }
            if (record.state() != PlayerTransferRecord.State.CLAIMED
                    && record.state() != PlayerTransferRecord.State.COMMITTED) {
                continue;
            }

            PlayerTransferFence fence = new PlayerTransferFence(ledger, record, epochMillis);
            PendingPlayerTransferRestoreRegistry.PendingRestore restore =
                    new PendingPlayerTransferRestoreRegistry.PendingRestore(
                            record.playerXuid(), record.playerRevision(), fence);
            var existing = registry.find(record.playerXuid());
            if (existing.isPresent()) {
                PlayerTransferFence existingFence = existing.orElseThrow().fence();
                if (existingFence.transferId().equals(record.transferId())
                        && existingFence.fencingToken() == record.fencingToken()) {
                    alreadyRegistered++;
                    continue;
                }
                throw new IOException("Conflicting pending transfer restore for XUID "
                        + record.playerXuid());
            }
            try {
                registry.register(restore);
                registered++;
            } catch (IllegalStateException stale) {
                // A concurrent reclaim/abort invalidated this record during recovery.
                if (record.state() == PlayerTransferRecord.State.CLAIMED
                        && record.leaseExpired(epochMillis.getAsLong())) {
                    expiredClaims++;
                    continue;
                }
                throw new IOException("Unable to rehydrate transfer restore for XUID "
                        + record.playerXuid(), stale);
            }
        }
        return new RecoveryReport(records.size(), registered, alreadyRegistered,
                expiredClaims, superseded);
    }
}
