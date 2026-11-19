package ru.nexora.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Keeps one previous, fully decoded generation of a durable state file.
 * A backup is rotated only after the current primary passes its real decoder.
 */
public final class BackupFile {
    private BackupFile() {
    }

    public static <T> void write(Path primary, byte[] contents, FileReader<T> reader) throws IOException {
        Objects.requireNonNull(primary, "primary");
        Objects.requireNonNull(contents, "contents");
        Objects.requireNonNull(reader, "reader");
        Path backup = backupPath(primary);
        rejectSymlink(primary);
        rejectSymlink(backup);
        if (Files.exists(primary)) {
            reader.read(primary);
            AtomicFileWriter.writeAtomically(backup, Files.readAllBytes(primary));
        }
        AtomicFileWriter.writeAtomically(primary, contents);
    }

    public static <T> Optional<T> load(Path primary, FileReader<T> reader) throws IOException {
        Objects.requireNonNull(primary, "primary");
        Objects.requireNonNull(reader, "reader");
        Path backup = backupPath(primary);
        rejectSymlink(primary);
        rejectSymlink(backup);
        if (!Files.exists(primary)) {
            if (!Files.exists(backup)) return Optional.empty();
            T recovered = reader.read(backup);
            AtomicFileWriter.writeAtomically(primary, Files.readAllBytes(backup));
            return Optional.of(recovered);
        }
        try {
            return Optional.of(reader.read(primary));
        } catch (IOException primaryFailure) {
            if (!Files.exists(backup)) throw primaryFailure;
            try {
                T recovered = reader.read(backup);
                AtomicFileWriter.writeAtomically(primary, Files.readAllBytes(backup));
                return Optional.of(recovered);
            } catch (IOException backupFailure) {
                primaryFailure.addSuppressed(backupFailure);
                throw primaryFailure;
            }
        }
    }

    public static void delete(Path primary) throws IOException {
        Path backup = backupPath(primary);
        rejectSymlink(primary);
        rejectSymlink(backup);
        Files.deleteIfExists(primary);
        Files.deleteIfExists(backup);
    }

    public static Path backupPath(Path primary) {
        return primary.resolveSibling(primary.getFileName() + ".bak");
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
