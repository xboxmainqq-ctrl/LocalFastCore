package ru.nexora.plugins.api;

/** Minimal logging boundary; adapters map levels to their native logger. */
public interface PluginLogger {
    void info(String message);

    void warn(String message);

    void error(String message, Throwable cause);
}
