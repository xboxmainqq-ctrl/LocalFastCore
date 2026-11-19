package ru.nexora.runtime;

import java.util.Objects;

/**
 * Explicit execution contract for NEXORA work.
 *
 * <p>The contract tells the runtime what may be deferred and what must preserve
 * ordering. It does not contain gameplay logic.</p>
 */
public record TaskContract(
        AdaptiveRuntime.Lane lane,
        boolean critical,
        boolean deferrable,
        boolean orderingSensitive,
        long estimatedNanos
) {
    public TaskContract {
        Objects.requireNonNull(lane, "lane");
        if (estimatedNanos < 0) throw new IllegalArgumentException("estimatedNanos < 0");
        if (orderingSensitive && !critical && !deferrable) {
            // Valid: an ordering-sensitive task may be non-critical but must still
            // be allowed to wait for dependencies.
        }
    }

    public static TaskContract critical(AdaptiveRuntime.Lane lane, long estimatedNanos) {
        return new TaskContract(lane, true, false, true, estimatedNanos);
    }

    public static TaskContract deferrable(AdaptiveRuntime.Lane lane, long estimatedNanos) {
        return new TaskContract(lane, false, true, false, estimatedNanos);
    }

    public static TaskContract ordered(AdaptiveRuntime.Lane lane, long estimatedNanos) {
        return new TaskContract(lane, false, true, true, estimatedNanos);
    }
}
