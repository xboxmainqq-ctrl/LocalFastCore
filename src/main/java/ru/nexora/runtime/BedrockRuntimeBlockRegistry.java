package ru.nexora.runtime;

import java.util.Map;
import java.util.Objects;

/**
 * Explicit mapping from NEXORA's protocol-independent block states to the
 * runtime block IDs negotiated for one exact Bedrock client version.
 *
 * <p>No numeric IDs are guessed or inferred from block names. A registry
 * provider must supply IDs from the matching 1.26.60 block palette.</p>
 */
@FunctionalInterface
public interface BedrockRuntimeBlockRegistry {
    int runtimeId(BlockState state);

    /** Computes hashed block IDs from the NBT representation of each state. */
    static BedrockRuntimeBlockRegistry hashing() {
        java.util.concurrent.ConcurrentMap<Integer, BlockState> statesByHash =
                new java.util.concurrent.ConcurrentHashMap<>();
        return state -> {
            Objects.requireNonNull(state, "state");
            int hash = BedrockBlockStateHash.hash(state);
            BlockState previous = statesByHash.putIfAbsent(hash, state);
            if (previous != null && !previous.equals(state)) {
                throw new IllegalArgumentException(
                        "Bedrock block-state hash collision between " + previous + " and " + state);
            }
            if (previous == null && statesByHash.size() > 500_000) {
                statesByHash.remove(hash, state);
                throw new IllegalStateException("Hashed block registry exceeds 500,000 unique states");
            }
            return hash;
        };
    }

    static BedrockRuntimeBlockRegistry of(Map<BlockState, Integer> entries) {
        Map<BlockState, Integer> copy = Map.copyOf(Objects.requireNonNull(entries, "entries"));
        if (!copy.containsKey(BlockState.AIR)) {
            throw new IllegalArgumentException("The runtime block registry must define minecraft:air");
        }
        java.util.HashSet<Integer> uniqueIds = new java.util.HashSet<>();
        for (Map.Entry<BlockState, Integer> entry : copy.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "block state");
            Integer id = Objects.requireNonNull(entry.getValue(), "runtime ID");
            // Protocol 2225 supports signed ZigZag int32 block IDs when the
            // server advertises hashed block network IDs in StartGame.
            if (!uniqueIds.add(id)) {
                throw new IllegalArgumentException("Duplicate Bedrock block runtime ID: " + id);
            }
        }
        return state -> {
            Integer id = copy.get(Objects.requireNonNull(state, "state"));
            if (id == null) {
                throw new IllegalArgumentException("No Bedrock runtime ID registered for " + state);
            }
            return id;
        };
    }
}
