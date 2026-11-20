package ru.nexora.plugins.api;

/** Handle used to cancel work scheduled through the platform adapter. */
public interface TaskHandle {
    boolean cancel();

    boolean isCancelled();
}
