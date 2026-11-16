package ru.nexora.runtime;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.util.Objects;
import java.util.Properties;

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


    /**
     * Reads the authenticated public Bedrock listener from server.properties.
     * Missing settings preserve the safe offline default.
     */
    public static BedrockListenerSettings loadFromProperties(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        if (Files.notExists(file)) return disabled();
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        }
        String enabledValue = properties.getProperty("bedrock.network.enabled", "false").trim();
        if (!"true".equalsIgnoreCase(enabledValue) && !"false".equalsIgnoreCase(enabledValue)) {
            throw new IOException("bedrock.network.enabled must be true or false in " + file);
        }
        if (!Boolean.parseBoolean(enabledValue)) return disabled();
        try {
            String bind = properties.getProperty("bedrock.bind-address", "0.0.0.0");
            int port = Integer.parseInt(properties.getProperty("bedrock.port", "19132").trim());
            int icePort = Integer.parseInt(properties.getProperty("bedrock.ice-port", "0").trim());
            Path identity = Path.of(properties.getProperty(
                    "bedrock.identity-file", "nethernet-identity.pem").trim());
            return new BedrockListenerSettings(true, bind, port, icePort, identity, true);
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid Bedrock listener setting in " + file + ": "
                    + invalid.getMessage(), invalid);
        }
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
