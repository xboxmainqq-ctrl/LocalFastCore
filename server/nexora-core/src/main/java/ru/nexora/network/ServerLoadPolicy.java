package ru.nexora.network;

import ru.nexora.runtime.LoadSnapshot;

import java.util.Objects;

/** Decides whether a server should accept additional load. */
public final class ServerLoadPolicy {
    public enum Decision { ACCEPT, ACCEPT_LIMITED, REDIRECT }

    private final int softPlayerLimit;
    private final int hardPlayerLimit;

    public ServerLoadPolicy(int softPlayerLimit, int hardPlayerLimit) {
        if (softPlayerLimit < 1 || hardPlayerLimit < softPlayerLimit)
            throw new IllegalArgumentException("invalid player limits");
        this.softPlayerLimit = softPlayerLimit;
        this.hardPlayerLimit = hardPlayerLimit;
    }

    public Decision decide(LoadSnapshot snapshot) {
        Objects.requireNonNull(snapshot);
        if (snapshot.players() >= hardPlayerLimit || snapshot.pressure() >= 0.97) return Decision.REDIRECT;
        if (snapshot.players() >= softPlayerLimit || snapshot.overloaded()) return Decision.ACCEPT_LIMITED;
        return Decision.ACCEPT;
    }
}
