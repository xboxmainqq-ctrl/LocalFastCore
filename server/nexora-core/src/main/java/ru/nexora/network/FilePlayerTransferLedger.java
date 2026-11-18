package ru.nexora.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable local-filesystem transfer ledger with per-transfer CAS and process locks.
 * This adapter is suitable for one host/local filesystem only; it is not a
 * cross-host consensus service or a guarantee of exactly-once gameplay delivery.
 */
public final class FilePlayerTransferLedger implements PlayerTransferLedger {
    private static final int MAGIC = 0x4e58544c; // NXTL
    private static final int FORMAT_VERSION = 1;
    private static final int CHECKSUM_BYTES = 32;
    private static final int MAX_FILE_BYTES = 1024;
    private static final Object[] LOCKS = createLocks();

    private final Path directory;

    public FilePlayerTransferLedger(Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Path root = directory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        rejectSymlink(root);
        this.directory = root;
    }

    @Override
    public Optional<PlayerTransferRecord> get(UUID transferId) throws IOException {
        Objects.requireNonNull(transferId, "transferId");
        synchronized (lockFor(transferId)) {
            return withFileLock(transferId, () -> read(recordPath(transferId), transferId));
        }
    }

    @Override
    public boolean create(PlayerTransferRecord prepared) throws IOException {
        Objects.requireNonNull(prepared, "prepared");
        if (prepared.state() != PlayerTransferRecord.State.PREPARED
                || prepared.version() != 0 || prepared.fencingToken() != 0) {
            throw new IllegalArgumentException("Initial transfer record must be PREPARED at version zero");
        }
        synchronized (lockFor(prepared.transferId())) {
            return withFileLock(prepared.transferId(), () -> {
                Path target = recordPath(prepared.transferId());
                rejectSymlink(target);
                if (Files.exists(target)) return false;
                writeAtomically(target, encode(prepared));
                return true;
            });
        }
    }

    @Override
    public boolean compareAndSet(UUID transferId, long expectedVersion,
                                 PlayerTransferRecord replacement) throws IOException {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(replacement, "replacement");
        if (!transferId.equals(replacement.transferId()) || expectedVersion < 0
                || replacement.version() != expectedVersion + 1) {
            throw new IllegalArgumentException("Invalid transfer CAS replacement");
        }
        synchronized (lockFor(transferId)) {
            return withFileLock(transferId, () -> {
                Path target = recordPath(transferId);
                Optional<PlayerTransferRecord> stored = read(target, transferId);
                if (stored.isEmpty() || stored.orElseThrow().version() != expectedVersion
                        || !replacement.isLegalSuccessorOf(stored.orElseThrow())) return false;
                writeAtomically(target, encode(replacement));
                return true;
            });
        }
    }

    @Override
    public List<PlayerTransferRecord> listRecoverableForTarget(String targetNode, int limit) throws IOException {
        if (targetNode == null || !targetNode.matches("[A-Za-z0-9._-]{1,64}"))
            throw new IllegalArgumentException("Invalid target node");
        if (limit < 1 || limit > 10_000)
            throw new IllegalArgumentException("Recovery query limit must be 1..10000");

        List<PlayerTransferRecord> matches = new ArrayList<>();
        int scanned = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.nxtl")) {
            for (Path file : files) {
                if (++scanned > 100_000)
                    throw new IOException("Transfer ledger recovery scan exceeds 100000 records");
                String name = file.getFileName().toString();
                String idText = name.substring(0, name.length() - ".nxtl".length());
                final UUID id;
                try {
                    id = UUID.fromString(idText);
                } catch (IllegalArgumentException malformed) {
                    throw new IOException("Invalid transfer ledger filename: " + name, malformed);
                }
                Optional<PlayerTransferRecord> stored = get(id);
                if (stored.isEmpty()) throw new IOException("Transfer ledger record disappeared during recovery scan: " + id);
                PlayerTransferRecord record = stored.orElseThrow();
                if (record.targetNode().equals(targetNode)
                        && (record.state() == PlayerTransferRecord.State.CLAIMED
                        || record.state() == PlayerTransferRecord.State.COMMITTED)) {
                    matches.add(record);
                }
            }
        }
        matches.sort(Comparator.comparingLong(PlayerTransferRecord::updatedAtEpochMillis)
                .thenComparing(record -> record.transferId().toString()));
        return List.copyOf(matches.subList(0, Math.min(limit, matches.size())));
    }

    private <T> T withFileLock(UUID id, IoSupplier<T> action) throws IOException {
        Path lockPath = directory.resolve(id + ".lock");
        rejectSymlink(lockPath);
        try (FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            return action.get();
        }
    }

    private Optional<PlayerTransferRecord> read(Path file, UUID expectedId) throws IOException {
        rejectSymlink(file);
        if (!Files.exists(file)) return Optional.empty();
        long size = Files.size(file);
        if (size < 4 + 1 + 16 + 2 + 2 + 2 + 8 * 5 + 1 + CHECKSUM_BYTES
                || size > MAX_FILE_BYTES) {
            throw new IOException("Invalid transfer ledger file size: " + size);
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length != size) throw new IOException("Transfer ledger changed while reading");
        byte[] body = Arrays.copyOf(bytes, bytes.length - CHECKSUM_BYTES);
        byte[] checksum = Arrays.copyOfRange(bytes, bytes.length - CHECKSUM_BYTES, bytes.length);
        if (!MessageDigest.isEqual(checksum, sha256(body))) throw new IOException("Transfer ledger checksum mismatch");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
            if (input.readInt() != MAGIC) throw new IOException("Invalid transfer ledger magic");
            if (input.readUnsignedByte() != FORMAT_VERSION) throw new IOException("Unsupported transfer ledger version");
            UUID id = new UUID(input.readLong(), input.readLong());
            String xuid = readString(input, 64);
            String source = readString(input, 64);
            String target = readString(input, 64);
            long playerRevision = input.readLong();
            int stateOrdinal = input.readUnsignedByte();
            long fence = input.readLong();
            long lease = input.readLong();
            long version = input.readLong();
            long updated = input.readLong();
            if (input.available() != 0) throw new IOException("Trailing transfer ledger data");
            if (!id.equals(expectedId)) throw new IOException("Transfer ID does not match ledger filename");
            if (stateOrdinal >= PlayerTransferRecord.State.values().length)
                throw new IOException("Invalid transfer state");
            try {
                return Optional.of(new PlayerTransferRecord(id, xuid, source, target, playerRevision,
                        PlayerTransferRecord.State.values()[stateOrdinal], fence, lease, version, updated));
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid transfer ledger record", invalid);
            }
        } catch (EOFException truncated) {
            throw new IOException("Truncated transfer ledger record", truncated);
        }
    }

    private byte[] encode(PlayerTransferRecord record) throws IOException {
        ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream(160);
        try (DataOutputStream output = new DataOutputStream(bodyBytes)) {
            output.writeInt(MAGIC);
            output.writeByte(FORMAT_VERSION);
            output.writeLong(record.transferId().getMostSignificantBits());
            output.writeLong(record.transferId().getLeastSignificantBits());
            writeString(output, record.playerXuid(), 64);
            writeString(output, record.sourceNode(), 64);
            writeString(output, record.targetNode(), 64);
            output.writeLong(record.playerRevision());
            output.writeByte(record.state().ordinal());
            output.writeLong(record.fencingToken());
            output.writeLong(record.leaseExpiresAtEpochMillis());
            output.writeLong(record.version());
            output.writeLong(record.updatedAtEpochMillis());
        }
        byte[] body = bodyBytes.toByteArray();
        ByteArrayOutputStream file = new ByteArrayOutputStream(body.length + CHECKSUM_BYTES);
        file.write(body);
        file.write(sha256(body));
        return file.toByteArray();
    }

    private void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(directory, ".nexora-transfer-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path recordPath(UUID id) { return directory.resolve(id + ".nxtl"); }

    private static void writeString(DataOutputStream output, String value, int maxBytes) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 1 || bytes.length > maxBytes) throw new IOException("Transfer ledger string too long");
        output.writeByte(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input, int maxBytes) throws IOException {
        int length = input.readUnsignedByte();
        if (length < 1 || length > maxBytes) throw new IOException("Invalid transfer ledger string length");
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new IOException("Malformed transfer ledger UTF-8", invalid);
        }
    }

    private static byte[] sha256(byte[] value) throws IOException {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }

    private static Object[] createLocks() {
        Object[] locks = new Object[256];
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
        return locks;
    }

    private static Object lockFor(UUID id) {
        return LOCKS[Math.floorMod(id.hashCode(), LOCKS.length)];
    }

    private static void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Transfer ledger path must not be a symbolic link: " + path);
    }

    @FunctionalInterface
    private interface IoSupplier<T> { T get() throws IOException; }
}
