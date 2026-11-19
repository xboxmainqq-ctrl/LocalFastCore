package ru.nexora.network;

import java.io.IOException;

/**
 * Cluster-wide exclusive online ownership lease for one authenticated Bedrock XUID.
 * The supplied epoch is used by local adapters and deterministic tests; JDBC
 * adapters must compare expiry using the shared database clock.
 */
public interface PlayerOnlineLeaseRegistry {
    boolean acquire(String playerXuid, String nodeId, String sessionId,
                    long nowEpochMillis, long leaseMillis) throws IOException;
    boolean renew(String playerXuid, String nodeId, String sessionId,
                  long nowEpochMillis, long leaseMillis) throws IOException;
    boolean release(String playerXuid, String nodeId, String sessionId) throws IOException;
}
