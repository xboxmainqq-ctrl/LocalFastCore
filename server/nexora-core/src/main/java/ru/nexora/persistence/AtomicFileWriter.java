package ru.nexora.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Crash-conscious replacement for small durable server state files.
 *
 * <p>The temporary file is fully written and forced before replacement. After
 * the rename, the parent directory is forced where the filesystem exposes
 * directory channels. Some platforms (notably Windows providers) do not allow
 * opening directories as channels; those platforms still get a forced file
 * before replacement and report directory sync as unavailable in the result.</p>
 */
public final class AtomicFileWriter {
    private AtomicFileWriter() {
    }

    public static WriteResult writeAtomically(Path target, byte[] contents) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(contents, "contents");
        Path absoluteTarget = target.toAbsolutePath();
        Path parent = absoluteTarget.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IOException("Atomic file target parent directory does not exist: " + parent);
        }

        Path temporary = Files.createTempFile(parent, ".nexora-durable-", ".tmp");
        boolean replaced = false;
        boolean atomicMove = true;
        try {
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(contents);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }

            try {
                Files.move(temporary, absoluteTarget,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                atomicMove = false;
                Files.move(temporary, absoluteTarget, StandardCopyOption.REPLACE_EXISTING);
            }
            replaced = true;
            boolean directoryForced = forceDirectory(parent);
            return new WriteResult(true, directoryForced, atomicMove);
        } finally {
            if (!replaced) Files.deleteIfExists(temporary);
        }
    }

    private static boolean forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
            return true;
        } catch (UnsupportedOperationException | AccessDeniedException unsupported) {
            return false;
        }
    }

    public record WriteResult(
            boolean fileContentsForced,
            boolean directoryEntryForced,
            boolean atomicMoveUsed) {
    }
}
