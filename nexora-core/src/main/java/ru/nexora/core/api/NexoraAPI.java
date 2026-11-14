package ru.nexora.core.api;

import ru.nexora.core.platform.NexoraPlatform;
import ru.nexora.core.storage.KeyValueStore;
import ru.nexora.core.security.NexoraSecurityManager;

import java.util.Objects;
import java.util.Optional;

/** Stable entry point exposed to NEXORA modules. */
public final class NexoraAPI {
    private final ServiceRegistry services;
    private final EventBus events;
    private final KeyValueStore storage;
    private final NexoraPlatform platform;
    private final NexoraSecurityManager security;

    /** Compatibility constructor retained for modules/tests without a live adapter. */
    public NexoraAPI(ServiceRegistry services, EventBus events, KeyValueStore storage) {
        this(services, events, storage, null, null);
    }

    public NexoraAPI(ServiceRegistry services, EventBus events, KeyValueStore storage,
                     NexoraPlatform platform) {
        this(services, events, storage, platform, null);
    }

    public NexoraAPI(ServiceRegistry services, EventBus events, KeyValueStore storage,
                     NexoraPlatform platform, NexoraSecurityManager security) {
        this.services = Objects.requireNonNull(services, "services");
        this.events = Objects.requireNonNull(events, "events");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.platform = platform;
        this.security = security;
    }

    public ServiceRegistry services() { return services; }
    public EventBus events() { return events; }
    public KeyValueStore storage() { return storage; }

    /** Empty when the API was created without a server platform adapter. */
    public Optional<NexoraPlatform> platform() { return Optional.ofNullable(platform); }

    /** Empty when the host has not wired runtime security services. */
    public Optional<NexoraSecurityManager> security() { return Optional.ofNullable(security); }

    public String getVersion() { return "0.1.0"; }
}
