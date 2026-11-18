package ru.nexora.network;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * In-process reference state machine for a cross-node handoff.
 * A production deployment must persist/replicate this ledger and use an
 * authenticated transport before claiming crash-safe exactly-once transfer.
 */
public final class HandoffCoordinator {
    public enum State { PREPARED, CLAIMED, COMMITTED, ABORTED }

    private final Map<UUID, State> transfers = new HashMap<>();

    public synchronized void prepare(PlayerHandoff handoff) {
        Objects.requireNonNull(handoff, "handoff");
        if (transfers.putIfAbsent(handoff.transferId(), State.PREPARED) != null)
            throw new IllegalStateException("Transfer already exists: " + handoff.transferId());
    }

    public synchronized boolean claim(UUID transferId, String destinationNode) {
        Objects.requireNonNull(transferId, "transferId");
        State state = require(transferId);
        if (state != State.PREPARED) return false;
        transfers.put(transferId, State.CLAIMED);
        return true;
    }

    public synchronized void commit(UUID transferId) {
        transition(transferId, State.CLAIMED, State.COMMITTED);
    }

    public synchronized void abort(UUID transferId) {
        State state = require(transferId);
        if (state == State.COMMITTED || state == State.ABORTED)
            throw new IllegalStateException("Cannot abort transfer from " + state);
        transfers.put(transferId, State.ABORTED);
    }

    public synchronized State state(UUID transferId) {
        return require(transferId);
    }

    private void transition(UUID id, State expected, State next) {
        State current = require(id);
        if (current != expected) throw new IllegalStateException("Expected " + expected + ", got " + current);
        transfers.put(id, next);
    }

    private State require(UUID id) {
        State state = transfers.get(Objects.requireNonNull(id, "transferId"));
        if (state == null) throw new IllegalArgumentException("Unknown transfer: " + id);
        return state;
    }
}
