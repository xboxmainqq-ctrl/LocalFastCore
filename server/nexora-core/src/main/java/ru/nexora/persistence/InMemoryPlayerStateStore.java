package ru.nexora.persistence;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe reference implementation for tests and local development only.
 * Data is lost when the process exits; this is not crash-recovery storage.
 */
public final class InMemoryPlayerStateStore implements PlayerStateStore {
    private final ConcurrentMap<Long, PlayerStateSnapshot> snapshots = new ConcurrentHashMap<>();

    @Override
    public SaveResult save(PlayerStateSnapshot snapshot) {
        if (snapshot == null) throw new NullPointerException("snapshot");
        if (!snapshot.hasValidChecksum()) throw new IllegalArgumentException("Invalid snapshot checksum");

        final SaveResult[] result = new SaveResult[1];
        snapshots.compute(snapshot.playerId(), (playerId, current) -> {
            if (current == null || snapshot.revision() > current.revision()) {
                result[0] = SaveResult.SAVED;
                return snapshot;
            }
            if (snapshot.revision() < current.revision()) {
                result[0] = SaveResult.STALE;
                return current;
            }
            if (snapshot.checksum().equals(current.checksum())
                    && snapshot.schemaVersion() == current.schemaVersion()) {
                result[0] = SaveResult.ALREADY_CURRENT;
                return current;
            }
            throw new IllegalStateException("Conflicting player snapshot revision "
                    + snapshot.revision() + " for player " + playerId);
        });
        return result[0];
    }

    @Override
    public Optional<PlayerStateSnapshot> loadLatest(long playerId) {
        if (playerId < 0) throw new IllegalArgumentException("playerId < 0");
        return Optional.ofNullable(snapshots.get(playerId));
    }

    public int playerCount() {
        return snapshots.size();
    }
}
