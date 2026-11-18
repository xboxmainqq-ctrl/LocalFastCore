package ru.nexora.network;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/** Compare-and-set persistence contract for transfer records. */
public interface PlayerTransferLedger {
    Optional<PlayerTransferRecord> get(UUID transferId) throws IOException;
    boolean create(PlayerTransferRecord prepared) throws IOException;
    boolean compareAndSet(UUID transferId, long expectedVersion,
                          PlayerTransferRecord replacement) throws IOException;
}
