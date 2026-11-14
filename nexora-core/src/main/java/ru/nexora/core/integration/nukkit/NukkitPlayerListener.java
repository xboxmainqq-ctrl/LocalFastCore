package ru.nexora.core.integration.nukkit;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import ru.nexora.core.api.EventBus;
import ru.nexora.core.rpg.AbilityCooldownService;

import java.util.Objects;

/** Converts Nukkit lifecycle callbacks into platform-neutral NEXORA events. */
public final class NukkitPlayerListener implements Listener {
    private final EventBus eventBus;
    private final AbilityCooldownService cooldowns;

    public NukkitPlayerListener(EventBus eventBus) {
        this(eventBus, null);
    }

    public NukkitPlayerListener(EventBus eventBus, AbilityCooldownService cooldowns) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.cooldowns = cooldowns;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        eventBus.publish(new ru.nexora.core.event.player.PlayerJoinEvent(
                player.getUniqueId(), player.getName()));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (cooldowns != null) {
            cooldowns.clearPlayer(player.getUniqueId());
        }
        eventBus.publish(new ru.nexora.core.event.player.PlayerQuitEvent(
                player.getUniqueId(), player.getName()));
    }
}
