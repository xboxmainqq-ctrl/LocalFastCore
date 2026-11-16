package ru.nexora.world;

/**
 * Receives committed runtime block changes; world generation and chunk loading are excluded.
 * Callbacks execute synchronously on the mutating thread and should remain short and non-blocking.
 */
@FunctionalInterface
public interface WorldBlockChangeListener {
    void onBlockChanged(World.BlockChange change);
}
