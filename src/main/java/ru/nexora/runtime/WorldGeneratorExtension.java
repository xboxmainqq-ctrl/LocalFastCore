package ru.nexora.runtime;

/**
 * Service-provider API for trusted server-side world generator extensions.
 *
 * <p>Implementations are discovered through Java ServiceLoader. Each provider
 * must be listed in
 * {@code META-INF/services/ru.nexora.runtime.WorldGeneratorExtension} inside
 * its extension JAR.</p>
 */
public interface WorldGeneratorExtension {
    /** Stable generator identifier used by {@code world.generator}. */
    String id();

    /** Creates a fresh generator instance for the selected world. */
    ChunkGenerator createGenerator();
}
