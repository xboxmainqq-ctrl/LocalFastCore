package dev.nexora.platform.api;

/**
 * Stable, engine-neutral view of the running NEXORA host.
 */
public interface NexoraServer {
    String serverId();

    String platformVersion();

    long uptimeMillis();
}
