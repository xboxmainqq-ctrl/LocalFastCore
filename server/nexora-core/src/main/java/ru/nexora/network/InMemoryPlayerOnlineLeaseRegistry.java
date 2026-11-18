package ru.nexora.network;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Thread-safe single-process lease registry for deterministic tests and standalone use. */
public final class InMemoryPlayerOnlineLeaseRegistry implements PlayerOnlineLeaseRegistry {
    private record Lease(String nodeId, String sessionId, long expiresAt) { }
    private final Map<String, Lease> leases = new HashMap<>();

    @Override
    public synchronized boolean acquire(String xuid, String nodeId, String sessionId,
                                         long now, long leaseMillis) {
        validate(xuid, nodeId, sessionId, now, leaseMillis);
        Lease current = leases.get(xuid);
        if (current != null && current.expiresAt() > now
                && !(current.nodeId().equals(nodeId) && current.sessionId().equals(sessionId))) {
            return false;
        }
        leases.put(xuid, new Lease(nodeId, sessionId, Math.addExact(now, leaseMillis)));
        return true;
    }

    @Override
    public synchronized boolean renew(String xuid, String nodeId, String sessionId,
                                      long now, long leaseMillis) {
        validate(xuid, nodeId, sessionId, now, leaseMillis);
        Lease current = leases.get(xuid);
        if (current == null || current.expiresAt() <= now
                || !current.nodeId().equals(nodeId) || !current.sessionId().equals(sessionId)) return false;
        leases.put(xuid, new Lease(nodeId, sessionId, Math.addExact(now, leaseMillis)));
        return true;
    }

    @Override
    public synchronized boolean release(String xuid, String nodeId, String sessionId) {
        validateIdentity(xuid, nodeId, sessionId);
        Lease current = leases.get(xuid);
        if (current == null || !current.nodeId().equals(nodeId) || !current.sessionId().equals(sessionId)) return false;
        leases.remove(xuid);
        return true;
    }

    private static void validate(String xuid, String nodeId, String sessionId, long now, long leaseMillis) {
        validateIdentity(xuid, nodeId, sessionId);
        if (now < 0 || leaseMillis < 1 || now > Long.MAX_VALUE - leaseMillis) {
            throw new IllegalArgumentException("Invalid lease time");
        }
    }

    private static void validateIdentity(String xuid, String nodeId, String sessionId) {
        if (xuid == null || !xuid.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid XUID");
        if (nodeId == null || !nodeId.matches("[A-Za-z0-9._-]{1,64}")) throw new IllegalArgumentException("Invalid node ID");
        Objects.requireNonNull(sessionId, "sessionId");
        if (!sessionId.matches("[A-Za-z0-9._-]{1,128}")) throw new IllegalArgumentException("Invalid session ID");
    }
}
