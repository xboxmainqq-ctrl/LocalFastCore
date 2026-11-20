package ru.nexora.client;

import java.util.Objects;

/** One verified-shape chunk received from a Bedrock server. */
record BedrockAndroidResourcePackChunk(
        String resourceName,
        int chunkIndex,
        long offset,
        byte[] data
) {
    BedrockAndroidResourcePackChunk {
        Objects.requireNonNull(resourceName, "resourceName");
        data = Objects.requireNonNull(data, "data").clone();
        if (resourceName.isBlank() || chunkIndex < 0 || offset < 0
                || data.length < 1 || data.length > 1024 * 1024) {
            throw new IllegalArgumentException("Invalid resource-pack chunk");
        }
    }

    @Override
    public byte[] data() {
        return data.clone();
    }
}
