package ru.nexora.core;

import cn.nukkit.plugin.PluginBase;
import ru.nexora.core.api.EventBus;
import ru.nexora.core.api.NexoraAPI;
import ru.nexora.core.api.ServiceRegistry;
import ru.nexora.core.api.SimpleEventBus;
import ru.nexora.core.storage.KeyValueStore;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Compatibility-first NEXORA foundation.
 * Nukkit-MOT remains responsible for loading legacy plugins.
 */
public final class NexoraCore extends PluginBase {
    private static volatile NexoraCore instance;

    private final ServiceRegistry services = new ServiceRegistry();
    private SimpleEventBus eventBus;
    private KeyValueStore storage;
    private NexoraAPI api;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        try {
            Path dataFile = getDataFolder().toPath().resolve("nexora-data.properties");
            storage = new KeyValueStore(dataFile);
            eventBus = new SimpleEventBus();
            api = new NexoraAPI(services, eventBus, storage);

            services.register(EventBus.class, eventBus);
            services.register(KeyValueStore.class, storage);
            services.register(NexoraAPI.class, api);

            getLogger().info("NexoraCore API 0.1.0 enabled.");
            getLogger().info("Services ready: event bus, persistent key-value storage.");
            getLogger().info("Nukkit-MOT legacy plugin loading remains enabled.");
        } catch (IOException | RuntimeException exception) {
            getLogger().error("NexoraCore could not initialize: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (eventBus != null) {
            eventBus.clear();
        }
        if (storage != null) {
            try {
                storage.close();
            } catch (IOException exception) {
                getLogger().error("Could not save NEXORA data: " + exception.getMessage());
            }
        }

        if (api != null) {
            services.unregister(NexoraAPI.class, api);
        }
        if (eventBus != null) {
            services.unregister(EventBus.class, eventBus);
        }
        if (storage != null) {
            services.unregister(KeyValueStore.class, storage);
        }

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

    public NexoraAPI getApi() {
        return api;
    }

    public String getApiVersion() {
        return "0.1.0";
    }
}
