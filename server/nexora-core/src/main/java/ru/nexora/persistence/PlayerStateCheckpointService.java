package ru.nexora.persistence;

import java.io.IOException;
import java.util.Optional;
import java.util.Objects;

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

    /**
     * Writes a new revision even when the serialized player state is unchanged.
     * Transfer revisions are monotonic ownership markers, not just content versions.
     */
    public long checkpointForTransfer(
            PlayerStateBundle bundle, long capturedAtEpochMillis
    ) throws IOException {
        Objects.requireNonNull(bundle, "bundle");
        if (capturedAtEpochMillis < 0) throw new IllegalArgumentException("capturedAtEpochMillis < 0");
        String xuid = bundle.profile().xuid();
        synchronized (lockFor(xuid)) {
            byte[] payload = codec.encode(bundle);
            for (int attempt = 0; attempt < 32; attempt++) {
                long revision = store.loadLatest(xuid)
                        .map(PlayerStateSnapshot::revision).orElse(-1L);
                if (revision == Long.MAX_VALUE) throw new IOException("Player snapshot revision exhausted");
                PlayerStateSnapshot snapshot = new PlayerStateSnapshot(
                        PlayerStateBundleCodec.SCHEMA_VERSION, xuid, revision + 1,
                        capturedAtEpochMillis, payload);
                PlayerStateStore.SaveResult result = store.save(snapshot);
                if (result == PlayerStateStore.SaveResult.SAVED
                        || result == PlayerStateStore.SaveResult.ALREADY_CURRENT) {
                    return snapshot.revision();
                }
            }
            throw new IOException("Transfer checkpoint CAS contention exceeded retry limit");
        }
    }

    /** Returns the latest durable revision, or -1 when no canonical checkpoint exists. */
    public long latestRevision(String xuid) throws IOException {
        if (xuid == null || !xuid.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid player XUID");
        }
        synchronized (lockFor(xuid)) {
            return store.loadLatest(xuid).map(PlayerStateSnapshot::revision).orElse(-1L);
        }
    }

    /** Loads the exact durable revision requested by a transfer claim. */
    public Optional<LoadedCheckpoint> loadAtRevision(String xuid, long expectedRevision) throws IOException {
        if (xuid == null || !xuid.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid player XUID");
        }
        if (expectedRevision < 0) throw new IllegalArgumentException("expectedRevision < 0");
        synchronized (lockFor(xuid)) {
            Optional<PlayerStateSnapshot> stored = store.loadLatest(xuid);
            if (stored.isEmpty()) return Optional.empty();
            PlayerStateSnapshot snapshot = stored.orElseThrow();
            if (snapshot.revision() != expectedRevision) return Optional.empty();
            if (snapshot.schemaVersion() != PlayerStateBundleCodec.SCHEMA_VERSION
                    || !snapshot.hasValidChecksum()) {
                throw new IOException("Canonical player checkpoint failed schema/checksum validation");
            }
            PlayerStateBundle bundle = codec.decode(snapshot.payload());
            if (!bundle.profile().xuid().equals(xuid)) {
                throw new IOException("Player state XUID does not match snapshot key");
            }
            return Optional.of(new LoadedCheckpoint(snapshot.revision(), bundle));
        }
    }

    public record LoadedCheckpoint(long revision, PlayerStateBundle bundle) {
        public LoadedCheckpoint {
            if (revision < 0) throw new IllegalArgumentException("revision < 0");
            Objects.requireNonNull(bundle, "bundle");
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
