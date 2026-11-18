package ru.nexora.network;

import ru.nexora.world.ChunkKey;

import java.util.Objects;
import java.util.function.Predicate;

/** Filters observer-visible work before network delivery. */
public final class ObserverFilter {
    private final InterestArea area;

    public ObserverFilter(InterestArea area) {
        this.area = Objects.requireNonNull(area);
    }

    public boolean visibleAt(ChunkKey chunk) {
        return area.contains(chunk.x(), chunk.z());
    }

    public <T> boolean visibleAt(T value, Predicate<T> visibilityRule) {
        return Objects.requireNonNull(visibilityRule).test(value);
    }
}
