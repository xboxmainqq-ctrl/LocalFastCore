package ru.nexora.client;

import java.util.List;
import java.util.Objects;

/** Ordered pack stack selected by the Bedrock server for this world. */
record BedrockAndroidResourcePackStack(
        boolean required,
        String gameVersion,
        List<String> resourceNames
) {
    BedrockAndroidResourcePackStack {
        Objects.requireNonNull(gameVersion, "gameVersion");
        resourceNames = List.copyOf(Objects.requireNonNull(resourceNames, "resourceNames"));
    }
}
