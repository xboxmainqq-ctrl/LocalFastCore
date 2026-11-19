package ru.nexora.runtime;

import java.util.EnumMap;
import java.util.Map;

/**
 * Explicit ownership boundary for mutable server state.
 *
 * <p>Ownership is checked by logical execution domain. This is the safety
 * boundary that must exist before parallel world mutation is considered.</p>
 */
public final class StateOwnership {
    public enum Domain { PLAYER, WORLD, ENTITY, GAMEPLAY, PERSISTENCE }

    private final Map<Domain, Thread> owners = new EnumMap<>(Domain.class);

    public synchronized void claim(Domain domain) {
        Thread current = Thread.currentThread();
        Thread owner = owners.get(domain);
        if (owner == null) {
            owners.put(domain, current);
            return;
        }
        if (owner != current) {
            throw new IllegalStateException("State domain " + domain + " is owned by " + owner.getName());
        }
    }

    public synchronized void release(Domain domain) {
        Thread current = Thread.currentThread();
        Thread owner = owners.get(domain);
        if (owner == null) return;
        if (owner != current) {
            throw new IllegalStateException("Only the owner can release " + domain);
        }
        owners.remove(domain);
    }

    public synchronized boolean ownedByCurrentThread(Domain domain) {
        return owners.get(domain) == Thread.currentThread();
    }
}
