package ru.nexora.core.listener;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import ru.nexora.core.api.EventBus;

import java.util.Objects;

/** Bridges Nukkit player lifecycle events into the public NEXORA event bus. */
public final class NexoraPlayerListener implements Listener {
    private final EventBus eventBus;

    public NexoraPlayerListener(EventBus eventBus) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
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
        eventBus.publish(new ru.nexora.core.event.player.PlayerQuitEvent(
                player.getUniqueId(), player.getName()));
    }
}
