package dev.nexora.platform.api;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Discovers NEXORA modules from JAR files using META-INF/nexora/module.properties.
 *
 * Module code is loaded only from JARs in the explicitly supplied directory.
 * This is not a sandbox: only load module archives from sources you trust.
 */
public final class ModuleJarLoader {
    public static final String DESCRIPTOR_PATH = "META-INF/nexora/module.properties";

    private final String supportedApiVersion;

    public ModuleJarLoader(String supportedApiVersion) {
        this.supportedApiVersion = requireText(supportedApiVersion, "supportedApiVersion");
    }

    /**
     * Loads every top-level .jar in deterministic filename order.
     * The caller must close the returned handle after module shutdown.
     */
    public LoadedModules loadDirectory(Path directory)
            throws IOException, ModuleDiscoveryException {
        Objects.requireNonNull(directory, "directory");
        if (!Files.isDirectory(directory)) {
            throw new ModuleDiscoveryException("Module directory does not exist: " + directory);
        }

        List<Path> jars;
        try (var paths = Files.list(directory)) {
            jars = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }

        List<URLClassLoader> classLoaders = new ArrayList<>();
        List<NexoraModule> modules = new ArrayList<>();
        Set<String> discoveredIds = new HashSet<>();
        try {
            for (Path jar : jars) {
                URLClassLoader classLoader = new URLClassLoader(
                        new URL[]{jar.toUri().toURL()}, NexoraModule.class.getClassLoader());
                classLoaders.add(classLoader);

                Properties properties = readDescriptor(jar);
                ModuleDescriptor declared = descriptor(properties, jar);
                if (!supportedApiVersion.equals(declared.apiVersion())) {
                    throw new ModuleDiscoveryException("Module " + declared.id()
                            + " targets API " + declared.apiVersion()
                            + ", but this host supports " + supportedApiVersion);
                }
                if (!discoveredIds.add(declared.id())) {
                    throw new ModuleDiscoveryException("Duplicate module id in JAR directory: "
                            + declared.id());
                }

                String entrypointName = required(properties, "entrypoint", jar);
                NexoraModule module;
                try {
                    Class<?> entrypoint = Class.forName(entrypointName, true, classLoader);
                    if (!NexoraModule.class.isAssignableFrom(entrypoint)) {
                        throw new ModuleDiscoveryException("Entrypoint does not implement "
                                + NexoraModule.class.getName() + ": " + entrypointName);
                    }
                    module = NexoraModule.class.cast(entrypoint.getConstructor().newInstance());
                } catch (ModuleDiscoveryException exception) {
                    throw exception;
                } catch (ReflectiveOperationException | LinkageError exception) {
                    throw new ModuleDiscoveryException(
                            "Cannot create module entrypoint " + entrypointName + " from " + jar,
                            exception);
                }

                ModuleDescriptor actual = Objects.requireNonNull(
                        module.descriptor(), "Module descriptor returned null: " + declared.id());
                if (!declared.equals(actual)) {
                    throw new ModuleDiscoveryException("JAR descriptor does not match "
                            + "entrypoint descriptor for module " + declared.id());
                }
                modules.add(module);
            }
            return new LoadedModules(modules, classLoaders);
        } catch (IOException | ModuleDiscoveryException | RuntimeException exception) {
            closeAll(classLoaders);
            throw exception;
        }
    }

    private static Properties readDescriptor(Path jar) throws IOException, ModuleDiscoveryException {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            JarEntry entry = jarFile.getJarEntry(DESCRIPTOR_PATH);
            if (entry == null) {
                throw new ModuleDiscoveryException("Missing " + DESCRIPTOR_PATH + " in " + jar);
            }
            Properties properties = new Properties();
            try (InputStream input = jarFile.getInputStream(entry)) {
                properties.load(input);
            }
            return properties;
        }
    }

    private static ModuleDescriptor descriptor(Properties properties, Path jar)
            throws ModuleDiscoveryException {
        String id = required(properties, "id", jar);
        String name = required(properties, "name", jar);
        String version = required(properties, "version", jar);
        String apiVersion = required(properties, "apiVersion", jar);
        String dependenciesValue = properties.getProperty("dependencies", "").trim();
        List<String> dependencies = dependenciesValue.isEmpty()
                ? List.of()
                : java.util.Arrays.stream(dependenciesValue.split(","))
                        .map(String::trim).filter(value -> !value.isEmpty()).toList();
        try {
            return new ModuleDescriptor(id, name, version, apiVersion, dependencies);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new ModuleDiscoveryException("Invalid module descriptor in " + jar, exception);
        }
    }

    private static String required(Properties properties, String key, Path jar)
            throws ModuleDiscoveryException {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new ModuleDiscoveryException(
                    "Missing required property '" + key + "' in " + jar);
        }
        return value.trim();
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static void closeAll(List<URLClassLoader> classLoaders) {
        for (int index = classLoaders.size() - 1; index >= 0; index--) {
            try {
                classLoaders.get(index).close();
            } catch (IOException ignored) {
                // Preserve the original discovery failure.
            }
        }
    }

    /** Holds discovered module instances and their classloaders as one lifecycle unit. */
    public static final class LoadedModules implements AutoCloseable {
        private final List<NexoraModule> modules;
        private final List<URLClassLoader> classLoaders;
        private boolean registered;
        private boolean closed;

        private LoadedModules(List<NexoraModule> modules, List<URLClassLoader> classLoaders) {
            this.modules = List.copyOf(modules);
            this.classLoaders = List.copyOf(classLoaders);
        }

        public List<NexoraModule> modules() {
            return modules;
        }

        /** Registers all discovered modules atomically with respect to duplicate IDs. */
        public synchronized void registerWith(ModuleManager manager) {
            if (closed) {
                throw new IllegalStateException("Loaded modules are already closed");
            }
            if (registered) {
                throw new IllegalStateException("Loaded modules have already been registered");
            }
            Objects.requireNonNull(manager, "manager").registerAll(modules);
            registered = true;
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            IOException failure = null;
            for (int index = classLoaders.size() - 1; index >= 0; index--) {
                try {
                    classLoaders.get(index).close();
                } catch (IOException exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
            closed = true;
            if (failure != null) {
                throw failure;
            }
        }
    }
}
