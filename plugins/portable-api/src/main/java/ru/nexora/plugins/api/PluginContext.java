package ru.nexora.plugins.api;

import java.nio.file.Path;
import java.util.Objects;

/** Services supplied by the active server adapter during plugin startup. */
public record PluginContext(ServerPort server, SchedulerPort scheduler,
                            PluginLogger logger, Path dataDirectory) {
    public PluginContext {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(dataDirectory, "dataDirectory");
    }
}
