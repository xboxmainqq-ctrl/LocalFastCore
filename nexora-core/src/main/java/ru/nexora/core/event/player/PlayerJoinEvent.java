package ru.nexora.core.event.player;

import ru.nexora.core.api.NexoraEvent;

import java.util.Objects;
import java.util.UUID;

/** Published after a player joins the server. Contains stable identity data only. */
public final class PlayerJoinEvent implements NexoraEvent {
    private final UUID playerId;
    private final String playerName;

    public PlayerJoinEvent(UUID playerId, String playerName) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.playerName = Objects.requireNonNull(playerName, "playerName");
    }

    public UUID getPlayerId() { return playerId; }
    public String getPlayerName() { return playerName; }
}
