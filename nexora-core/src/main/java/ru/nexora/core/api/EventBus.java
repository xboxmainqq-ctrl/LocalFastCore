package ru.nexora.core.api;

import java.util.function.Consumer;

/**
 * Synchronous typed event bus. Events are delivered on the publishing thread.
 * Implementations may report listener runtime failures after attempting every
 * listener registered in the dispatch snapshot.
 */
public interface EventBus {
    <E extends NexoraEvent> Subscription subscribe(Class<E> eventType, Consumer<? super E> listener);

    <E extends NexoraEvent> E publish(E event);
}
