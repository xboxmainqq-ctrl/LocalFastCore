package ru.nexora.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Keeps one previous, fully decoded generation of a durable state file.
 * A backup is rotated only after the current primary passes its real decoder.
 *
 * <p>Operations on the same normalized path are serialized. Atomic rename alone
 * does not protect the primary/backup pair from two writers rotating each
 * other's generations concurrently.</p>
 */
public final class BackupFile {
    private static final Object[] PATH_LOCKS = createPathLocks();

    private BackupFile() {
    }

    public static <T> void write(Path primary, byte[] contents, FileReader<T> reader) throws IOException {
        Objects.requireNonNull(primary, "primary");
        Objects.requireNonNull(contents, "contents");
        Objects.requireNonNull(reader, "reader");
        Path normalized = primary.toAbsolutePath().normalize();
        synchronized (lockFor(normalized)) {
            Path backup = backupPath(normalized);
            rejectSymlink(normalized);
            rejectSymlink(backup);
            if (Files.exists(normalized)) {
                reader.read(normalized);
                AtomicFileWriter.writeAtomically(backup, Files.readAllBytes(normalized));
            }
            AtomicFileWriter.writeAtomically(normalized, contents);
        }
    }

    public static <T> Optional<T> load(Path primary, FileReader<T> reader) throws IOException {
        Objects.requireNonNull(primary, "primary");
        Objects.requireNonNull(reader, "reader");
        Path normalized = primary.toAbsolutePath().normalize();
        synchronized (lockFor(normalized)) {
            Path backup = backupPath(normalized);
            rejectSymlink(normalized);
            rejectSymlink(backup);
            if (!Files.exists(normalized)) {
                if (!Files.exists(backup)) return Optional.empty();
                T recovered = reader.read(backup);
                AtomicFileWriter.writeAtomically(normalized, Files.readAllBytes(backup));
                return Optional.of(recovered);
            }
            try {
                return Optional.of(reader.read(normalized));
            } catch (IOException primaryFailure) {
                if (!Files.exists(backup)) throw primaryFailure;
                try {
                    T recovered = reader.read(backup);
                    AtomicFileWriter.writeAtomically(normalized, Files.readAllBytes(backup));
                    return Optional.of(recovered);
                } catch (IOException backupFailure) {
                    primaryFailure.addSuppressed(backupFailure);
                    throw primaryFailure;
                }
            }
        }
    }

    public static void delete(Path primary) throws IOException {
        Objects.requireNonNull(primary, "primary");
        Path normalized = primary.toAbsolutePath().normalize();
        synchronized (lockFor(normalized)) {
            Path backup = backupPath(normalized);
            rejectSymlink(normalized);
            rejectSymlink(backup);
            Files.deleteIfExists(backup);
            Files.deleteIfExists(normalized);
        }
    }

    public static Path backupPath(Path primary) {
        return primary.resolveSibling(primary.getFileName() + ".bak");
    }

    private static Object[] createPathLocks() {
        Object[] locks = new Object[64];
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
        return locks;
    }

    private static Object lockFor(Path path) {
        return PATH_LOCKS[path.hashCode() & (PATH_LOCKS.length - 1)];
    }

    private static void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("Durable state path must not be a symbolic link: " + path);
        }
    }

    @FunctionalInterface
    public interface FileReader<T> {
        T read(Path path) throws IOException;
    }
}
