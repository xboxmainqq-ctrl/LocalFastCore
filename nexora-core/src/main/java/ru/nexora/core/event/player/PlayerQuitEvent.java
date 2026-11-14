package ru.nexora.core.event.player;

import ru.nexora.core.api.NexoraEvent;

import java.util.Objects;
import java.util.UUID;

/** Published when a player leaves the server. Contains stable identity data only. */
public final class PlayerQuitEvent implements NexoraEvent {
    private final UUID playerId;
    private final String playerName;

    public PlayerQuitEvent(UUID playerId, String playerName) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.playerName = Objects.requireNonNull(playerName, "playerName");
    }

    public UUID getPlayerId() { return playerId; }
    public String getPlayerName() { return playerName; }
}
