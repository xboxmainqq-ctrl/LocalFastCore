package ru.nexora.core.platform;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Platform Service Provider Interface.
 *
 * Implementations adapt a concrete server runtime (currently Nukkit-MOT)
 * to the NEXORA API. Keep this interface free of platform-specific types.
 */
public interface NexoraPlatform {
    String getPlatformName();

    String getPlatformVersion();

    Collection<PlayerSnapshot> getOnlinePlayers();

    Optional<PlayerSnapshot> findPlayer(UUID uniqueId);
}
