package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NetherNetServerInfoTest {
    @Test
    void advertisesPrivateNexoraProtocolSeparatelyFromBedrockProtocolField() {
        NetherNetServerInfo info = new NetherNetServerInfo(
                "NEXORA", 2225, "0.1.0-dev", "world", 0, 20, 0);
        String json = info.toJson();

        assertTrue(json.contains("\"protocol\":2225"));
        assertTrue(json.contains("\"nexoraProtocol\":1"));
    }
}
