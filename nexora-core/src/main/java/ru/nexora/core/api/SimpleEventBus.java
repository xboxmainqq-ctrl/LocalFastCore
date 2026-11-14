package ru.nexora.core.api;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.Objects;

/** Thread-safe event registration with synchronous event delivery. */
public final class SimpleEventBus implements EventBus {
    private final ConcurrentMap<Class<?>, CopyOnWriteArrayList<Consumer<?>>> listeners =
            new ConcurrentHashMap<>();
    private final Consumer<EventDispatchException> failureHandler;

    public SimpleEventBus() {
        this(failure -> System.getLogger(SimpleEventBus.class.getName()).log(
                System.Logger.Level.ERROR, failure.getMessage(), failure));
    }

    public SimpleEventBus(Consumer<EventDispatchException> failureHandler) {
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
    }

    @Override
    public <E extends NexoraEvent> Subscription subscribe(
            Class<E> eventType, Consumer<? super E> listener) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(listener, "listener");

        listeners.compute(eventType, (ignored, current) -> {
            CopyOnWriteArrayList<Consumer<?>> eventListeners =
                    current == null ? new CopyOnWriteArrayList<>() : current;
            eventListeners.add(listener);
            return eventListeners;
        });

        return () -> listeners.computeIfPresent(eventType, (ignored, current) -> {
            current.remove(listener);
            return current.isEmpty() ? null : current;
        });
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
            try {
                failureHandler.accept(dispatchFailure);
            } catch (RuntimeException handlerFailure) {
                System.getLogger(SimpleEventBus.class.getName()).log(
                        System.Logger.Level.ERROR,
                        "NEXORA event failure handler itself failed", handlerFailure);
                System.getLogger(SimpleEventBus.class.getName()).log(
                        System.Logger.Level.ERROR, dispatchFailure.getMessage(), dispatchFailure);
            }
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
