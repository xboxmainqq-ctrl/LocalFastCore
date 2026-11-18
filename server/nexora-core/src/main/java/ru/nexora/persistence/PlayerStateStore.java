package ru.nexora.persistence;

import java.util.Optional;

/**
 * Storage contract for immutable player checkpoints.
 *
 * Implementations must make save() atomic per player. A lower revision must
 * never replace a newer one. Durable adapters must preserve this guarantee
 * across process restarts, not merely within one JVM.
 */
public interface PlayerStateStore {
    enum SaveResult { SAVED, ALREADY_CURRENT, STALE }

    SaveResult save(PlayerStateSnapshot snapshot);

    Optional<PlayerStateSnapshot> loadLatest(String playerId);
}
