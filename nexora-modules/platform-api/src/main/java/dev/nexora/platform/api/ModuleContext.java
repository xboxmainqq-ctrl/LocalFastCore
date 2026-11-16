package dev.nexora.platform.api;

import java.util.Objects;

/**
 * Services and host information made available to a module during loading.
 */
public record ModuleContext(
        NexoraServer server,
        ServiceRegistry services,
        NexoraLogger logger
) {
    public ModuleContext {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(services, "services");
        Objects.requireNonNull(logger, "logger");
    }
}
