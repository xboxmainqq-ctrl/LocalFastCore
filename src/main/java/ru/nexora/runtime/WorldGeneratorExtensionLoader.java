package ru.nexora.runtime;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.jar.JarFile;

/**
 * Loads trusted generator providers from {@code extensions/generators}.
 *
 * <p>Extension JARs execute inside the server process with its permissions;
 * this loader is not a security sandbox. Only install extensions from trusted
 * authors.</p>
 */
public final class WorldGeneratorExtensionLoader implements AutoCloseable {
    private final List<URLClassLoader> classLoaders = new ArrayList<>();
    private boolean closed;

    private WorldGeneratorExtensionLoader() {
    }

    public static WorldGeneratorExtensionLoader load(Path directory, WorldGeneratorRegistry registry)
            throws IOException {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(registry, "registry");
        WorldGeneratorExtensionLoader result = new WorldGeneratorExtensionLoader();
        try {
            Files.createDirectories(directory);
            List<Path> jars;
            try (var files = Files.list(directory)) {
                jars = files.filter(path -> Files.isRegularFile(path)
                                && path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .toList();
            }
            for (Path jar : jars) {
                result.loadJar(jar, registry);
            }
            return result;
        } catch (IOException | RuntimeException | Error failure) {
            try {
                result.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private void loadJar(Path jar, WorldGeneratorRegistry registry) throws IOException {
        try (JarFile ignored = new JarFile(jar.toFile(), true)) {
            URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()},
                    WorldGeneratorExtension.class.getClassLoader());
            classLoaders.add(loader);
            try {
                ServiceLoader<WorldGeneratorExtension> services =
                        ServiceLoader.load(WorldGeneratorExtension.class, loader);
                for (WorldGeneratorExtension extension : services) {
                    if (extension == null) {
                        throw new IllegalArgumentException("Null generator extension in " + jar);
                    }
                    String id = Objects.requireNonNull(extension.id(),
                            "Generator extension id is null in " + jar);
                    if (id.isBlank()) {
                        throw new IllegalArgumentException("Generator extension id is blank in " + jar);
                    }
                    registry.register(id, () -> Objects.requireNonNull(extension.createGenerator(),
                            "Generator extension '" + id + "' returned null"));
                }
            } catch (ServiceConfigurationError | RuntimeException failure) {
                throw new IOException("Could not load world generator extension from " + jar
                        + ": " + failure.getMessage(), failure);
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        IOException aggregate = null;
        for (int index = classLoaders.size() - 1; index >= 0; index--) {
            try {
                classLoaders.get(index).close();
            } catch (IOException failure) {
                if (aggregate == null) aggregate = new IOException("Could not close generator extension loaders");
                aggregate.addSuppressed(failure);
            }
        }
        if (aggregate == null) closed = true;
        if (aggregate != null) throw aggregate;
    }
}
