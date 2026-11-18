package ru.nexora.world;

import ru.nexora.runtime.OwnershipGuard;
import ru.nexora.runtime.StateOwnership;

import java.util.Objects;

/** Explicit player lifecycle boundary independent from network transport. */
public final class PlayerLifecycle {
    public enum State { CONNECTING, ACTIVE, DISCONNECTING, DISCONNECTED }

    private final StateOwnership ownership;
    private State state = State.CONNECTING;

    public PlayerLifecycle(StateOwnership ownership) {
        this.ownership = Objects.requireNonNull(ownership);
    }

    public void activate() {
        transition(State.ACTIVE, State.CONNECTING);
    }

    public void beginDisconnect() {
        transition(State.DISCONNECTING, State.ACTIVE);
    }

    public void finishDisconnect() {
        transition(State.DISCONNECTED, State.DISCONNECTING);
    }

    public State state() { return state; }

    private void transition(State next, State expected) {
        try (OwnershipGuard ignored = OwnershipGuard.acquire(ownership, StateOwnership.Domain.PLAYER)) {
            if (state != expected) throw new IllegalStateException("Expected " + expected + ", got " + state);
            state = next;
        }
    }
}
