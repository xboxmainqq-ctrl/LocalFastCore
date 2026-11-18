package ru.nexora.network;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

/**
 * Single-host durable replay filter. Message IDs remain rejected across process
 * restarts until their retention expires. A global OS file lock serializes
 * capacity checks and creates across processes sharing this directory.
 */
public final class FileClusterReplayFilter implements ClusterReplayFilter {
    private static final int MAGIC = 0x4e585244; // NXRD
    private static final int RECORD_BYTES = 4 + 8 + 32;
    private static final Object JVM_LOCK = new Object();

    private final Path directory;
    private final Path lockPath;
    private final int maximumEntries;
    private final long retentionMillis;

    public FileClusterReplayFilter(Path directory, int maximumEntries, long retentionMillis) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        if (maximumEntries < 1) throw new IllegalArgumentException("maximumEntries must be positive");
        if (retentionMillis < 1) throw new IllegalArgumentException("retentionMillis must be positive");
        this.maximumEntries = maximumEntries;
        this.retentionMillis = retentionMillis;
        this.lockPath = this.directory.resolve("cluster-replay.lock");
    }

    @Override
    public boolean accept(UUID messageId, long nowEpochMillis) throws IOException {
        Objects.requireNonNull(messageId, "messageId");
        if (nowEpochMillis < 0) throw new IllegalArgumentException("Negative current time");
        long expiresAt;
        try { expiresAt = Math.addExact(nowEpochMillis, retentionMillis); }
        catch (ArithmeticException overflow) { expiresAt = Long.MAX_VALUE; }

        synchronized (JVM_LOCK) {
            Files.createDirectories(directory);
            rejectSymlink(directory);
            rejectSymlink(lockPath);
            try (FileChannel channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                Path marker = markerPath(messageId);
                rejectSymlink(marker);
                if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                    long existingExpiry = readExpiry(marker);
                    if (existingExpiry > nowEpochMillis) return false;
                    Files.delete(marker);
                }
                purgeExpired(nowEpochMillis);
                if (countMarkers() >= maximumEntries) return false;

                byte[] record = encode(expiresAt);
                try (FileChannel output = FileChannel.open(marker,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    ByteBuffer buffer = ByteBuffer.wrap(record);
                    while (buffer.hasRemaining()) output.write(buffer);
                    output.force(true);
                    return true;
                } catch (java.nio.file.FileAlreadyExistsException race) {
                    return false;
                }
            }
        }
    }

    private void purgeExpired(long now) throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.nxrd")) {
            for (Path file : files) {
                rejectSymlink(file);
                if (readExpiry(file) <= now) Files.deleteIfExists(file);
            }
        }
    }

    private int countMarkers() throws IOException {
        int count = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.nxrd")) {
            for (Path file : files) {
                rejectSymlink(file);
                count++;
            }
        }
        return count;
    }

    private Path markerPath(UUID id) { return directory.resolve(id.toString() + ".nxrd"); }

    private static byte[] encode(long expiresAt) throws IOException {
        ByteBuffer body = ByteBuffer.allocate(12).putInt(MAGIC).putLong(expiresAt);
        byte[] bodyBytes = body.array();
        byte[] checksum = sha256(bodyBytes);
        ByteBuffer record = ByteBuffer.allocate(RECORD_BYTES).put(bodyBytes).put(checksum);
        return record.array();
    }

    private static long readExpiry(Path path) throws IOException {
        rejectSymlink(path);
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length != RECORD_BYTES) throw new IOException("Invalid cluster replay marker length");
        byte[] body = java.util.Arrays.copyOf(bytes, 12);
        byte[] checksum = java.util.Arrays.copyOfRange(bytes, 12, RECORD_BYTES);
        if (!MessageDigest.isEqual(checksum, sha256(body)))
            throw new IOException("Cluster replay marker checksum mismatch");
        ByteBuffer input = ByteBuffer.wrap(body);
        if (input.getInt() != MAGIC) throw new IOException("Invalid cluster replay marker magic");
        long expiry = input.getLong();
        if (expiry < 0) throw new IOException("Invalid cluster replay marker expiry");
        return expiry;
    }

    private static byte[] sha256(byte[] bytes) throws IOException {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new IOException("SHA-256 unavailable", impossible); }
    }

    private static void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Symbolic links are not allowed: " + path);
    }
}
