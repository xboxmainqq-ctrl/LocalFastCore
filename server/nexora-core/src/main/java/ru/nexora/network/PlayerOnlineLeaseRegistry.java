package ru.nexora.network;

import java.io.IOException;

/** Cluster-wide exclusive online ownership lease for one authenticated Bedrock XUID. */
public interface PlayerOnlineLeaseRegistry {
    boolean acquire(String playerXuid, String nodeId, String sessionId,
                    long nowEpochMillis, long leaseMillis) throws IOException;
    boolean renew(String playerXuid, String nodeId, String sessionId,
                  long nowEpochMillis, long leaseMillis) throws IOException;
    boolean release(String playerXuid, String nodeId, String sessionId) throws IOException;
}
