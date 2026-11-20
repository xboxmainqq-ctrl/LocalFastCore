package ru.nexora.network.nethernet;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CloudburstNetherNetConfigurationTest {
    private static CloudburstNetherNetConfiguration config(
            Map<String, String> environment, int min, int max, List<String> stun) {
        return CloudburstNetherNetConfiguration.fromEnvironment(
                environment, Path.of("server-data"), min, max, stun);
    }

    @Test
    void suppliesStableDefaultsWithoutOptionalDeploymentSettings() {
        var result = config(Map.of(), 30000, 30199, List.of());

        assertNull(result.advertisedHost());
        assertEquals(0, result.publicUdpPort());
        assertEquals("NEXOR", result.identityDomain());
        assertNull(result.httpsCertificate());
        assertNull(result.httpsPrivateKey());
    }

    @Test
    void resolvesRelativeTlsFilesAgainstServerDataDirectory() {
        var result = config(Map.of(
                "NEXORA_HTTPS_CERT_FILE", "certs/fullchain.pem",
                "NEXORA_HTTPS_KEY_FILE", "certs/privkey.pem",
                "NEXORA_IDENTITY_DOMAIN", "play.nexor.example"),
                30000, 30199, List.of());

        assertEquals(Path.of("server-data/certs/fullchain.pem"), result.httpsCertificate());
        assertEquals(Path.of("server-data/certs/privkey.pem"), result.httpsPrivateKey());
        assertEquals("play.nexor.example", result.identityDomain());
    }

    @Test
    void preservesAbsoluteTlsPaths() {
        var result = config(Map.of(
                "NEXORA_HTTPS_CERT_FILE", "/etc/ssl/nexor/fullchain.pem",
                "NEXORA_HTTPS_KEY_FILE", "/etc/ssl/nexor/privkey.pem"),
                30000, 30199, List.of());

        assertEquals(Path.of("/etc/ssl/nexor/fullchain.pem"), result.httpsCertificate());
        assertEquals(Path.of("/etc/ssl/nexor/privkey.pem"), result.httpsPrivateKey());
    }

    @Test
    void requiresCertificateAndPrivateKeyTogether() {
        assertThrows(IllegalArgumentException.class, () -> config(
                Map.of("NEXORA_HTTPS_CERT_FILE", "cert.pem"),
                30000, 30199, List.of()));
        assertThrows(IllegalArgumentException.class, () -> config(
                Map.of("NEXORA_HTTPS_KEY_FILE", "key.pem"),
                30000, 30199, List.of()));
    }

    @Test
    void validatesAdvertisedHostAndPublicPortMapping() {
        var result = config(Map.of(
                "NEXORA_ADVERTISED_HOST", "play.nexor.example",
                "NEXORA_PUBLIC_UDP_PORT", "40000"),
                30000, 30000, List.of());

        assertEquals("play.nexor.example", result.advertisedHost());
        assertEquals(40000, result.publicUdpPort());

        assertThrows(IllegalArgumentException.class, () -> config(
                Map.of("NEXORA_ADVERTISED_HOST", "https://play.nexor.example"),
                30000, 30000, List.of()));
        assertThrows(IllegalArgumentException.class, () -> config(
                Map.of("NEXORA_PUBLIC_UDP_PORT", "40000"),
                30000, 30000, List.of()));
        assertThrows(IllegalArgumentException.class, () -> config(
                Map.of("NEXORA_ADVERTISED_HOST", "play.nexor.example",
                        "NEXORA_PUBLIC_UDP_PORT", "40000"),
                30000, 30199, List.of()));
    }

    @Test
    void requiresFixedIcePortForStunDiscoveryWithoutAdvertisedHost() {
        assertThrows(IllegalArgumentException.class, () -> config(
                Map.of(), 30000, 30199, List.of("stun:stun.example.net:3478")));

        assertDoesNotThrow(() -> config(
                Map.of(), 30000, 30000, List.of("stun:stun.example.net:3478")));
    }

    @Test
    void resolvesDnsAdvertisedHostsToNumericCloudburstEndpoints() throws Exception {
        var endpoints = CloudburstNetherNetTransport.resolveAdvertisedEndpoints("localhost", 40000);

        assertFalse(endpoints.isEmpty());
        assertTrue(endpoints.stream().allMatch(endpoint ->
                endpoint.endsWith(":40000")));
        assertTrue(endpoints.stream().allMatch(endpoint ->
                endpoint.matches("(?:[0-9.]+|\\[[0-9a-fA-F:]+\\]):40000")));
    }

    @Test
    void rejectsInvalidPortsAndIdentityDomains() {
        assertThrows(IllegalArgumentException.class, () -> config(
                Map.of("NEXORA_PUBLIC_UDP_PORT", "not-a-port",
                        "NEXORA_ADVERTISED_HOST", "play.nexor.example"),
                30000, 30000, List.of()));
        assertThrows(IllegalArgumentException.class, () -> config(
                Map.of("NEXORA_IDENTITY_DOMAIN", "bad domain"),
                30000, 30199, List.of()));
        assertThrows(IllegalArgumentException.class, () -> config(
                Map.of(), 0, 30199, List.of()));
    }
}
