package ru.nexora.client;

/** Negotiated Bedrock NetworkSettings values needed by the Android packet codec. */
public final class BedrockAndroidNetworkSettings {
    private final int compressionThreshold;
    private final int compressionAlgorithm;
    private final boolean clientThrottleEnabled;
    private final int clientThrottleThreshold;
    private final float clientThrottleScalar;

    public BedrockAndroidNetworkSettings(int compressionThreshold, int compressionAlgorithm,
                                         boolean clientThrottleEnabled, int clientThrottleThreshold,
                                         float clientThrottleScalar) {
        if (compressionThreshold < 0 || compressionThreshold > 0xffff
                || (compressionAlgorithm != 0 && compressionAlgorithm != 1
                && compressionAlgorithm != 0xffff)
                || clientThrottleThreshold < 0 || clientThrottleThreshold > 0xff
                || !Float.isFinite(clientThrottleScalar)) {
            throw new IllegalArgumentException("Invalid Bedrock NetworkSettings values");
        }
        this.compressionThreshold = compressionThreshold;
        this.compressionAlgorithm = compressionAlgorithm;
        this.clientThrottleEnabled = clientThrottleEnabled;
        this.clientThrottleThreshold = clientThrottleThreshold;
        this.clientThrottleScalar = clientThrottleScalar;
    }

    public int compressionThreshold() { return compressionThreshold; }
    public int compressionAlgorithm() { return compressionAlgorithm; }
    public boolean clientThrottleEnabled() { return clientThrottleEnabled; }
    public int clientThrottleThreshold() { return clientThrottleThreshold; }
    public float clientThrottleScalar() { return clientThrottleScalar; }
}
