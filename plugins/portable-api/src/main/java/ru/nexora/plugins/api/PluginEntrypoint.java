package ru.nexora.plugins.api;

/** Lifecycle implemented by platform-specific plugin entrypoints. */
public interface PluginEntrypoint {
    void onEnable(PluginContext context);

    void onDisable();
}
