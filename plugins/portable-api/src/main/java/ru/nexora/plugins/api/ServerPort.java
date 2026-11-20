package ru.nexora.plugins.api;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/** Server services exposed to portable plugin logic. */
public interface ServerPort {
    Collection<? extends PlayerPort> onlinePlayers();

    Optional<? extends PlayerPort> player(UUID playerId);

    void broadcast(String message);
}
