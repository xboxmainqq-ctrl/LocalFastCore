package ru.nexora.core.api;

import ru.nexora.core.storage.KeyValueStore;

import java.util.Objects;

/** Stable entry point exposed to NEXORA modules. */
public final class NexoraAPI {
    private final ServiceRegistry services;
    private final EventBus events;
    private final KeyValueStore storage;

    public NexoraAPI(ServiceRegistry services, EventBus events, KeyValueStore storage) {
        this.services = Objects.requireNonNull(services, "services");
        this.events = Objects.requireNonNull(events, "events");
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public ServiceRegistry services() { return services; }
    public EventBus events() { return events; }
    public KeyValueStore storage() { return storage; }
    public String getVersion() { return "0.1.0"; }
}
