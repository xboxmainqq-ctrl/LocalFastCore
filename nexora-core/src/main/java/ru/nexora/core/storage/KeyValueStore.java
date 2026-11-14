package ru.nexora.core.storage;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * Small persistent namespaced store for core metadata and module settings.
 * This is not intended for high-volume player data or concurrent database workloads.
 */
public final class KeyValueStore implements AutoCloseable {
    private final Path file;
    private final Properties values = new Properties();
    private boolean closed;

    public KeyValueStore(Path file) throws IOException {
        this.file = Objects.requireNonNull(file, "file");
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                values.load(reader);
            }
        }
    }

    public synchronized Optional<String> get(String namespace, String key) {
        ensureOpen();
        return Optional.ofNullable(values.getProperty(composeKey(namespace, key)));
    }

    public synchronized void put(String namespace, String key, String value) {
        ensureOpen();
        values.setProperty(composeKey(namespace, key), Objects.requireNonNull(value, "value"));
    }

    public synchronized boolean remove(String namespace, String key) {
        ensureOpen();
        return values.remove(composeKey(namespace, key)) != null;
    }

    public synchronized void save() throws IOException {
        ensureOpen();
        Path absoluteFile = file.toAbsolutePath();
        Path parent = absoluteFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = absoluteFile.resolveSibling(absoluteFile.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            values.store(writer, "NEXORA Core persistent data");
        }
        try {
            Files.move(temporary, absoluteFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, absoluteFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public Path getFile() {
        return file;
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            save();
            closed = true;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("KeyValueStore is closed");
        }
    }

    private static String composeKey(String namespace, String key) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(key, "key");
        if (namespace.isBlank() || namespace.indexOf(':') >= 0) {
            throw new IllegalArgumentException("Namespace must be non-empty and must not contain ':'");
        }
        if (key.isBlank()) {
            throw new IllegalArgumentException("Key must not be blank");
        }
        return namespace + ":" + key;
    }
}
