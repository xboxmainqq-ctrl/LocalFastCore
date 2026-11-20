package ru.nexora.network.nethernet;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validated deployment settings for the Cloudburst-backed NetherNet transport.
 * Paths that are not absolute are resolved relative to the server data directory.
 */
record CloudburstNetherNetConfiguration(
        String advertisedHost,
        int publicUdpPort,
        String identityDomain,
        Path httpsCertificate,
        Path httpsPrivateKey
) {
    private static final Pattern ADVERTISED_HOST =
            Pattern.compile("[A-Za-z0-9._:%-]{1,253}");
    private static final Pattern IDENTITY_DOMAIN =
            Pattern.compile("[A-Za-z0-9._-]{1,255}");

    static CloudburstNetherNetConfiguration fromEnvironment(
            Map<String, String> environment,
            Path dataDirectory,
            int udpPortMin,
            int udpPortMax,
            List<String> stunUrls
    ) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Objects.requireNonNull(stunUrls, "stunUrls");
        if (udpPortMin < 1 || udpPortMax > 65535 || udpPortMin > udpPortMax) {
            throw new IllegalArgumentException("Invalid NetherNet UDP port range");
        }

        String host = blankToNull(environment.get("NEXORA_ADVERTISED_HOST"));
        if (host != null && !ADVERTISED_HOST.matcher(host).matches()) {
            throw new IllegalArgumentException(
                    "NEXORA_ADVERTISED_HOST must be a DNS name or IP address without a scheme or port");
        }

        int publicPort = parsePort(environment.get("NEXORA_PUBLIC_UDP_PORT"));
        if (publicPort > 0 && host == null) {
            throw new IllegalArgumentException(
                    "NEXORA_PUBLIC_UDP_PORT requires NEXORA_ADVERTISED_HOST");
        }
        if (publicPort > 0 && udpPortMin != udpPortMax) {
            throw new IllegalArgumentException(
                    "NEXORA_PUBLIC_UDP_PORT requires a fixed ICE port: set NEXORA_UDP_PORT_MIN and NEXORA_UDP_PORT_MAX to the same port");
        }
        if (!stunUrls.isEmpty() && host == null && udpPortMin != udpPortMax) {
            throw new IllegalArgumentException(
                    "Cloudburst STUN discovery requires one fixed ICE UDP port; set NEXORA_UDP_PORT_MIN and NEXORA_UDP_PORT_MAX to the same value");
        }

        String domain = blankToNull(environment.get("NEXORA_IDENTITY_DOMAIN"));
        if (domain == null) domain = "NEXOR";
        if (!IDENTITY_DOMAIN.matcher(domain).matches()) {
            throw new IllegalArgumentException("NEXORA_IDENTITY_DOMAIN contains unsupported characters");
        }

        String certificate = blankToNull(environment.get("NEXORA_HTTPS_CERT_FILE"));
        String privateKey = blankToNull(environment.get("NEXORA_HTTPS_KEY_FILE"));
        if ((certificate == null) != (privateKey == null)) {
            throw new IllegalArgumentException(
                    "Configure both NEXORA_HTTPS_CERT_FILE and NEXORA_HTTPS_KEY_FILE for built-in HTTPS");
        }

        return new CloudburstNetherNetConfiguration(
                host,
                publicPort,
                domain,
                resolve(dataDirectory, certificate),
                resolve(dataDirectory, privateKey));
    }

    private static Path resolve(Path base, String value) {
        if (value == null) return null;
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : base.resolve(path)).normalize();
    }

    private static int parsePort(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 0 || value > 65535) {
                throw new NumberFormatException("outside 0..65535");
            }
            return value;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid NEXORA_PUBLIC_UDP_PORT", failure);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
