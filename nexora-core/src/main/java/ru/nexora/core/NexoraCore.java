package ru.nexora.core;

import cn.nukkit.plugin.PluginBase;
import ru.nexora.core.api.EventBus;
import ru.nexora.core.api.NexoraAPI;
import ru.nexora.core.api.ServiceRegistry;
import ru.nexora.core.api.SimpleEventBus;
import ru.nexora.core.integration.nukkit.NukkitPlatformAdapter;
import ru.nexora.core.integration.nukkit.NukkitPlayerListener;
import ru.nexora.core.platform.NexoraPlatform;
import ru.nexora.core.profile.PlayerProgressionService;
import ru.nexora.core.profile.PlayerProfileRepository;
import ru.nexora.core.profile.SqlitePlayerProfileRepository;
import ru.nexora.core.rpg.RpgClassRegistry;
import ru.nexora.core.rpg.RpgAbilityRegistry;
import ru.nexora.core.rpg.AbilityCooldownService;
import ru.nexora.core.security.AuditLogger;
import ru.nexora.core.security.IntegrityChecker;
import ru.nexora.core.security.NexoraSecurityManager;
import ru.nexora.core.security.NexoraAccessController;
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
    private NexoraSecurityManager security;
    private PlayerProfileRepository profileRepository;
    private PlayerProgressionService progression;
    private RpgClassRegistry classes;
    private RpgAbilityRegistry abilities;
    private AbilityCooldownService cooldowns;
    private NexoraAPI api;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        try {
            Path dataFolder = getDataFolder().toPath();
            storage = new KeyValueStore(dataFolder.resolve("nexora-data.properties"));
            eventBus = new SimpleEventBus();
            platform = new NukkitPlatformAdapter(this);

            AuditLogger auditLogger = new AuditLogger(dataFolder.resolve("security-audit.jsonl"));
            NexoraAccessController accessController = new NexoraAccessController(auditLogger);
            security = new NexoraSecurityManager(new IntegrityChecker(), auditLogger, accessController);

            profileRepository = new SqlitePlayerProfileRepository(
                    dataFolder.resolve("players.sqlite"));
            classes = new RpgClassRegistry();
            abilities = new RpgAbilityRegistry();
            cooldowns = new AbilityCooldownService();
            progression = new PlayerProgressionService(profileRepository, classes, abilities);
            api = new NexoraAPI(services, eventBus, storage, platform, security,
                    progression, classes, abilities, cooldowns);

            services.register(EventBus.class, eventBus);
            services.register(KeyValueStore.class, storage);
            services.register(NexoraPlatform.class, platform);
            services.register(NexoraSecurityManager.class, security);
            services.register(PlayerProfileRepository.class, profileRepository);
            services.register(PlayerProgressionService.class, progression);
            services.register(RpgClassRegistry.class, classes);
            services.register(RpgAbilityRegistry.class, abilities);
            services.register(AbilityCooldownService.class, cooldowns);
            services.register(NexoraAPI.class, api);

            getServer().getPluginManager().registerEvents(new NukkitPlayerListener(eventBus), this);

            getLogger().info("NexoraCore API 0.1.0 enabled.");
            getLogger().info("RPG services ready: UUID profiles, progression, class and ability registries, cooldown tracking.");
            getLogger().info("Services ready: event bus, persistent storage, platform adapter, security and RPG progression.");
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
        if (profileRepository != null) {
            try {
                profileRepository.close();
            } catch (IOException exception) {
                getLogger().error("Could not close RPG profile database: " + exception.getMessage());
            }
        }
        if (storage != null) {
            try {
                storage.close();
            } catch (IOException exception) {
                getLogger().error("Could not save NEXORA data: " + exception.getMessage());
            }
        }

        if (api != null) services.unregister(NexoraAPI.class, api);
        if (progression != null) services.unregister(PlayerProgressionService.class, progression);
        if (classes != null) services.unregister(RpgClassRegistry.class, classes);
        if (abilities != null) services.unregister(RpgAbilityRegistry.class, abilities);
        if (cooldowns != null) services.unregister(AbilityCooldownService.class, cooldowns);
        if (profileRepository != null) services.unregister(PlayerProfileRepository.class, profileRepository);
        if (platform != null) services.unregister(NexoraPlatform.class, platform);
        if (security != null) services.unregister(NexoraSecurityManager.class, security);
        if (eventBus != null) services.unregister(EventBus.class, eventBus);
        if (storage != null) services.unregister(KeyValueStore.class, storage);

        if (instance == this) instance = null;
        getLogger().info("NexoraCore disabled.");
    }

    public static NexoraCore getInstance() { return instance; }
    public ServiceRegistry getServices() { return services; }
    public NexoraAPI getApi() { return api; }
    public String getApiVersion() { return "0.1.0"; }
}
