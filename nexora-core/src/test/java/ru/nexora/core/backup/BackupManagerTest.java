package ru.nexora.core.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class BackupManagerTest {
    @TempDir
    Path tempDir;

    private final BackupManager manager = new BackupManager();

    @Test
    void createsSnapshotAndManifestWithoutChangingSource() throws IOException {
        Path source = Files.createDirectory(tempDir.resolve("server-data"));
        Path nested = Files.createDirectories(source.resolve("world/playerdata"));
        Path sourceFile = Files.writeString(nested.resolve("player.dat"), "player-state");
        Path backupRoot = tempDir.resolve("backups");

        BackupManager.BackupResult result = manager.createBackup(source, backupRoot, "snapshot-001");

        Path copiedFile = result.backupDirectory().resolve("world/playerdata/player.dat");
        assertEquals("player-state", Files.readString(copiedFile));
        assertTrue(Files.isRegularFile(result.manifest()));
        assertEquals(1, result.fileCount());
        assertEquals(Files.size(sourceFile), result.totalBytes());
        assertEquals("player-state", Files.readString(sourceFile));
        assertTrue(Files.readString(result.manifest()).contains("NEXORA-BACKUP-MANIFEST\t1"));

        BackupManager.VerificationResult verified = manager.verifyBackup(result.backupDirectory());
        assertEquals(1, verified.fileCount());
        assertEquals(result.totalBytes(), verified.totalBytes());
    }

    @Test
    void rejectsTamperedFileAndUnexpectedFiles() throws IOException {
        Path source = Files.createDirectory(tempDir.resolve("source"));
        Files.writeString(source.resolve("data.txt"), "original");
        Path backup = manager.createBackup(source, tempDir.resolve("backups"), "tamper").backupDirectory();

        Files.writeString(backup.resolve("data.txt"), "changed");
        assertThrows(IOException.class, () -> manager.verifyBackup(backup));

        Files.writeString(backup.resolve("extra.txt"), "extra");
        assertThrows(IOException.class, () -> manager.verifyBackup(backup));
    }

    @Test
    void rejectsManifestPathTraversalAndDuplicateEntries() throws IOException {
        Path source = Files.createDirectory(tempDir.resolve("source"));
        Files.writeString(source.resolve("safe.txt"), "safe");
        Path backup = manager.createBackup(source, tempDir.resolve("backups"), "manifest").backupDirectory();
        Path manifest = backup.resolve("NEXORA-BACKUP-MANIFEST.tsv");
        String traversal = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("../escape".getBytes(StandardCharsets.UTF_8));
        Files.writeString(manifest, "NEXORA-BACKUP-MANIFEST\t1\n" + traversal + "\t0\t"
                + "0".repeat(64) + "\n");
        assertThrows(IOException.class, () -> manager.verifyBackup(backup));
    }

    @Test
    void refusesToOverwriteAnExistingBackup() throws IOException {
        Path source = Files.createDirectory(tempDir.resolve("source"));
        Files.writeString(source.resolve("data.txt"), "safe");
        Path root = tempDir.resolve("backups");
        manager.createBackup(source, root, "stable");

        assertThrows(IOException.class, () -> manager.createBackup(source, root, "stable"));
        assertEquals("safe", Files.readString(root.resolve("stable/data.txt")));
    }

    @Test
    void rejectsBackupRootInsideSource() throws IOException {
        Path source = Files.createDirectory(tempDir.resolve("source"));
        assertThrows(IOException.class, () ->
                manager.createBackup(source, source.resolve("backups"), "nested"));
    }

    @Test
    void rejectsUnsafeBackupIds() throws IOException {
        Path source = Files.createDirectory(tempDir.resolve("source"));
        assertThrows(IllegalArgumentException.class, () ->
                manager.createBackup(source, tempDir.resolve("backups"), "../escape"));
    }

    @Test
    void rejectsSymbolicLinksWhenSupported() throws IOException {
        Path source = Files.createDirectory(tempDir.resolve("source"));
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "outside");
        try {
            Files.createSymbolicLink(source.resolve("linked.txt"), outside);
        } catch (UnsupportedOperationException | SecurityException | IOException unavailable) {
            return;
        }

        assertThrows(IOException.class, () ->
                manager.createBackup(source, tempDir.resolve("backups"), "with-link"));
        assertFalse(Files.exists(tempDir.resolve("backups/with-link")));
    }
}
