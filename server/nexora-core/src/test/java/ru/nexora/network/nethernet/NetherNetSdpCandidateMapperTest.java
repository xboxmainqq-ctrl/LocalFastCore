package ru.nexora.network.nethernet;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NetherNetSdpCandidateMapperTest {
    private static final String SDP = "v=0\r\n"
            + "a=candidate:1 1 udp 2122260223 192.168.1.20 30000 typ host generation 0\r\n"
            + "a=candidate:2 1 udp 1686052607 203.0.113.5 45000 typ srflx raddr 192.168.1.20 rport 30000\r\n"
            + "a=candidate:3 1 tcp 1518280447 192.168.1.20 9 typ host tcptype active\r\n"
            + "a=end-of-candidates\r\n";

    @Test
    void rewritesOnlyUdpHostCandidatesAndMapsForwardedPortRange() {
        NetherNetSdpCandidateMapper mapper =
                new NetherNetSdpCandidateMapper("play.example.net", 40000, 30000, 30199);

        String result = mapper.rewrite(SDP);

        assertTrue(result.contains("a=candidate:1 1 udp 2122260223 play.example.net 40000 typ host"));
        assertTrue(result.contains("a=candidate:2 1 udp 1686052607 203.0.113.5 45000 typ srflx"));
        assertTrue(result.contains("a=candidate:3 1 tcp 1518280447 192.168.1.20 9 typ host"));
        assertTrue(result.endsWith("a=end-of-candidates\r\n"));
    }

    @Test
    void canAdvertiseHostWithoutChangingCandidatePorts() {
        NetherNetSdpCandidateMapper mapper =
                new NetherNetSdpCandidateMapper("198.51.100.10", 0, 30000, 30199);
        String result = mapper.rewrite(SDP);
        assertTrue(result.contains("198.51.100.10 30000 typ host"));
        assertTrue(result.contains("203.0.113.5 45000 typ srflx"));
    }

    @Test
    void leavesSdpUntouchedWhenNoPublicHostIsConfigured() {
        NetherNetSdpCandidateMapper mapper =
                new NetherNetSdpCandidateMapper(null, 0, 0, 0);
        assertEquals(SDP, mapper.rewrite(SDP));
        assertFalse(mapper.isEnabled());
    }

    @Test
    void rejectsInvalidPublicPortConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new NetherNetSdpCandidateMapper("play.example.net", 65535, 30000, 30199));
        assertThrows(IllegalArgumentException.class,
                () -> new NetherNetSdpCandidateMapper(null, 40000, 30000, 30199));
    }

    @Test
    void loadsPublicMappingFromEnvironment() {
        NetherNetSdpCandidateMapper mapper = NetherNetSdpCandidateMapper.fromEnvironment(
                Map.of("NEXORA_ADVERTISED_HOST", "play.example.net", "NEXORA_PUBLIC_UDP_PORT", "40000"),
                30000, 30199);
        assertTrue(mapper.isEnabled());
        assertTrue(mapper.rewrite(SDP).contains("play.example.net 40000 typ host"));
    }

    @Test
    void rejectsCandidateOutsideConfiguredPortRangeWhenPortMappingIsEnabled() {
        String outside = "v=0\r\na=candidate:1 1 udp 100 192.168.1.20 31000 typ host\r\n";
        NetherNetSdpCandidateMapper mapper =
                new NetherNetSdpCandidateMapper("play.example.net", 40000, 30000, 30199);
        assertThrows(IllegalArgumentException.class, () -> mapper.rewrite(outside));
    }
}
