package ru.nexora.runtime;

/**
 * A lifecycle-managed component owned by the standalone NEXORA runtime.
 * Implementations must release their resources when stop() is called.
 */
public interface RuntimeModule {
    /** Stable, unique module identifier. */
    String id();

    /** Initialize the module and acquire its resources. */
    void start() throws Exception;

    /** Release resources acquired by start(). */
    void stop() throws Exception;
}
