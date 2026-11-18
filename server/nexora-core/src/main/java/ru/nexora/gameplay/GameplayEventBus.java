package ru.nexora.gameplay;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Lightweight gameplay event bus for loose coupling between gameplay modules.
 *
 * <p>Events are delivered synchronously and deterministically in registration order.
 * The bus does not decide scheduling; event handlers may create WorkGraph nodes when
 * work needs adaptive execution.</p>
 */
public final class GameplayEventBus {
    public enum Type {
        CLASS_CHANGED,
        SPELL_CAST,
        ELEMENT_REACTION,
        DAMAGE,
        STATUS_EFFECT,
        QUEST_PROGRESS
    }

    private final Map<Type, List<Consumer<Event>>> handlers = new EnumMap<>(Type.class);

    public GameplayEventBus() {
        for (Type type : Type.values()) handlers.put(type, new ArrayList<>());
    }

    public synchronized void subscribe(Type type, Consumer<Event> handler) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(handler, "handler");
        handlers.get(type).add(handler);
    }

    public void publish(Event event) {
        Objects.requireNonNull(event, "event");
        List<Consumer<Event>> listeners;
        synchronized (this) {
            listeners = List.copyOf(handlers.get(event.type()));
        }
        for (Consumer<Event> listener : listeners) listener.accept(event);
    }

    public record Event(Type type, String actorId, Object payload) {
        public Event {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(actorId, "actorId");
        }
    }
}
