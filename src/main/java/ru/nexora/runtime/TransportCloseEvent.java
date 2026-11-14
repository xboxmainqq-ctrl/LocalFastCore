package ru.nexora.runtime;

import java.util.Objects;

/** Describes why a transport connection closed. */
public record TransportCloseEvent(String description, boolean remote) {
    public TransportCloseEvent {
        Objects.requireNonNull(description, "description");
        if (description.isBlank()) {
            throw new IllegalArgumentException("Close description must not be blank");
        }
    }
}
