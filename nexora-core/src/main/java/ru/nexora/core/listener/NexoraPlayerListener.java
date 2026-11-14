package ru.nexora.core.listener;

import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.Player;
import ru.nexora.core.api.EventBus;
import ru.nexora.core.event.player.PlayerJoinEvent as NexoraPlayerJoinEvent;
import ru.nexora.core.event.player.PlayerQuitEvent as NexoraPlayerQuitEvent;

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
        eventBus.publish(new NexoraPlayerJoinEvent(player.getUniqueId(), player.getName()));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        eventBus.publish(new NexoraPlayerQuitEvent(player.getUniqueId(), player.getName()));
    }
}
