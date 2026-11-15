package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockProtocolTargetTest {
    @Test
    void exposesMinecraft12660Codec() {
        BedrockProtocolTarget.verifyCodec();

        assertEquals(2225, BedrockProtocolTarget.codec12660().getProtocolVersion());
        assertEquals("1.26.60", BedrockProtocolTarget.BEDROCK_1_26_60_VERSION);
    }

    @Test
    void onlyClaimsTheVerifiedProtocolVersion() {
        assertTrue(BedrockProtocolTarget.supportsProtocol(2225));
        assertFalse(BedrockProtocolTarget.supportsProtocol(2193));
        assertFalse(BedrockProtocolTarget.supportsProtocol(2226));
    }
}
