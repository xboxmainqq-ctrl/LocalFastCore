package ru.nexora.runtime;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Validated bind and identity settings for the Bedrock NetherNet listener.
 * A separate ICE port of zero means WebRTC media shares the host-assigned Bedrock port.
 */
public record BedrockListenerSettings(
        boolean enabled,
        String bindAddress,
        int signalingPort,
        int separateIcePort,
        Path identityFile,
        boolean requireRetailAuthentication
) {
    public BedrockListenerSettings {
        bindAddress = Objects.requireNonNull(bindAddress, "bindAddress").trim();
        if (bindAddress.isEmpty()) {
            throw new IllegalArgumentException("Bedrock bind address must not be blank");
        }
        if (signalingPort < 1 || signalingPort > 65535) {
            throw new IllegalArgumentException("Signaling port must be between 1 and 65535");
        }
        if (separateIcePort < 0 || separateIcePort > 65535) {
            throw new IllegalArgumentException("Separate ICE port must be 0 (shared) or between 1 and 65535");
        }
        if (separateIcePort == signalingPort) {
            throw new IllegalArgumentException("Separate ICE port must differ from the signaling port");
        }
        identityFile = Objects.requireNonNull(identityFile, "identityFile").normalize();
        if (identityFile.toString().isBlank()) {
            throw new IllegalArgumentException("NetherNet identity file must not be blank");
        }
        if (enabled && !requireRetailAuthentication) {
            throw new IllegalArgumentException(
                    "Production Bedrock networking requires retail identity authentication");
        }
    }

    public static BedrockListenerSettings disabled() {
        return new BedrockListenerSettings(false, "0.0.0.0", 19132, 0,
                Path.of("nethernet-identity.pem"), true);
    }

    public int mediaUdpPort() {
        return separateIcePort == 0 ? signalingPort : separateIcePort;
    }

    public InetSocketAddress signalingBindAddress() {
        return new InetSocketAddress(bindAddress, signalingPort);
    }

    public InetSocketAddress mediaBindAddress() {
        return new InetSocketAddress(bindAddress, mediaUdpPort());
    }
}
