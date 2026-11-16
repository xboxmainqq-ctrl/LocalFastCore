package dev.nexora.platform.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ModuleJarLoaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void discoversJarAndRegistersItsEntrypoint() throws Exception {
        writeJar("example.jar", descriptor("0.1"));

        ModuleJarLoader loader = new ModuleJarLoader("0.1");
        try (ModuleJarLoader.LoadedModules loaded =
                     loader.loadDirectory(temporaryDirectory)) {
            assertEquals(1, loaded.modules().size());
            assertEquals("nexora.example", loaded.modules().get(0).descriptor().id());

            ModuleManager manager = new ModuleManager(context());
            loaded.registerWith(manager);
            assertEquals(ModuleState.REGISTERED, manager.stateOf("nexora.example"));
            assertThrows(IllegalStateException.class, () -> loaded.registerWith(manager));
        }
    }

    @Test
    void rejectsIncompatibleApiVersion() throws Exception {
        writeJar("incompatible.jar", descriptor("9.9"));

        ModuleDiscoveryException exception = assertThrows(ModuleDiscoveryException.class,
                () -> new ModuleJarLoader("0.1").loadDirectory(temporaryDirectory));
        assertTrue(exception.getMessage().contains("targets API"));
    }

    @Test
    void rejectsJarWithoutModuleDescriptor() throws Exception {
        Path jar = temporaryDirectory.resolve("missing-descriptor.jar");
        try (JarOutputStream output = new JarOutputStream(
                java.nio.file.Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("unrelated.txt"));
            output.write("not a module".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }

        assertThrows(ModuleDiscoveryException.class,
                () -> new ModuleJarLoader("0.1").loadDirectory(temporaryDirectory));
    }

    private void writeJar(String fileName, String properties) throws IOException {
        Path jar = temporaryDirectory.resolve(fileName);
        try (JarOutputStream output = new JarOutputStream(
                java.nio.file.Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(ModuleJarLoader.DESCRIPTOR_PATH));
            output.write(properties.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private static String descriptor(String apiVersion) {
        return "id=nexora.example\n"
                + "name=Example\n"
                + "version=1.0.0\n"
                + "apiVersion=" + apiVersion + "\n"
                + "dependencies=\n"
                + "entrypoint=" + ExampleModule.class.getName() + "\n";
    }

    public static class ExampleModule implements NexoraModule {
        public ExampleModule() {
        }

        @Override
        public ModuleDescriptor descriptor() {
            return new ModuleDescriptor("nexora.example", "Example",
                    "1.0.0", "0.1", List.of());
        }
    }

    private static ModuleContext context() {
        NexoraServer server = new NexoraServer() {
            public String serverId() { return "test"; }
            public String platformVersion() { return "test"; }
            public long uptimeMillis() { return 0; }
        };
        ServiceRegistry services = new ServiceRegistry() {
            public <T> void register(Class<T> type, T service) {
                throw new UnsupportedOperationException();
            }
            public <T> Optional<T> find(Class<T> type) {
                return Optional.empty();
            }
        };
        NexoraLogger logger = new NexoraLogger() {
            public void info(String message) { }
            public void warn(String message) { }
            public void error(String message, Throwable cause) { }
        };
        return new ModuleContext(server, services, logger);
    }
}
