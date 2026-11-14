package ru.nexora.core.api;

import ru.nexora.core.platform.NexoraPlatform;
import ru.nexora.core.storage.KeyValueStore;
import ru.nexora.core.security.NexoraSecurityManager;
import ru.nexora.core.profile.PlayerProgressionService;
import ru.nexora.core.rpg.RpgClassRegistry;

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
    private final RpgClassRegistry classes;

    /** Compatibility constructor retained for modules/tests without a live adapter. */
    public NexoraAPI(ServiceRegistry services, EventBus events, KeyValueStore storage) {
        this(services, events, storage, null, null, null, null);
    }

    public NexoraAPI(ServiceRegistry services, EventBus events, KeyValueStore storage,
                     NexoraPlatform platform) {
        this(services, events, storage, platform, null, null, null);
    }

    public NexoraAPI(ServiceRegistry services, EventBus events, KeyValueStore storage,
                     NexoraPlatform platform, NexoraSecurityManager security) {
        this(services, events, storage, platform, security, null, null);
    }

    public NexoraAPI(ServiceRegistry services, EventBus events, KeyValueStore storage,
                     NexoraPlatform platform, NexoraSecurityManager security,
                     PlayerProgressionService progression) {
        this(services, events, storage, platform, security, progression, null);
    }

    public NexoraAPI(ServiceRegistry services, EventBus events, KeyValueStore storage,
                     NexoraPlatform platform, NexoraSecurityManager security,
                     PlayerProgressionService progression, RpgClassRegistry classes) {
        this.services = Objects.requireNonNull(services, "services");
        this.events = Objects.requireNonNull(events, "events");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.platform = platform;
        this.security = security;
        this.progression = progression;
        this.classes = classes;
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

    /** Empty for compatibility-only API instances without class modules. */
    public Optional<RpgClassRegistry> classes() {
        return Optional.ofNullable(classes);
    }

    public String getVersion() { return "0.1.0"; }
}
