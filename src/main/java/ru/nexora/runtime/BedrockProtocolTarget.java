package ru.nexora.runtime;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v2225.Bedrock_v2225;

/**
 * Bedrock protocol versions explicitly targeted by the MCPEHost integration.
 *
 * <p>Protocol 2225 is Minecraft Bedrock 1.26.60. The transport is NetherNet;
 * this codec declaration alone does not implement a Bedrock server login.</p>
 */
public final class BedrockProtocolTarget {
    public static final int BEDROCK_1_26_60_PROTOCOL = 2225;
    public static final String BEDROCK_1_26_60_VERSION = "1.26.60";

    private BedrockProtocolTarget() {
    }

    public static BedrockCodec codec12660() {
        return Bedrock_v2225.CODEC;
    }

    public static boolean supportsProtocol(int protocolVersion) {
        return protocolVersion == BEDROCK_1_26_60_PROTOCOL;
    }

    public static void verifyCodec() {
        int actual = codec12660().getProtocolVersion();
        if (!supportsProtocol(actual)) {
            throw new IllegalStateException("Bedrock 1.26.60 codec mismatch: expected "
                    + BEDROCK_1_26_60_PROTOCOL + " but got " + actual);
        }
    }
}
