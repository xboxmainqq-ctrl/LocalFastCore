package ru.nexora.core.api;

import ru.nexora.core.platform.NexoraPlatform;
import ru.nexora.core.storage.KeyValueStore;

import java.util.Objects;
import java.util.Optional;

/** Stable entry point exposed to NEXORA modules. */
public final class NexoraAPI {
    private final ServiceRegistry services;
    private final EventBus events;
    private final KeyValueStore storage;
    private final NexoraPlatform platform;

    /**
     * Compatibility constructor retained for modules/tests that create an API
     * without a live server adapter.
     */
    public NexoraAPI(ServiceRegistry services, EventBus events, KeyValueStore storage) {
        this(services, events, storage, null);
    }

    public NexoraAPI(ServiceRegistry services, EventBus events, KeyValueStore storage,
                     NexoraPlatform platform) {
        this.services = Objects.requireNonNull(services, "services");
        this.events = Objects.requireNonNull(events, "events");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.platform = platform;
    }

    public ServiceRegistry services() { return services; }
    public EventBus events() { return events; }
    public KeyValueStore storage() { return storage; }

    /** Empty when the API was created without a server platform adapter. */
    public Optional<NexoraPlatform> platform() { return Optional.ofNullable(platform); }

    public String getVersion() { return "0.1.0"; }
}
