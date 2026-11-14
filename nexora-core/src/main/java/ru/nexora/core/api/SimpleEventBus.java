package ru.nexora.core.api;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/** Thread-safe event registration with synchronous event delivery. */
public final class SimpleEventBus implements EventBus {
    private final ConcurrentMap<Class<?>, CopyOnWriteArrayList<Consumer<?>>> listeners =
            new ConcurrentHashMap<>();

    @Override
    public <E extends NexoraEvent> Subscription subscribe(
            Class<E> eventType, Consumer<? super E> listener) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(listener, "listener");

        CopyOnWriteArrayList<Consumer<?>> eventListeners =
                listeners.computeIfAbsent(eventType, ignored -> new CopyOnWriteArrayList<>());
        eventListeners.add(listener);

        return () -> {
            eventListeners.remove(listener);
            if (eventListeners.isEmpty()) {
                listeners.remove(eventType, eventListeners);
            }
        };
    }

    @Override
    public <E extends NexoraEvent> E publish(E event) {
        Objects.requireNonNull(event, "event");
        CopyOnWriteArrayList<Consumer<?>> eventListeners = listeners.get(event.getClass());
        if (eventListeners == null) {
            return event;
        }

        EventDispatchException dispatchFailure = null;
        for (Consumer<?> rawListener : eventListeners) {
            @SuppressWarnings("unchecked")
            Consumer<E> listener = (Consumer<E>) rawListener;
            try {
                listener.accept(event);
            } catch (RuntimeException failure) {
                if (dispatchFailure == null) {
                    dispatchFailure = new EventDispatchException(
                            "One or more listeners failed for " + event.getClass().getName(), failure);
                } else {
                    dispatchFailure.addSuppressed(failure);
                }
            }
        }
        if (dispatchFailure != null) {
            throw dispatchFailure;
        }
        return event;
    }

    public int listenerCount(Class<? extends NexoraEvent> eventType) {
        CopyOnWriteArrayList<Consumer<?>> eventListeners = listeners.get(eventType);
        return eventListeners == null ? 0 : eventListeners.size();
    }

    public void clear() {
        listeners.clear();
    }
}
