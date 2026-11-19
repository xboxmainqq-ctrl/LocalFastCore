package ru.nexora.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
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
 * the rename, the parent directory is forced so the directory entry is also
 * requested to reach stable storage. Directory forcing depends on filesystem
 * and provider support; unsupported providers fail explicitly rather than
 * silently claiming the stronger durability guarantee.</p>
 */
public final class AtomicFileWriter {
    private AtomicFileWriter() {
    }

    public static void writeAtomically(Path target, byte[] contents) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(contents, "contents");
        Path absoluteTarget = target.toAbsolutePath();
        Path parent = absoluteTarget.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IOException("Atomic file target parent directory does not exist: " + parent);
        }

        Path temporary = Files.createTempFile(parent, ".nexora-durable-", ".tmp");
        boolean replaced = false;
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
                Files.move(temporary, absoluteTarget, StandardCopyOption.REPLACE_EXISTING);
            }
            replaced = true;
            forceDirectory(parent);
        } finally {
            if (!replaced) Files.deleteIfExists(temporary);
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (UnsupportedOperationException unsupported) {
            throw new IOException("Filesystem provider does not support durable directory sync: " + directory,
                    unsupported);
        }
    }
}
