package ru.nexora.world;

/** Receives committed runtime block changes; world generation and chunk loading are excluded. */
@FunctionalInterface
public interface WorldBlockChangeListener {
    void onBlockChanged(World.BlockChange change);
}
