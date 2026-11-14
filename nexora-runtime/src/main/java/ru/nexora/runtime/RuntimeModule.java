package ru.nexora.runtime;

/**
 * A lifecycle-managed component owned by the standalone NEXORA runtime.
 *
 * <p>If start() fails after acquiring any resources, the implementation must
 * release those partially acquired resources itself before propagating the
 * failure. The server coordinator only calls stop() for modules whose start()
 * completed successfully. stop() should be safe to retry after a failure and
 * should release all resources it can before reporting an exception.</p>
 */
public interface RuntimeModule {
    /** Stable, unique module identifier. */
    String id();

    /** Initialize the module and acquire its resources. */
    void start() throws Exception;

    /** Release resources acquired by start(); implementations should support retry. */
    void stop() throws Exception;
}
