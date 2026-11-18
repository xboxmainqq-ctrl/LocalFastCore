package ru.nexora.persistence;

import java.io.IOException;
import java.util.Optional;

/**
 * Coordinates canonical bundle encoding with the revisioned snapshot store.
 * Revisions are advanced from the durable latest value, never from wall-clock time.
 */
public final class PlayerStateCheckpointService {
    private final PlayerStateStore store;
    private final PlayerStateBundleCodec codec;
    private final Object[] playerLocks = createLocks();

    public PlayerStateCheckpointService(PlayerStateStore store, PlayerStateBundleCodec codec) {
        this.store = java.util.Objects.requireNonNull(store, "store");
        this.codec = java.util.Objects.requireNonNull(codec, "codec");
    }

    public PlayerStateStore.SaveResult checkpoint(PlayerStateBundle bundle, long capturedAtEpochMillis)
            throws IOException {
        java.util.Objects.requireNonNull(bundle, "bundle");
        if (capturedAtEpochMillis < 0) throw new IllegalArgumentException("capturedAtEpochMillis < 0");
        String xuid = bundle.profile().xuid();
        synchronized (lockFor(xuid)) {
            Optional<PlayerStateSnapshot> latest = store.loadLatest(xuid);
            byte[] payload = codec.encode(bundle);
            if (latest.isPresent()) {
                PlayerStateSnapshot current = latest.orElseThrow();
                PlayerStateSnapshot candidate = new PlayerStateSnapshot(
                        PlayerStateBundleCodec.SCHEMA_VERSION, xuid, current.revision(),
                        capturedAtEpochMillis, payload);
                if (current.schemaVersion() == candidate.schemaVersion()
                        && current.checksum().equals(candidate.checksum())) {
                    return PlayerStateStore.SaveResult.ALREADY_CURRENT;
                }
            }
            long revision = latest.map(PlayerStateSnapshot::revision).orElse(-1L);
            if (revision == Long.MAX_VALUE) throw new IOException("Player snapshot revision exhausted");
            PlayerStateSnapshot snapshot = new PlayerStateSnapshot(
                    PlayerStateBundleCodec.SCHEMA_VERSION, xuid, revision + 1,
                    capturedAtEpochMillis, payload);
            return store.save(snapshot);
        }
    }

    public Optional<PlayerStateBundle> load(String xuid) throws IOException {
        synchronized (lockFor(xuid)) {
            Optional<PlayerStateSnapshot> stored = store.loadLatest(xuid);
            if (stored.isEmpty()) return Optional.empty();
            PlayerStateSnapshot snapshot = stored.orElseThrow();
            if (snapshot.schemaVersion() != PlayerStateBundleCodec.SCHEMA_VERSION) {
                throw new IOException("Unsupported player state schema: " + snapshot.schemaVersion());
            }
            PlayerStateBundle bundle = codec.decode(snapshot.payload());
            if (!bundle.profile().xuid().equals(xuid)) {
                throw new IOException("Player state XUID does not match snapshot key");
            }
            return Optional.of(bundle);
        }
    }

    private Object lockFor(String xuid) {
        return playerLocks[Math.floorMod(xuid.hashCode(), playerLocks.length)];
    }

    private static Object[] createLocks() {
        Object[] locks = new Object[256];
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
        return locks;
    }
}
