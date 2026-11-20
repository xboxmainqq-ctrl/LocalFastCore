package ru.nexora.client;

import java.util.Objects;

/** Bounded metadata for one Bedrock resource-pack ZIP download. */
record BedrockAndroidResourcePackDataInfo(
        String resourceName,
        int chunkSize,
        int chunkCount,
        long fileSize,
        String fileHash,
        int packType
) {
    BedrockAndroidResourcePackDataInfo {
        Objects.requireNonNull(resourceName, "resourceName");
        Objects.requireNonNull(fileHash, "fileHash");
        if (resourceName.isBlank() || chunkSize < 1 || chunkSize > 1024 * 1024
                || chunkCount < 1 || chunkCount > 64
                || fileSize < 1 || fileSize > 64L * 1024L * 1024L
                || !fileHash.matches("[0-9a-f]{64}") || packType != 6) {
            throw new IllegalArgumentException("Invalid resource-pack download metadata");
        }
    }
}
