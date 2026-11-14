package ru.nexora.core.api;

/**
 * Reports listener failures after every listener in the current dispatch
 * snapshot has had an opportunity to receive the event.
 */
public final class EventDispatchException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    EventDispatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
