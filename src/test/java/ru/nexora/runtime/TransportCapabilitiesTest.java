package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TransportCapabilitiesTest {
    @Test
    void acceptsNetherNetDeliveryShape() {
        TransportCapabilities capabilities = new TransportCapabilities(true, true, true, false);

        assertTrue(capabilities.reliableOrdered());
        assertTrue(capabilities.unreliable());
        assertTrue(capabilities.reliableFragmentation());
        assertFalse(capabilities.unreliableFragmentation());
    }

    @Test
    void rejectsFragmentationWithoutItsDeliveryMode() {
        assertThrows(IllegalArgumentException.class,
                () -> new TransportCapabilities(false, true, true, false));
        assertThrows(IllegalArgumentException.class,
                () -> new TransportCapabilities(true, false, false, true));
    }
}
