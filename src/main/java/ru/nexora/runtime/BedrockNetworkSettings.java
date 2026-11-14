package ru.nexora.runtime;

/** Protocol 2225 NetworkSettingsPacket fields, excluding the outer packet header. */
public record BedrockNetworkSettings(
        int compressionThreshold,
        CompressionAlgorithm compressionAlgorithm,
        boolean clientThrottleEnabled,
        int clientThrottleThreshold,
        float clientThrottleScalar
) {
    public BedrockNetworkSettings {
        if (compressionThreshold < 0 || compressionThreshold > 0xFFFF) {
            throw new IllegalArgumentException("compressionThreshold must fit uint16");
        }
        if (compressionAlgorithm == null) {
            throw new NullPointerException("compressionAlgorithm");
        }
        if (clientThrottleThreshold < 0 || clientThrottleThreshold > 0xFF) {
            throw new IllegalArgumentException("clientThrottleThreshold must fit uint8");
        }
    }

    public enum CompressionAlgorithm {
        ZLIB(0),
        SNAPPY(1),
        NONE(0xFFFF);

        private final int wireValue;

        CompressionAlgorithm(int wireValue) {
            this.wireValue = wireValue;
        }

        public int wireValue() {
            return wireValue;
        }

        public static CompressionAlgorithm fromWireValue(int value) {
            for (CompressionAlgorithm algorithm : values()) {
                if (algorithm.wireValue == value) {
                    return algorithm;
                }
            }
            throw new IllegalArgumentException("Unsupported Bedrock compression algorithm: " + value);
        }
    }
}
