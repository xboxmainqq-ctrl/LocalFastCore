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

    static BedrockRuntimeBlockRegistry of(Map<BlockState, Integer> entries) {
        Map<BlockState, Integer> copy = Map.copyOf(Objects.requireNonNull(entries, "entries"));
        if (!copy.containsKey(BlockState.AIR)) {
            throw new IllegalArgumentException("The runtime block registry must define minecraft:air");
        }
        for (Map.Entry<BlockState, Integer> entry : copy.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "block state");
            Integer id = Objects.requireNonNull(entry.getValue(), "runtime ID");
            if (id < 0) {
                throw new IllegalArgumentException("Runtime block IDs must not be negative");
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
