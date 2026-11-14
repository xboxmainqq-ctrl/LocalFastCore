package ru.nexora.core.backup;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Creates and verifies explicit, non-destructive snapshots of a selected directory.
 *
 * This utility does not stop a server or guarantee an application-consistent
 * snapshot of files that are being actively written. Callers must arrange a
 * safe source (for example, a quiesced data directory) before invoking it.
 */
public final class BackupManager {
    private static final String MANIFEST_NAME = "NEXORA-BACKUP-MANIFEST.tsv";
    private static final String MANIFEST_HEADER = "NEXORA-BACKUP-MANIFEST\t1";
    private static final int BUFFER_SIZE = 8192;

    /**
     * Copies regular files from sourceDirectory into a new backup directory.
     * Existing backups are never overwritten. Symbolic links and special files
     * are rejected rather than followed.
     */
    public BackupResult createBackup(Path sourceDirectory, Path backupRoot, String backupId)
            throws IOException {
        Objects.requireNonNull(sourceDirectory, "sourceDirectory");
        Objects.requireNonNull(backupRoot, "backupRoot");
        validateBackupId(backupId);

        if (Files.isSymbolicLink(sourceDirectory)) {
            throw new IOException("Backup source must not be a symbolic link");
        }
        Path source = sourceDirectory.toRealPath();
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Backup source must be a real directory");
        }

        Files.createDirectories(backupRoot);
        if (Files.isSymbolicLink(backupRoot)) {
            throw new IOException("Backup root must not be a symbolic link");
        }
        Path root = backupRoot.toRealPath();
        if (root.startsWith(source) || source.startsWith(root)) {
            throw new IOException("Backup source and backup root must not contain one another");
        }

        Path target = root.resolve(backupId);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Backup already exists; refusing to overwrite: " + target);
        }
        Path staging = root.resolve("." + backupId + ".staging-" + UUID.randomUUID());
        Files.createDirectory(staging);

        try {
            List<ManifestEntry> entries = new ArrayList<>();
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs)
                        throws IOException {
                    if (attrs.isSymbolicLink() || !attrs.isDirectory()) {
                        throw new IOException("Unsupported directory entry: " + directory);
                    }
                    Path relative = source.relativize(directory);
                    if (!relative.toString().isEmpty()) {
                        Files.createDirectory(staging.resolve(relative));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isSymbolicLink() || !attrs.isRegularFile()) {
                        throw new IOException("Backup accepts regular files only: " + file);
                    }
                    Path relative = source.relativize(file);
                    Path destination = staging.resolve(relative).normalize();
                    if (!destination.startsWith(staging)) {
                        throw new IOException("Invalid relative path in backup source");
                    }

                    BasicFileAttributes before = Files.readAttributes(
                            file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    MessageDigest digest = newSha256();
                    long copied = 0;
                    byte[] buffer = new byte[BUFFER_SIZE];
                    try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
                         OutputStream output = Files.newOutputStream(destination)) {
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            output.write(buffer, 0, read);
                            digest.update(buffer, 0, read);
                            copied += read;
                        }
                    }

                    BasicFileAttributes after = Files.readAttributes(
                            file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!after.isRegularFile()
                            || before.size() != after.size()
                            || before.lastModifiedTime().compareTo(after.lastModifiedTime()) != 0
                            || copied != after.size()
                            || (before.fileKey() != null && !before.fileKey().equals(after.fileKey()))) {
                        throw new IOException("Source changed while backup was being created: " + file);
                    }

                    entries.add(new ManifestEntry(relative, copied,
                            HexFormat.of().formatHex(digest.digest())));
                    return FileVisitResult.CONTINUE;
                }
            });

            Path manifest = staging.resolve(MANIFEST_NAME);
            try (BufferedWriter writer = Files.newBufferedWriter(manifest, StandardCharsets.UTF_8)) {
                writer.write(MANIFEST_HEADER);
                writer.newLine();
                Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
                for (ManifestEntry entry : entries) {
                    String encodedPath = encoder.encodeToString(
                            entry.relativePath().toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                    writer.write(encodedPath);
                    writer.write('\t');
                    writer.write(Long.toString(entry.size()));
                    writer.write('\t');
                    writer.write(entry.sha256());
                    writer.newLine();
                }
            }

            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(staging, target);
            }

            long totalBytes = entries.stream().mapToLong(ManifestEntry::size).sum();
            return new BackupResult(target, entries.size(), totalBytes, target.resolve(MANIFEST_NAME));
        } catch (IOException | RuntimeException failure) {
            try {
                deleteTreeWithoutFollowingLinks(staging);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /**
     * Verifies manifest structure, exact file set, file sizes and SHA-256 hashes.
     * Does not modify the backup and rejects symlinks or special filesystem entries.
     */
    public VerificationResult verifyBackup(Path backupDirectory) throws IOException {
        Objects.requireNonNull(backupDirectory, "backupDirectory");
        if (Files.isSymbolicLink(backupDirectory)) {
            throw new IOException("Backup directory must not be a symbolic link");
        }
        Path root = backupDirectory.toRealPath();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Backup path must be a directory");
        }

        Path manifest = root.resolve(MANIFEST_NAME);
        if (Files.isSymbolicLink(manifest)
                || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Backup manifest is missing or is not a regular file");
        }

        Map<Path, ManifestEntry> expected = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            if (!MANIFEST_HEADER.equals(reader.readLine())) {
                throw new IOException("Unsupported or invalid backup manifest header");
            }
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String[] fields = line.split("\\t", -1);
                if (fields.length != 3) {
                    throw new IOException("Malformed backup manifest line " + lineNumber);
                }
                final String decoded;
                final long size;
                try {
                    decoded = new String(Base64.getUrlDecoder().decode(fields[0]), StandardCharsets.UTF_8);
                    size = Long.parseLong(fields[1]);
                } catch (IllegalArgumentException malformed) {
                    throw new IOException("Invalid backup manifest value on line " + lineNumber, malformed);
                }
                Path relative = Path.of(decoded);
                Path normalized = relative.normalize();
                if (decoded.isBlank() || relative.isAbsolute() || !normalized.equals(relative)
                        || normalized.startsWith("..") || normalized.toString().isBlank()
                        || normalized.equals(Path.of(MANIFEST_NAME))
                        || size < 0 || !fields[2].matches("[0-9a-f]{64}")) {
                    throw new IOException("Unsafe backup manifest entry on line " + lineNumber);
                }
                if (expected.putIfAbsent(normalized,
                        new ManifestEntry(normalized, size, fields[2])) != null) {
                    throw new IOException("Duplicate backup manifest path: " + normalized);
                }
            }
        }

        Set<Path> actualFiles = new HashSet<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs)
                        throws IOException {
                    if (attrs.isSymbolicLink() || !attrs.isDirectory()) {
                        throw new IOException("Unsupported backup directory entry: " + directory);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isSymbolicLink() || !attrs.isRegularFile()) {
                        throw new IOException("Unsupported backup file entry: " + file);
                    }
                    if (!file.equals(manifest)) {
                        actualFiles.add(root.relativize(file));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException failure) {
            throw failure;
        }

        if (!actualFiles.equals(expected.keySet())) {
            Set<Path> missing = new HashSet<>(expected.keySet());
            missing.removeAll(actualFiles);
            Set<Path> unexpected = new HashSet<>(actualFiles);
            unexpected.removeAll(expected.keySet());
            throw new IOException("Backup file set mismatch; missing=" + missing + ", unexpected=" + unexpected);
        }

        long totalBytes = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        for (ManifestEntry entry : expected.values()) {
            Path file = root.resolve(entry.relativePath()).normalize();
            if (!file.startsWith(root) || Files.isSymbolicLink(file)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Invalid backup file: " + entry.relativePath());
            }
            MessageDigest digest = newSha256();
            long size = 0;
            try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                    size += read;
                }
            }
            String actualHash = HexFormat.of().formatHex(digest.digest());
            if (size != entry.size() || !actualHash.equals(entry.sha256())) {
                throw new IOException("Backup integrity check failed: " + entry.relativePath());
            }
            totalBytes = Math.addExact(totalBytes, size);
        }
        return new VerificationResult(root, expected.size(), totalBytes);
    }

    private static void deleteTreeWithoutFollowingLinks(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void validateBackupId(String backupId) {
        Objects.requireNonNull(backupId, "backupId");
        if (!backupId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException(
                    "Backup ID must be 1-64 safe filename characters and start with a letter or digit");
        }
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record ManifestEntry(Path relativePath, long size, String sha256) { }

    public record BackupResult(Path backupDirectory, int fileCount, long totalBytes, Path manifest) {
        public BackupResult {
            backupDirectory = Objects.requireNonNull(backupDirectory, "backupDirectory")
                    .toAbsolutePath().normalize();
            manifest = Objects.requireNonNull(manifest, "manifest").toAbsolutePath().normalize();
            if (fileCount < 0 || totalBytes < 0) {
                throw new IllegalArgumentException("Backup counters cannot be negative");
            }
        }
    }

    public record VerificationResult(Path backupDirectory, int fileCount, long totalBytes) {
        public VerificationResult {
            backupDirectory = Objects.requireNonNull(backupDirectory, "backupDirectory")
                    .toAbsolutePath().normalize();
            if (fileCount < 0 || totalBytes < 0) {
                throw new IllegalArgumentException("Verification counters cannot be negative");
            }
        }
    }
}
