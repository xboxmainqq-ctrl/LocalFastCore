package ru.nexora.runtime;

import java.util.Objects;

/** Scoped ownership helper for runtime state domains. */
public final class OwnershipGuard implements AutoCloseable {
    private final StateOwnership ownership;
    private final StateOwnership.Domain domain;
    private boolean closed;

    private OwnershipGuard(StateOwnership ownership, StateOwnership.Domain domain) {
        this.ownership = ownership;
        this.domain = domain;
        ownership.claim(domain);
    }

    public static OwnershipGuard acquire(StateOwnership ownership, StateOwnership.Domain domain) {
        return new OwnershipGuard(Objects.requireNonNull(ownership), Objects.requireNonNull(domain));
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            ownership.release(domain);
        }
    }
}
