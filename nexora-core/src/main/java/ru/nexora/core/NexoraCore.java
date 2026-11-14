package ru.nexora.core;

import cn.nukkit.plugin.PluginBase;
import ru.nexora.core.api.EventBus;
import ru.nexora.core.api.NexoraAPI;
import ru.nexora.core.api.ServiceRegistry;
import ru.nexora.core.api.SimpleEventBus;
import ru.nexora.core.integration.nukkit.NukkitPlatformAdapter;
import ru.nexora.core.integration.nukkit.NukkitPlayerListener;
import ru.nexora.core.platform.NexoraPlatform;
import ru.nexora.core.storage.KeyValueStore;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Compatibility host bootstrap.
 * Nukkit-MOT-specific wiring is isolated in the integration.nukkit package.
 */
public final class NexoraCore extends PluginBase {
    private static volatile NexoraCore instance;

    private final ServiceRegistry services = new ServiceRegistry();
    private SimpleEventBus eventBus;
    private KeyValueStore storage;
    private NexoraPlatform platform;
    private NexoraAPI api;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        try {
            Path dataFile = getDataFolder().toPath().resolve("nexora-data.properties");
            storage = new KeyValueStore(dataFile);
            eventBus = new SimpleEventBus();
            platform = new NukkitPlatformAdapter(this);
            api = new NexoraAPI(services, eventBus, storage, platform);

            services.register(EventBus.class, eventBus);
            services.register(KeyValueStore.class, storage);
            services.register(NexoraPlatform.class, platform);
            services.register(NexoraAPI.class, api);

            getServer().getPluginManager().registerEvents(new NukkitPlayerListener(eventBus), this);

            getLogger().info("NexoraCore API 0.1.0 enabled.");
            getLogger().info("Services ready: event bus, persistent key-value storage, platform adapter.");
            getLogger().info("Player lifecycle bridge registered: join, quit.");
            getLogger().info("Platform adapter: " + platform.getPlatformName()
                    + " " + platform.getPlatformVersion());
            if (!getConfig().getBoolean("core.compatibility-mode", true)) {
                getLogger().warning("Compatibility mode is disabled in config, but Nukkit-MOT remains the plugin loader.");
            }
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
        if (platform != null) {
            services.unregister(NexoraPlatform.class, platform);
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
