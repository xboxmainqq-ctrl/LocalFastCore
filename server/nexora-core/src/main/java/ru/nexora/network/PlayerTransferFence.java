package ru.nexora.network;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Runtime ownership guard for a destination transfer claim.
 *
 * Session/gameplay adapters must call assertCurrent() immediately before every
 * authoritative player-state mutation and must stop the session when it fails.
 * This check is intentionally backed by the durable ledger, not a local cache.
 */
public final class PlayerTransferFence {
    private final DurableHandoffCoordinator ledger;
    private final UUID transferId;
    private final String targetNode;
    private final String playerXuid;
    private final long playerRevision;
    private final long fencingToken;
    private final LongSupplier epochMillis;

    public PlayerTransferFence(DurableHandoffCoordinator ledger, PlayerTransferRecord claim,
                               LongSupplier epochMillis) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(claim, "claim");
        if (claim.state() != PlayerTransferRecord.State.CLAIMED || claim.fencingToken() <= 0) {
            throw new IllegalArgumentException("Fence must be created from an active CLAIMED record");
        }
        this.transferId = claim.transferId();
        this.targetNode = claim.targetNode();
        this.playerXuid = claim.playerXuid();
        this.playerRevision = claim.playerRevision();
        this.fencingToken = claim.fencingToken();
        this.epochMillis = Objects.requireNonNull(epochMillis, "epochMillis");
    }

    public UUID transferId() { return transferId; }
    public String targetNode() { return targetNode; }
    public String playerXuid() { return playerXuid; }
    public long playerRevision() { return playerRevision; }
    public long fencingToken() { return fencingToken; }

    /** Throws unless this token still owns the claimed or committed player transfer. */
    public void assertCurrent() throws IOException {
        PlayerTransferRecord current = ledger.get(transferId).orElseThrow(
                () -> new IOException("Player transfer record disappeared"));
        boolean sameOwner = current.fencingToken() == fencingToken
                && current.targetNode().equals(targetNode)
                && current.playerXuid().equals(playerXuid)
                && current.playerRevision() == playerRevision;
        boolean activeClaim = current.state() == PlayerTransferRecord.State.CLAIMED
                && !current.leaseExpired(epochMillis.getAsLong());
        boolean committed = current.state() == PlayerTransferRecord.State.COMMITTED;
        if (!sameOwner || (!activeClaim && !committed)) {
            throw new IOException("Player transfer fencing token is no longer authoritative");
        }
    }

    /** Requires durable COMMITTED ownership before gameplay admission or persistence. */
    public void assertCommitted() throws IOException {
        PlayerTransferRecord current = ledger.get(transferId).orElseThrow(
                () -> new IOException("Player transfer record disappeared"));
        boolean sameOwner = current.fencingToken() == fencingToken
                && current.targetNode().equals(targetNode)
                && current.playerXuid().equals(playerXuid)
                && current.playerRevision() == playerRevision;
        if (!sameOwner || current.state() != PlayerTransferRecord.State.COMMITTED) {
            throw new IOException("Player transfer is not durably committed to this owner");
        }
    }

    /** True only while this fence is the active, unexpired claim (before commit). */
    public boolean isActiveClaim() throws IOException {
        PlayerTransferRecord current = ledger.get(transferId).orElse(null);
        return current != null && current.state() == PlayerTransferRecord.State.CLAIMED
                && current.fencingToken() == fencingToken
                && current.targetNode().equals(targetNode)
                && current.playerXuid().equals(playerXuid)
                && current.playerRevision() == playerRevision
                && !current.leaseExpired(epochMillis.getAsLong());
    }
}
