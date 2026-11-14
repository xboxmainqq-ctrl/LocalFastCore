package ru.nexora.core.api;

import ru.nexora.core.platform.NexoraPlatform;
import ru.nexora.core.storage.KeyValueStore;
import ru.nexora.core.security.NexoraSecurityManager;
import ru.nexora.core.profile.PlayerProgressionService;

import java.util.Objects;
import java.util.Optional;

/** Stable entry point exposed to NEXORA modules. */
public final class NexoraAPI {
    private final ServiceRegistry services;
    private final EventBus events;
    private final KeyValueStore storage;
    private final NexoraPlatform platform;
    private final NexoraSecurityManager security;
    private final PlayerProgressionService progression;

    /** Compatibility constructor retained for modules/tests without a live adapter. */
    public NexoraAPI(ServiceRegistry services, EventBus events, KeyValueStore storage) {
        this(services, events, storage, null, null, null);
    }

    public NexoraAPI(ServiceRegistry services, EventBus events, KeyValueStore storage,
                     NexoraPlatform platform) {
        this(services, events, storage, platform, null, null);
    }

    public NexoraAPI(ServiceRegistry services, EventBus events, KeyValueStore storage,
                     NexoraPlatform platform, NexoraSecurityManager security) {
        this(services, events, storage, platform, security, null);
    }

    public NexoraAPI(ServiceRegistry services, EventBus events, KeyValueStore storage,
                     NexoraPlatform platform, NexoraSecurityManager security,
                     PlayerProgressionService progression) {
        this.services = Objects.requireNonNull(services, "services");
        this.events = Objects.requireNonNull(events, "events");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.platform = platform;
        this.security = security;
        this.progression = progression;
    }

    public ServiceRegistry services() { return services; }
    public EventBus events() { return events; }
    public KeyValueStore storage() { return storage; }

    /** Empty when the API was created without a server platform adapter. */
    public Optional<NexoraPlatform> platform() { return Optional.ofNullable(platform); }

    /** Empty when the host has not wired runtime security services. */
    public Optional<NexoraSecurityManager> security() { return Optional.ofNullable(security); }

    /** Empty for compatibility-only API instances without RPG persistence. */
    public Optional<PlayerProgressionService> progression() {
        return Optional.ofNullable(progression);
    }

    public String getVersion() { return "0.1.0"; }
}
