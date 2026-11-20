package ru.nexora.network.nethernet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Rewrites host ICE candidates for a server behind a one-to-one UDP port forward.
 *
 * Server-reflexive and relay candidates are deliberately left untouched: their
 * addresses are discovered by ICE and must not be guessed by the application.
 */
public final class NetherNetSdpCandidateMapper {
    public static final String ADVERTISED_HOST = "NEXORA_ADVERTISED_HOST";
    public static final String PUBLIC_UDP_PORT = "NEXORA_PUBLIC_UDP_PORT";

    private final String advertisedHost;
    private final int publicUdpPortBase;
    private final int internalUdpPortMin;
    private final int internalUdpPortMax;

    public NetherNetSdpCandidateMapper(
            String advertisedHost,
            int publicUdpPortBase,
            int internalUdpPortMin,
            int internalUdpPortMax
    ) {
        String host = advertisedHost == null ? "" : advertisedHost.trim();
        if (host.isEmpty()) host = null;
        if (host != null && (!host.matches("[A-Za-z0-9._:-]+")
                || host.startsWith(".") || host.endsWith("."))) {
            throw new IllegalArgumentException("NEXORA_ADVERTISED_HOST must be an IP address or DNS host without a scheme or port");
        }
        boolean dynamicRange = internalUdpPortMin == 0 && internalUdpPortMax == 0;
        if (!dynamicRange && (internalUdpPortMin < 1024 || internalUdpPortMax > 65535
                || internalUdpPortMin > internalUdpPortMax)) {
            throw new IllegalArgumentException("Invalid internal UDP candidate port range");
        }
        if (publicUdpPortBase < 0 || publicUdpPortBase > 65535) {
            throw new IllegalArgumentException("NEXORA_PUBLIC_UDP_PORT must be 0 or 1..65535");
        }
        if (publicUdpPortBase > 0 && host == null) {
            throw new IllegalArgumentException("NEXORA_PUBLIC_UDP_PORT requires NEXORA_ADVERTISED_HOST");
        }
        if (publicUdpPortBase > 0 && dynamicRange) {
            throw new IllegalArgumentException("NEXORA_PUBLIC_UDP_PORT requires a fixed internal UDP port range");
        }
        if (publicUdpPortBase > 0
                && publicUdpPortBase + (internalUdpPortMax - internalUdpPortMin) > 65535) {
            throw new IllegalArgumentException("Public UDP port range exceeds 65535");
        }
        this.advertisedHost = host;
        this.publicUdpPortBase = publicUdpPortBase;
        this.internalUdpPortMin = internalUdpPortMin;
        this.internalUdpPortMax = internalUdpPortMax;
    }

    public static NetherNetSdpCandidateMapper fromEnvironment(
            Map<String, String> environment,
            int internalUdpPortMin,
            int internalUdpPortMax
    ) {
        Objects.requireNonNull(environment, "environment");
        String host = environment.get(ADVERTISED_HOST);
        String rawPort = environment.get(PUBLIC_UDP_PORT);
        int publicPort = 0;
        if (rawPort != null && !rawPort.isBlank()) {
            try {
                publicPort = Integer.parseInt(rawPort.trim());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid " + PUBLIC_UDP_PORT + ": " + rawPort, exception);
            }
        }
        return new NetherNetSdpCandidateMapper(host, publicPort, internalUdpPortMin, internalUdpPortMax);
    }

    public boolean isEnabled() {
        return advertisedHost != null;
    }

    public String rewrite(String sdp) {
        Objects.requireNonNull(sdp, "sdp");
        if (advertisedHost == null) return sdp;
        String[] lines = sdp.split("\\r?\\n", -1);
        List<String> rewritten = new ArrayList<>(lines.length);
        for (String line : lines) {
            if (!line.startsWith("a=candidate:")) {
                rewritten.add(line);
                continue;
            }
            String[] fields = line.split(" ");
            if (fields.length < 8 || !"typ".equals(fields[6]) || !"host".equals(fields[7])) {
                rewritten.add(line);
                continue;
            }
            if (!"udp".equalsIgnoreCase(fields[2])) {
                rewritten.add(line);
                continue;
            }
            int port;
            try {
                port = Integer.parseInt(fields[5]);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Malformed UDP host ICE candidate port", exception);
            }
            if (publicUdpPortBase > 0
                    && (port < internalUdpPortMin || port > internalUdpPortMax)) {
                throw new IllegalArgumentException("Host ICE candidate port is outside configured UDP range: " + port);
            }
            fields[4] = advertisedHost;
            if (publicUdpPortBase > 0) {
                fields[5] = Integer.toString(publicUdpPortBase + port - internalUdpPortMin);
            }
            rewritten.add(String.join(" ", fields));
        }
        return String.join("\\r\\n", rewritten);
    }
}
