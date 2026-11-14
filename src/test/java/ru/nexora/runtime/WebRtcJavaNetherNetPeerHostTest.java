package ru.nexora.runtime;

import dev.onvoid.webrtc.RTCBundlePolicy;
import dev.onvoid.webrtc.RTCContinualGatheringPolicy;
import dev.onvoid.webrtc.RTCIceTransportPolicy;
import dev.onvoid.webrtc.RTCTcpCandidatePolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebRtcJavaNetherNetPeerHostTest {
    @Test
    void configuresNetherNetFullIceHostOnlyMaxBundleTransport() {
        var configuration = WebRtcJavaNetherNetPeerHost.createConfiguration();

        assertTrue(configuration.iceServers.isEmpty());
        assertEquals(RTCIceTransportPolicy.ALL, configuration.iceTransportPolicy);
        assertEquals(RTCBundlePolicy.MAX_BUNDLE, configuration.bundlePolicy);
        assertEquals(RTCTcpCandidatePolicy.DISABLED, configuration.tcpCandidatePolicy);
        assertEquals(RTCContinualGatheringPolicy.GATHER_ONCE,
                configuration.continualGatheringPolicy);
        assertTrue(configuration.portAllocatorConfig.isStunDisabled());
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
