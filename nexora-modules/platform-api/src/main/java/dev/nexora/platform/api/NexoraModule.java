package dev.nexora.platform.api;

/**
 * Lifecycle contract implemented by modules loaded by a NEXORA host.
 *
 * The host calls load, enable and disable in that order. Disable may also be
 * called after a partial startup, so implementations should be safe to clean up.
 */
public interface NexoraModule {
    ModuleDescriptor descriptor();

    default void onLoad(ModuleContext context) throws Exception {
    }

    default void onEnable() throws Exception {
    }

    default void onDisable() throws Exception {
    }
}
