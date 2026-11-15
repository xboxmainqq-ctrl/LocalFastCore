package ru.nexora.runtime;

import java.util.concurrent.CompletableFuture;

/** Requests a chunk without blocking the network or caller thread. */
@FunctionalInterface
public interface AsyncChunkLoader {
    /** Completes true once the requested chunk is available in the world. */
    CompletableFuture<Boolean> request(ChunkPosition position);
}
