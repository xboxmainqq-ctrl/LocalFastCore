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
    @Test
    void aggregatesOnlyFreshRttSamplesAndKeepsCumulativeByteCounters() {
        var freshA = new WebRtcSdpNegotiator.TransportMetrics(20.0, 100L, 200L, 9_000L);
        var freshB = new WebRtcSdpNegotiator.TransportMetrics(40.0, 300L, 400L, 9_500L);
        var stale = new WebRtcSdpNegotiator.TransportMetrics(900.0, 500L, 600L, 1_000L);
        var missing = new WebRtcSdpNegotiator.TransportMetrics(-1.0, -1L, -1L, 0L);

        var summary = WebRtcSdpNegotiator.summarizeTransportMetrics(
                4, java.util.List.of(freshA, freshB, stale, missing), 10_000L, 2_000L);

        assertEquals(4, summary.connectedPeers());
        assertEquals(3, summary.peersWithSamples());
        assertEquals(2, summary.freshPeers());
        assertEquals(2, summary.staleOrMissingPeers());
        assertEquals(30.0, summary.averageRttMillis(), 0.0001);
        assertEquals(40.0, summary.maxRttMillis(), 0.0001);
        assertEquals(900L, summary.bytesSent());
        assertEquals(1_200L, summary.bytesReceived());
    }

    @Test
    void reportsUnavailableRttWhenNoFreshPeerSampleExists() {
        var stale = new WebRtcSdpNegotiator.TransportMetrics(80.0, 10L, 20L, 1_000L);
        var summary = WebRtcSdpNegotiator.summarizeTransportMetrics(
                1, java.util.List.of(stale), 10_000L, 500L);

        assertEquals(0, summary.freshPeers());
        assertEquals(-1.0, summary.averageRttMillis());
        assertEquals(-1.0, summary.maxRttMillis());
    }

    @Test
    void rejectsInvalidSummaryArguments() {
        assertThrows(IllegalArgumentException.class, () ->
                WebRtcSdpNegotiator.summarizeTransportMetrics(-1, java.util.List.of(), 0L, 1L));
        assertThrows(IllegalArgumentException.class, () ->
                WebRtcSdpNegotiator.summarizeTransportMetrics(0, java.util.List.of(), 0L, -1L));
    }

    @Test
    void retainsByteTotalsWhenFreshStatsHaveNoUsableRtt() {
        var bytesOnly = new WebRtcSdpNegotiator.TransportMetrics(-1.0, 700L, 900L, 9_500L);
        var summary = WebRtcSdpNegotiator.summarizeTransportMetrics(
                1, java.util.List.of(bytesOnly), 10_000L, 2_000L);

        assertEquals(1, summary.peersWithSamples());
        assertEquals(0, summary.freshPeers());
        assertEquals(1, summary.staleOrMissingPeers());
        assertEquals(700L, summary.bytesSent());
        assertEquals(900L, summary.bytesReceived());
    }


    @Test
    void aggregatesOnlyFreshTransportThroughputSamples() {
        var metrics = java.util.List.of(
                new WebRtcSdpNegotiator.TransportMetrics(15.0, 1_000L, 2_000L, 9_500L));
        var rates = java.util.List.of(
                new WebRtcSdpNegotiator.TransportRate(1_500.0, 2_500.0, 9_500L),
                new WebRtcSdpNegotiator.TransportRate(900.0, 1_100.0, 1_000L),
                new WebRtcSdpNegotiator.TransportRate(-1.0, -1.0, 0L));

        var summary = WebRtcSdpNegotiator.summarizeTransportMetrics(
                1, metrics, rates, 10_000L, 2_000L);

        assertEquals(1, summary.peersWithRate());
        assertEquals(1_500.0, summary.sentBytesPerSecond(), 0.0001);
        assertEquals(2_500.0, summary.receivedBytesPerSecond(), 0.0001);
    }

    @Test
    void reportsUnavailableThroughputWhenAllRateSamplesAreStale() {
        var staleRate = new WebRtcSdpNegotiator.TransportRate(100.0, 200.0, 1_000L);
        var summary = WebRtcSdpNegotiator.summarizeTransportMetrics(
                1, java.util.List.of(), java.util.List.of(staleRate), 10_000L, 500L);

        assertEquals(0, summary.peersWithRate());
        assertEquals(-1.0, summary.sentBytesPerSecond());
        assertEquals(-1.0, summary.receivedBytesPerSecond());
    }

    @Test
    void rejectsNullRateCollection() {
        assertThrows(NullPointerException.class, () ->
                WebRtcSdpNegotiator.summarizeTransportMetrics(
                        0, java.util.List.of(), null, 0L, 1L));
    }

}
