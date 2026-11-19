package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AdaptiveRuntimeTest {
    @Test
    void protectsEmergencyReserve() {
        AdaptiveRuntime runtime = new AdaptiveRuntime(10_000_000L, 2_000_000L);
        assertEquals(AdaptiveRuntime.Admission.ALLOW,
                runtime.admit(AdaptiveRuntime.Lane.PLAYER, 6_000_000L, true));
        assertEquals(AdaptiveRuntime.Admission.DEFER,
                runtime.admit(AdaptiveRuntime.Lane.BACKGROUND, 3_000_000L, false));
    }

    @Test
    void criticalWorkCanUseReservedCapacity() {
        AdaptiveRuntime runtime = new AdaptiveRuntime(10_000_000L, 2_000_000L);
        assertEquals(AdaptiveRuntime.Admission.ALLOW,
                runtime.admit(AdaptiveRuntime.Lane.PLAYER, 8_000_000L, true));
    }
}
