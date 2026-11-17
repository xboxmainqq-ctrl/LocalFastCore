package ru.nexora.inventory;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DurabilitySettingsTest {

    @Test
    void durabilityIsDisabledWhenNoSettingIsProvided() {
        assertFalse(DurabilitySettings.defaults().enabled());
        assertFalse(DurabilitySettings.fromEnvironment(Map.of()).enabled());
    }

    @Test
    void acceptsExplicitEnableAndDisableValues() {
        assertTrue(DurabilitySettings.fromEnvironment(
                Map.of(DurabilitySettings.ENVIRONMENT_VARIABLE, "true")).enabled());
        assertTrue(DurabilitySettings.fromEnvironment(
                Map.of(DurabilitySettings.ENVIRONMENT_VARIABLE, "ON")).enabled());
        assertFalse(DurabilitySettings.fromEnvironment(
                Map.of(DurabilitySettings.ENVIRONMENT_VARIABLE, "false")).enabled());
        assertFalse(DurabilitySettings.fromEnvironment(
                Map.of(DurabilitySettings.ENVIRONMENT_VARIABLE, "0")).enabled());
    }

    @Test
    void rejectsAmbiguousConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> DurabilitySettings.fromEnvironment(
                Map.of(DurabilitySettings.ENVIRONMENT_VARIABLE, "sometimes")));
    }
}
