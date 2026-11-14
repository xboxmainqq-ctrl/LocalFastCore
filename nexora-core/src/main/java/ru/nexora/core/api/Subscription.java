package ru.nexora.core.api;

/** Handle that can be closed to stop receiving an event. */
@FunctionalInterface
public interface Subscription extends AutoCloseable {
    @Override
    void close();
}
