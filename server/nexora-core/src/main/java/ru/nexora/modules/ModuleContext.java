package ru.nexora.modules;

import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

/** Capability-limited services made available to one module. */
public final class ModuleContext {
    private final String moduleId;
    private final Set<ModuleCapability> capabilities;
    private final Logger logger;

    ModuleContext(String moduleId, Set<ModuleCapability> capabilities, Logger logger) {
        this.moduleId = Objects.requireNonNull(moduleId, "moduleId");
        this.capabilities = Set.copyOf(capabilities);
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public String moduleId() {
        return moduleId;
    }

    public Set<ModuleCapability> capabilities() {
        return capabilities;
    }

    public Logger logger() {
        return logger;
    }

    public boolean has(ModuleCapability capability) {
        return capabilities.contains(Objects.requireNonNull(capability, "capability"));
    }

    public void require(ModuleCapability capability) {
        if (!has(capability)) {
            throw new SecurityException("Module " + moduleId + " does not have capability " + capability);
        }
    }
}
