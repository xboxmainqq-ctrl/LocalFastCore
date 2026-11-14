package ru.nexora.core.api;

import java.util.function.Consumer;

/** Synchronous typed event bus. Events are delivered on the publishing thread. */
public interface EventBus {
    <E extends NexoraEvent> Subscription subscribe(Class<E> eventType, Consumer<? super E> listener);

    <E extends NexoraEvent> E publish(E event);
}
