package ru.nexora.network.nethernet;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WebRtcTransportMetricsTest {
    @Test
    void reportsAvailabilityOnlyAfterAValidTimestampedRttSample() {
        var unknown = new WebRtcSdpNegotiator.TransportMetrics(-1.0, -1L, -1L, 0L);
        assertFalse(unknown.available());
        assertEquals(-1L, unknown.ageMillis(10_000L));
        assertTrue(unknown.stale(10_000L, 5_000L));

        var measured = new WebRtcSdpNegotiator.TransportMetrics(42.5, 1_024L, 2_048L, 8_000L);
        assertTrue(measured.available());
        assertEquals(2_000L, measured.ageMillis(10_000L));
        assertFalse(measured.stale(10_000L, 5_000L));
    }

    @Test
    void clampsFutureSampleAgeToZeroAndHonorsFreshnessBoundary() {
        var measured = new WebRtcSdpNegotiator.TransportMetrics(12.0, 10L, 20L, 12_000L);
        assertEquals(0L, measured.ageMillis(10_000L));
        assertFalse(measured.stale(17_000L, 5_000L));
        assertTrue(measured.stale(17_001L, 5_000L));
    }

    @Test
    void rejectsNegativeFreshnessWindow() {
        var measured = new WebRtcSdpNegotiator.TransportMetrics(12.0, 10L, 20L, 12_000L);
        assertThrows(IllegalArgumentException.class, () -> measured.stale(12_000L, -1L));
    }
}
