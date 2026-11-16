package dev.nexora.platform.api;

/**
 * Minimal logging boundary so modules do not depend on a host logging library.
 */
public interface NexoraLogger {
    void info(String message);

    void warn(String message);

    void error(String message, Throwable cause);
}
