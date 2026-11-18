package ru.nexora.lifecycle;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Explicit admission state for the server process.
 *
 * <p>Only READY accepts new sessions. Shutdown closes admission before owned
 * resources are drained and persisted.</p>
 */
public final class ServerReadiness {
    public enum State { STARTING, READY, STOPPING, STOPPED }

    private final AtomicReference<State> state = new AtomicReference<>(State.STARTING);

    public State state() {
        return state.get();
    }

    public boolean isAcceptingConnections() {
        return state.get() == State.READY;
    }

    public void markReady() {
        if (!state.compareAndSet(State.STARTING, State.READY)) {
            throw new IllegalStateException("Server can become ready only from STARTING (was "
                    + state.get() + ")");
        }
    }

    /**
     * Stops new admission. Safe to call more than once, including during
     * startup rollback.
     */
    public void beginShutdown() {
        state.updateAndGet(current -> switch (current) {
            case STARTING, READY -> State.STOPPING;
            case STOPPING, STOPPED -> current;
        });
    }

    public void markStopped() {
        State current = state.get();
        if (current == State.STOPPED) return;
        if (current != State.STOPPING) {
            throw new IllegalStateException("Server can become stopped only from STOPPING (was "
                    + current + ")");
        }
        if (!state.compareAndSet(State.STOPPING, State.STOPPED)) {
            markStopped();
        }
    }
}
