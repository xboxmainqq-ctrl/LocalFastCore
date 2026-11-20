package ru.nexora.plugins.api;

import java.util.UUID;

/** Player operations intentionally kept independent from Nukkit/NEXORA classes. */
public interface PlayerPort {
    UUID id();

    String name();

    boolean hasPermission(String permission);

    void sendMessage(String message);

    Position position();

    boolean teleport(Position destination);
}
