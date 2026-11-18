package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeSliceTest {
    @Test
    void sliceAdmitsOnlyWorkAllowedByRuntime() {
        AdaptiveRuntime runtime = new AdaptiveRuntime(10_000_000L, 1_000_000L);
        RuntimeSlice slice = new RuntimeSlice(runtime);
        WorkGraph graph = new WorkGraph()
                .add("player", TaskContract.critical(AdaptiveRuntime.Lane.PLAYER, 100_000L))
                .add("background", TaskContract.deferrable(AdaptiveRuntime.Lane.BACKGROUND, 100_000L));

        assertFalse(slice.admit(graph).isEmpty());
        assertNotNull(slice.snapshot());
    }
}
