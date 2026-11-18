package ru.nexora.network;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * In-process reference state machine for a cross-node handoff.
 * Production must persist/replicate this ledger and use authenticated transport
 * before claiming crash-safe exactly-once transfer.
 */
public final class HandoffCoordinator {
    public enum State { PREPARED, CLAIMED, COMMITTED, ABORTED }

    private final Map<UUID, Transfer> transfers = new HashMap<>();

    public synchronized void prepare(PlayerHandoff handoff) {
        Objects.requireNonNull(handoff, "handoff");
        Transfer transfer = new Transfer(handoff.targetNode(), State.PREPARED);
        if (transfers.putIfAbsent(handoff.transferId(), transfer) != null)
            throw new IllegalStateException("Transfer already exists: " + handoff.transferId());
    }

    public synchronized boolean claim(UUID transferId, String destinationNode) {
        Transfer transfer = require(transferId);
        if (!transfer.destinationNode().equals(destinationNode) || transfer.state() != State.PREPARED)
            return false;
        transfers.put(transferId, transfer.withState(State.CLAIMED));
        return true;
    }

    public synchronized void commit(UUID transferId) {
        transition(transferId, State.CLAIMED, State.COMMITTED);
    }

    public synchronized void abort(UUID transferId) {
        Transfer transfer = require(transferId);
        if (transfer.state() == State.COMMITTED || transfer.state() == State.ABORTED)
            throw new IllegalStateException("Cannot abort transfer from " + transfer.state());
        transfers.put(transferId, transfer.withState(State.ABORTED));
    }

    public synchronized State state(UUID transferId) {
        return require(transferId).state();
    }

    private void transition(UUID id, State expected, State next) {
        Transfer transfer = require(id);
        if (transfer.state() != expected)
            throw new IllegalStateException("Expected " + expected + ", got " + transfer.state());
        transfers.put(id, transfer.withState(next));
    }

    private Transfer require(UUID id) {
        Transfer transfer = transfers.get(Objects.requireNonNull(id, "transferId"));
        if (transfer == null) throw new IllegalArgumentException("Unknown transfer: " + id);
        return transfer;
    }

    private record Transfer(String destinationNode, State state) {
        private Transfer withState(State next) { return new Transfer(destinationNode, next); }
    }
}
