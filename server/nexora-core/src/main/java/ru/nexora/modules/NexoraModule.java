package ru.nexora.modules;

/** Lifecycle contract implemented by Java modules and adapters for other runtimes. */
public interface NexoraModule {
    ModuleDescriptor descriptor();

    /** Called once after dependencies and permissions have been validated. */
    default void onLoad(ModuleContext context) throws Exception {
    }

    /** Called after every registered module has loaded successfully. */
    default void onEnable() throws Exception {
    }

    /** Called in reverse dependency order during shutdown or failed startup rollback. */
    default void onDisable() throws Exception {
    }
}
