package ru.nexora.runtime;

/** Immutable pressure snapshot used by routing and admission layers. */
public record LoadSnapshot(
        int players,
        int activeChunks,
        int entities,
        long gameplayEvents,
        long networkBytes,
        double pressure) {

    public LoadSnapshot {
        if (players < 0 || activeChunks < 0 || entities < 0 || gameplayEvents < 0 || networkBytes < 0)
            throw new IllegalArgumentException("negative load metric");
        if (pressure < 0.0 || pressure > 1.0)
            throw new IllegalArgumentException("pressure must be 0..1");
    }

    public boolean overloaded() {
        return pressure >= 0.85;
    }
}
