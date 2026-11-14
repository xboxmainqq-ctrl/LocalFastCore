package ru.nexora.core.backup;

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
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Creates explicit, non-destructive snapshots of a selected directory.
 *
 * This utility does not stop a server or guarantee an application-consistent
 * snapshot of files that are being actively written. Callers must arrange a
 * safe source (for example, a quiesced data directory) before invoking it.
 */
public final class BackupManager {
    private static final String MANIFEST_NAME = "NEXORA-BACKUP-MANIFEST.tsv";
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

                entries.add(new ManifestEntry(relative, copied, HexFormat.of().formatHex(digest.digest())));
                return FileVisitResult.CONTINUE;
            }
        });

        Path manifest = staging.resolve(MANIFEST_NAME);
        try (BufferedWriter writer = Files.newBufferedWriter(manifest, StandardCharsets.UTF_8)) {
            writer.write("NEXORA-BACKUP-MANIFEST\t1");
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
}
