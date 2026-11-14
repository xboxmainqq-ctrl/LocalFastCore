package ru.nexora.core.profile;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence boundary for player profiles.
 *
 * Implementations must key profiles by the stable account UUID, never by
 * nickname. Storage engines stay behind this interface so the core API does
 * not depend on SQLite, Nukkit, or a particular standalone runtime.
 */
public interface PlayerProfileRepository extends AutoCloseable {
    Optional<PlayerProfile> find(UUID uniqueId) throws IOException;

    void save(PlayerProfile profile) throws IOException;

    boolean delete(UUID uniqueId) throws IOException;

    @Override
    void close() throws IOException;
}
