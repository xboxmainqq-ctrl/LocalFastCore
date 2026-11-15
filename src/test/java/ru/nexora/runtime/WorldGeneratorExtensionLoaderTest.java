package ru.nexora.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class WorldGeneratorExtensionLoaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void discoversGeneratorProviderFromExtensionJar() throws Exception {
        Path extensions = temporaryDirectory.resolve("extensions").resolve("generators");
        Files.createDirectories(extensions);
        createProviderJar(extensions.resolve("test-generator.jar"),
                TestExtension.class.getName());

        WorldGeneratorRegistry registry = WorldGeneratorRegistry.builtIn();
        try (WorldGeneratorExtensionLoader ignored =
                     WorldGeneratorExtensionLoader.load(extensions, registry)) {
            assertTrue(registry.contains("test_flat"));
            assertInstanceOf(TestGenerator.class, registry.create("test_flat"));
        }
    }

    @Test
    void emptyExtensionDirectoryIsSupported() throws Exception {
        Path extensions = temporaryDirectory.resolve("empty-generators");
        WorldGeneratorRegistry registry = WorldGeneratorRegistry.builtIn();
        try (WorldGeneratorExtensionLoader ignored =
                     WorldGeneratorExtensionLoader.load(extensions, registry)) {
            assertTrue(Files.isDirectory(extensions));
            assertTrue(registry.contains("overworld"));
        }
    }

    @Test
    void rejectsBrokenServiceProviderWithJarNameInError() throws Exception {
        Path extensions = temporaryDirectory.resolve("broken-generators");
        Files.createDirectories(extensions);
        createProviderJar(extensions.resolve("broken.jar"), "missing.provider.DoesNotExist");

        IOException failure = assertThrows(IOException.class,
                () -> WorldGeneratorExtensionLoader.load(
                        extensions, WorldGeneratorRegistry.builtIn()));
        assertTrue(failure.getMessage().contains("broken.jar"));
    }

    private static void createProviderJar(Path jar, String providerClass) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(
                    "META-INF/services/" + WorldGeneratorExtension.class.getName()));
            output.write((providerClass + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    public static final class TestExtension implements WorldGeneratorExtension {
        public TestExtension() {
        }

        @Override
        public String id() {
            return "test_flat";
        }

        @Override
        public ChunkGenerator createGenerator() {
            return new TestGenerator();
        }
    }

    static final class TestGenerator implements ChunkGenerator {
        @Override
        public GeneratedChunk generate(ChunkPosition position, WorldHeight height, long seed) {
            throw new UnsupportedOperationException("test generator");
        }
    }
}
