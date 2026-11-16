package ru.nexora.runtime;

import dev.onvoid.webrtc.RTCBundlePolicy;
import dev.onvoid.webrtc.RTCContinualGatheringPolicy;
import dev.onvoid.webrtc.RTCIceTransportPolicy;
import dev.onvoid.webrtc.RTCTcpCandidatePolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebRtcJavaNetherNetPeerHostTest {
    @Test
    void configuresNetherNetStunAndUdpOnlyMaxBundleTransport() {
        var configuration = WebRtcJavaNetherNetPeerHost.createConfiguration();

        assertEquals(2, configuration.iceServers.size());
        assertTrue(configuration.iceServers.stream()
                .anyMatch(server -> server.urls.contains("stun:stun.cloudflare.com:3478")));
        assertTrue(configuration.iceServers.stream()
                .anyMatch(server -> server.urls.contains("stun:stun.l.google.com:19302")));
        assertEquals(RTCIceTransportPolicy.ALL, configuration.iceTransportPolicy);
        assertEquals(RTCBundlePolicy.MAX_BUNDLE, configuration.bundlePolicy);
        assertEquals(RTCTcpCandidatePolicy.DISABLED, configuration.tcpCandidatePolicy);
        assertEquals(RTCContinualGatheringPolicy.GATHER_ONCE,
                configuration.continualGatheringPolicy);
        assertFalse(configuration.portAllocatorConfig.isStunDisabled());
        assertTrue(configuration.portAllocatorConfig.isRelayDisabled());
        assertTrue(configuration.portAllocatorConfig.isTcpDisabled());
    }

    @Test
    void advertisesOnlyTheDeliverySemanticsNetherNetActuallySupports() {
        WebRtcJavaNetherNetPeerHost host = new WebRtcJavaNetherNetPeerHost(8);

        assertEquals("nethernet-webrtc-java", host.id());
        assertEquals(new TransportCapabilities(true, true, true, false),
                host.capabilities());
    }
}
