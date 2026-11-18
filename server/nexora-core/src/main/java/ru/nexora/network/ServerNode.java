package ru.nexora.network;

import ru.nexora.runtime.LoadSnapshot;

import java.util.Objects;

/** Immutable description of one independently running NEXORA server process. */
public record ServerNode(String id, String address, String profile, boolean healthy, LoadSnapshot load) {
    public ServerNode {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is blank");
        if (address == null || address.isBlank()) throw new IllegalArgumentException("address is blank");
        if (profile == null || profile.isBlank()) throw new IllegalArgumentException("profile is blank");
        Objects.requireNonNull(load, "load");
    }
}
