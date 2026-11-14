package ru.nexora.core;

import cn.nukkit.plugin.PluginBase;
import ru.nexora.core.api.ServiceRegistry;

/**
 * Compatibility-first NEXORA foundation.
 * Nukkit-MOT remains responsible for loading legacy plugins.
 */
public final class NexoraCore extends PluginBase {
    private static volatile NexoraCore instance;
    private final ServiceRegistry services = new ServiceRegistry();

    @Override
    public void onEnable() {
        instance = this;
        getLogger().info("NexoraCore 0.1.0-SNAPSHOT enabled.");
        getLogger().info("Legacy Nukkit-MOT plugin loading remains enabled; Nexora APIs are ready.");
    }

    @Override
    public void onDisable() {
        if (instance == this) {
            instance = null;
        }
        getLogger().info("NexoraCore disabled.");
    }

    public static NexoraCore getInstance() {
        return instance;
    }

    public ServiceRegistry getServices() {
        return services;
    }

    public String getApiVersion() {
        return "1";
    }
}
