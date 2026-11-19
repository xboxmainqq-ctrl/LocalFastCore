package ru.nexora.persistence;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Durable local-disk checkpoint store.
 *
 * Writes are serialized per player across threads and cooperating processes,
 * written to a forced temporary file, then atomically replaced where supported.
 * Use one configured data directory per server process; this is not a
 * distributed/replicated database and does not coordinate remote hosts.
 */
public final class FilePlayerStateStore implements PlayerStateStore {
    private static final int MAGIC = 0x4e585053; // NXPS
    private static final int FORMAT_VERSION = 2;
    private static final int CHECKSUM_BYTES = 32;
    private static final int MAX_PAYLOAD_BYTES = 4 * 1024 * 1024;
    private static final int MAX_FILE_BYTES = 4 * 1024 * 1024 + 128;

    private final Path directory;
    private static final Object[] PLAYER_LOCKS = createLocks();

    public FilePlayerStateStore(Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Path root = directory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        rejectSymlink(root);
        this.directory = root;
    }

    @Override
    public SaveResult save(PlayerStateSnapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.hasValidChecksum()) throw new IllegalArgumentException("Invalid snapshot checksum");
        byte[] payload = snapshot.payload();
        if (payload.length > MAX_PAYLOAD_BYTES) throw new IOException("Player snapshot exceeds storage limit");

        synchronized (lockFor(snapshot.playerId())) {
            Path lockPath = lockPath(snapshot.playerId());
            rejectSymlink(lockPath);
            try (FileChannel lockChannel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = lockChannel.lock()) {
                Path target = snapshotPath(snapshot.playerId());
                rejectSymlink(target);
                Optional<PlayerStateSnapshot> current = read(target, snapshot.playerId());
                if (current.isPresent()) {
                    PlayerStateSnapshot stored = current.orElseThrow();
                    if (snapshot.revision() < stored.revision()) return SaveResult.STALE;
                    if (snapshot.revision() == stored.revision()) {
                        if (snapshot.schemaVersion() == stored.schemaVersion()
                                && snapshot.checksum().equals(stored.checksum())) {
                            return SaveResult.ALREADY_CURRENT;
                        }
                        throw new IllegalStateException("Conflicting player snapshot revision "
                                + snapshot.revision() + " for player " + snapshot.playerId());
                    }
                }
                writeAtomically(target, encode(snapshot));
                return SaveResult.SAVED;
            }
        }
    }

    @Override
    public Optional<PlayerStateSnapshot> loadLatest(String playerId) throws IOException {
        validatePlayerId(playerId);
        synchronized (lockFor(playerId)) {
            Path lockPath = lockPath(playerId);
            rejectSymlink(lockPath);
            try (FileChannel lockChannel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = lockChannel.lock()) {
                Path target = snapshotPath(playerId);
                rejectSymlink(target);
                return read(target, playerId);
            }
        }
    }

    private byte[] encode(PlayerStateSnapshot snapshot) throws IOException {
        byte[] payload = snapshot.payload();
        byte[] checksum = decodeHex(snapshot.checksum());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(payload.length + 64);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeByte(FORMAT_VERSION);
            output.writeInt(snapshot.schemaVersion());
            byte[] playerId = snapshot.playerId().getBytes(StandardCharsets.US_ASCII);
            output.writeByte(playerId.length);
            output.write(playerId);
            output.writeLong(snapshot.revision());
            output.writeLong(snapshot.capturedAtEpochMillis());
            output.writeInt(payload.length);
            output.write(payload);
            output.write(checksum);
        }
        return bytes.toByteArray();
    }

    private Optional<PlayerStateSnapshot> read(Path file, String expectedPlayerId) throws IOException {
        if (!Files.exists(file)) return Optional.empty();
        long size = Files.size(file);
        if (size < 4 + 1 + 4 + 1 + 1 + 8 + 8 + 4 + CHECKSUM_BYTES || size > MAX_FILE_BYTES) {
            throw new IOException("Invalid NEXORA player snapshot file size: " + size);
        }
        byte[] bytes = Files.readAllBytes(file);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC) throw new IOException("Invalid NEXORA player snapshot magic");
            int format = input.readUnsignedByte();
            if (format != 1 && format != FORMAT_VERSION) throw new IOException("Unsupported player snapshot format: " + format);
            int schemaVersion = input.readInt();
            String playerId;
            if (format == 1) {
                long legacyId = input.readLong();
                if (legacyId < 0) throw new IOException("Invalid legacy player snapshot ID");
                playerId = Long.toString(legacyId);
            } else {
                int idLength = input.readUnsignedByte();
                if (idLength < 1 || idLength > 64) throw new IOException("Invalid player XUID length");
                byte[] idBytes = new byte[idLength];
                input.readFully(idBytes);
                playerId = new String(idBytes, StandardCharsets.US_ASCII);
                try { validatePlayerId(playerId); }
                catch (IllegalArgumentException invalid) { throw new IOException("Invalid player XUID", invalid); }
            }
            long revision = input.readLong();
            long capturedAt = input.readLong();
            int payloadLength = input.readInt();
            if (!playerId.equals(expectedPlayerId)) throw new IOException("Player snapshot ID does not match file name");
            if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_BYTES
                    || payloadLength != input.available() - CHECKSUM_BYTES) {
                throw new IOException("Invalid NEXORA player snapshot payload length");
            }
            byte[] payload = new byte[payloadLength];
            input.readFully(payload);
            byte[] storedChecksum = new byte[CHECKSUM_BYTES];
            input.readFully(storedChecksum);
            if (input.available() != 0) throw new IOException("Trailing data in player snapshot");
            PlayerStateSnapshot snapshot;
            try {
                snapshot = new PlayerStateSnapshot(schemaVersion, playerId, revision, capturedAt, payload);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid NEXORA player snapshot metadata", exception);
            }
            if (!java.security.MessageDigest.isEqual(storedChecksum, decodeHex(snapshot.checksum()))) {
                throw new IOException("NEXORA player snapshot checksum mismatch: " + file);
            }
            return Optional.of(snapshot);
        } catch (EOFException exception) {
            throw new IOException("Truncated NEXORA player snapshot: " + file, exception);
        }
    }

    private void writeAtomically(Path target, byte[] bytes) throws IOException {
        AtomicFileWriter.writeAtomically(target, bytes);
    }

    private static Object[] createLocks() {
        Object[] locks = new Object[256];
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
        return locks;
    }

    private static Object lockFor(String playerId) {
        return PLAYER_LOCKS[Math.floorMod(playerId.hashCode(), PLAYER_LOCKS.length)];
    }

    private Path snapshotPath(String playerId) {
        validatePlayerId(playerId);
        return directory.resolve(playerId + ".nxps");
    }

    private Path lockPath(String playerId) {
        validatePlayerId(playerId);
        return directory.resolve(playerId + ".lock");
    }

    private static void validatePlayerId(String playerId) {
        if (playerId == null || !playerId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid player XUID");
        }
    }

    private static byte[] decodeHex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int high = Character.digit(value.charAt(i * 2), 16);
            int low = Character.digit(value.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) throw new IllegalArgumentException("Invalid checksum encoding");
            result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }

    private static void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("NEXORA player snapshot path must not be a symbolic link: " + path);
        }
    }
}
