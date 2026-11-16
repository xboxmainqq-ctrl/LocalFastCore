package ru.nexora.mot;

import cn.nukkit.plugin.PluginBase;

/**
 * Entry point for the NEXORA integration layer on Nukkit-MOT.
 *
 * Keep this adapter isolated from the standalone NEXORA runtime. Gameplay
 * features will be added here only after their MOT API contracts are verified.
 */
public final class NexoraMotPlugin extends PluginBase {

    @Override
    public void onEnable() {
        getLogger().info("NEXORA MOT integration enabled.");
        getLogger().info("Target: Nukkit-MOT 1.26.60-R1 / Java 21.");
    }

    @Override
    public void onDisable() {
        getLogger().info("NEXORA MOT integration disabled.");
    }
}
