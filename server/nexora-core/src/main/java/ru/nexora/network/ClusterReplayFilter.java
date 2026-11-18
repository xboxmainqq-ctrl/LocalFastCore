package ru.nexora.network;

import java.util.UUID;

/** Pluggable replay/idempotency boundary for cluster message delivery. */
@FunctionalInterface
public interface ClusterReplayFilter {
    /** Returns true only when the message ID is newly accepted. */
    boolean accept(UUID messageId, long nowEpochMillis);
}
