package ru.nexora.network;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Compare-and-set persistence contract for transfer records. */
public interface PlayerTransferLedger {
    Optional<PlayerTransferRecord> get(UUID transferId) throws IOException;
    /** Latest durable transfer decision for one XUID, ordered by checkpoint revision and update time. */
    Optional<PlayerTransferRecord> findLatestForPlayer(String playerXuid) throws IOException;
    boolean create(PlayerTransferRecord prepared) throws IOException;
    boolean compareAndSet(UUID transferId, long expectedVersion,
                          PlayerTransferRecord replacement) throws IOException;

    /** Returns bounded, deterministically ordered CLAIMED/COMMITTED records for a destination. */
    List<PlayerTransferRecord> listRecoverableForTarget(String targetNode, int limit) throws IOException;
}
