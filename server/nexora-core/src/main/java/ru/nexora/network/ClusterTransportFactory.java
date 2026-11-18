package ru.nexora.network;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Strict environment parser for an authenticated TCP cluster transport. */
public final class ClusterTransportFactory {
    public record ConfiguredTransport(TcpClusterTransport transport, int peerCount, String description) {
        public ConfiguredTransport {
            if (transport == null) throw new NullPointerException("transport");
            if (peerCount < 1) throw new IllegalArgumentException("peerCount must be positive");
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("description must not be blank");
            }
        }
    }

    private ClusterTransportFactory() {
    }

    public static Optional<ConfiguredTransport> open(
            String localNodeId, Path dataDirectory, Map<String, String> environment
    ) {
        if (localNodeId == null || !localNodeId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("Invalid local node ID");
        }
        if (dataDirectory == null || environment == null) throw new NullPointerException();
        String bindValue = value(environment, "NEXORA_CLUSTER_BIND");
        String peersValue = value(environment, "NEXORA_CLUSTER_PEERS");
        String secretValue = value(environment, "NEXORA_CLUSTER_SHARED_SECRET");
        if (bindValue == null && peersValue == null && secretValue == null) return Optional.empty();
        if (bindValue == null || peersValue == null || secretValue == null) {
            throw new IllegalArgumentException(
                    "NEXORA_CLUSTER_BIND, NEXORA_CLUSTER_PEERS and NEXORA_CLUSTER_SHARED_SECRET must be configured together");
        }

        InetSocketAddress bindAddress = parseAddress(bindValue, "NEXORA_CLUSTER_BIND");
        Map<String, InetSocketAddress> peers = parsePeers(peersValue, localNodeId);
        if (peers.isEmpty()) throw new IllegalArgumentException("At least one NEXORA_CLUSTER_PEERS entry is required");
        byte[] secret;
        try {
            secret = Base64.getDecoder().decode(secretValue);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("NEXORA_CLUSTER_SHARED_SECRET must be Base64", invalid);
        }
        if (secret.length < 32) {
            throw new IllegalArgumentException("NEXORA_CLUSTER_SHARED_SECRET must decode to at least 32 bytes");
        }

        long skewMillis = longValue(environment, "NEXORA_CLUSTER_MAX_CLOCK_SKEW_MS", 30_000L, 1_000L, 300_000L);
        long replayRetentionMillis = longValue(
                environment, "NEXORA_CLUSTER_REPLAY_RETENTION_MS", 600_000L, 10_000L, 86_400_000L);
        int replayCapacity = intValue(environment, "NEXORA_CLUSTER_REPLAY_MAX_ENTRIES", 100_000, 128, 2_000_000);
        int workers = intValue(environment, "NEXORA_CLUSTER_WORKERS", 2, 1, 64);
        int queueCapacity = intValue(environment, "NEXORA_CLUSTER_QUEUE_CAPACITY", 128, 8, 65_536);
        long connectTimeout = longValue(environment, "NEXORA_CLUSTER_CONNECT_TIMEOUT_MS", 2_000L, 100L, 60_000L);
        long readTimeout = longValue(environment, "NEXORA_CLUSTER_READ_TIMEOUT_MS", 5_000L, 100L, 120_000L);
        Path replayDirectory = Path.of(value(environment, "NEXORA_CLUSTER_REPLAY_DIRECTORY") == null
                ? dataDirectory.resolve("cluster").resolve("replay").toString()
                : value(environment, "NEXORA_CLUSTER_REPLAY_DIRECTORY"));

        TcpClusterTransport transport = new TcpClusterTransport(
                localNodeId, bindAddress, peers, peers.keySet(),
                new AuthenticatedClusterMessageCodec(secret, skewMillis),
                new FileClusterReplayFilter(replayDirectory, replayCapacity, replayRetentionMillis),
                Duration.ofMillis(connectTimeout), Duration.ofMillis(readTimeout),
                workers, queueCapacity);
        return Optional.of(new ConfiguredTransport(
                transport, peers.size(), "authenticated TCP cluster transport (HMAC-SHA-256, "
                + peers.size() + " configured peers; shared secret hidden)"));
    }

    private static Map<String, InetSocketAddress> parsePeers(String value, String localNodeId) {
        Map<String, InetSocketAddress> peers = new LinkedHashMap<>();
        for (String entry : value.split(";", -1)) {
            String trimmed = entry.trim();
            int separator = trimmed.indexOf('=');
            if (separator <= 0 || separator != trimmed.lastIndexOf('=')) {
                throw new IllegalArgumentException("Invalid NEXORA_CLUSTER_PEERS entry");
            }
            String nodeId = trimmed.substring(0, separator).trim();
            if (!nodeId.matches("[A-Za-z0-9._-]{1,64}") || nodeId.equals(localNodeId)) {
                throw new IllegalArgumentException("Invalid or self-referencing cluster peer ID");
            }
            InetSocketAddress address = parseAddress(trimmed.substring(separator + 1).trim(),
                    "cluster peer " + nodeId);
            if (peers.putIfAbsent(nodeId, address) != null) {
                throw new IllegalArgumentException("Duplicate cluster peer: " + nodeId);
            }
        }
        return Map.copyOf(peers);
    }

    private static InetSocketAddress parseAddress(String value, String setting) {
        try {
            URI uri = URI.create("tcp://" + value);
            if (uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65_535
                    || uri.getUserInfo() != null || (uri.getPath() != null && !uri.getPath().isEmpty())
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            return new InetSocketAddress(uri.getHost(), uri.getPort());
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(setting + " must be host:port (IPv6 hosts must be bracketed)", invalid);
        }
    }

    private static String value(Map<String, String> environment, String name) {
        String result = environment.get(name);
        return result == null || result.isBlank() ? null : result.trim();
    }

    private static int intValue(Map<String, String> environment, String name,
                                int fallback, int minimum, int maximum) {
        long parsed = longValue(environment, name, fallback, minimum, maximum);
        return Math.toIntExact(parsed);
    }

    private static long longValue(Map<String, String> environment, String name,
                                  long fallback, long minimum, long maximum) {
        String value = value(environment, name);
        if (value == null) return fallback;
        try {
            long parsed = Long.parseLong(value);
            if (parsed < minimum || parsed > maximum) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(name + " must be in range " + minimum + ".." + maximum, invalid);
        }
    }
}
