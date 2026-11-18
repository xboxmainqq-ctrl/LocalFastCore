package ru.nexora.network;

import ru.nexora.network.bedrock.BedrockSessionManager;
import ru.nexora.network.bedrock.BedrockTransferPacketCodec;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * Source-side transfer orchestration over an authenticated ClusterTransport.
 * The source is unfrozen only after a durable ABORTED state; uncertain outcomes
 * remain frozen, while a durable COMMITTED result triggers the Bedrock redirect.
 */
public final class PlayerTransferSourceCoordinator {
    private final String localNodeId;
    private final DurableHandoffCoordinator ledger;
    private final PlayerTransferLifecycle.Source source;
    private final BedrockSessionManager sessions;
    private final ClusterTransport transport;
    private final Duration acknowledgementTimeout;
    private final LongSupplier epochMillis;

    public PlayerTransferSourceCoordinator(
            String localNodeId,
            DurableHandoffCoordinator ledger,
            PlayerTransferLifecycle.Source source,
            BedrockSessionManager sessions,
            ClusterTransport transport,
            Duration acknowledgementTimeout,
            LongSupplier epochMillis
    ) {
        if (localNodeId == null || !localNodeId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("Invalid local node ID");
        }
        this.localNodeId = localNodeId;
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.source = Objects.requireNonNull(source, "source");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.acknowledgementTimeout = Objects.requireNonNull(acknowledgementTimeout, "acknowledgementTimeout");
        if (acknowledgementTimeout.isZero() || acknowledgementTimeout.isNegative()) {
            throw new IllegalArgumentException("acknowledgementTimeout must be positive");
        }
        this.epochMillis = Objects.requireNonNull(epochMillis, "epochMillis");
    }

    /**
     * Freezes/checkpoints, publishes PREPARED, requests destination acceptance,
     * reconciles uncertain delivery from the shared ledger, then redirects.
     */
    public PlayerTransferRecord transfer(
            PlayerHandoff handoff,
            String playerXuid,
            String destinationAddress,
            int destinationPort
    ) throws IOException {
        Objects.requireNonNull(handoff, "handoff");
        if (!localNodeId.equals(handoff.sourceNode())) {
            throw new SecurityException("Transfer source does not match this node");
        }
        if (handoff.targetNode().equals(localNodeId)) {
            throw new IllegalArgumentException("Transfer target must be another node");
        }
        // Validate the redirect target before freezing the source session.
        BedrockTransferPacketCodec.create(destinationAddress, destinationPort, true);

        long revision;
        try {
            revision = source.freezeAndCheckpoint(handoff);
        } catch (Exception freezeFailure) {
            if (freezeFailure instanceof IOException ioFailure) throw ioFailure;
            throw new IOException("Unable to freeze and checkpoint source player", freezeFailure);
        }
        try {
            ledger.prepare(handoff, playerXuid, revision, epochMillis.getAsLong());
        } catch (Exception publicationFailure) {
            try {
                resumeAfterAbort(handoff);
            } catch (IOException resumeFailure) {
                publicationFailure.addSuppressed(resumeFailure);
            }
            if (publicationFailure instanceof IOException ioFailure) throw ioFailure;
            throw new IOException("Unable to publish prepared player transfer", publicationFailure);
        }

        ClusterMessage request = new ClusterMessage(
                UUID.randomUUID(), localNodeId, handoff.targetNode(),
                PlayerTransferClusterEndpoint.ACCEPT_MESSAGE_TYPE,
                epochMillis.getAsLong(),
                PlayerTransferClusterEndpoint.encodeTransferId(handoff.transferId()));
        IOException deliveryFailure = null;
        try {
            transport.send(request).toCompletableFuture().get(
                    Math.max(1L, acknowledgementTimeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            deliveryFailure = new IOException("Interrupted while waiting for destination transfer ACK", interrupted);
        } catch (ExecutionException | TimeoutException | RuntimeException failed) {
            deliveryFailure = new IOException("Destination transfer acknowledgement was not confirmed", failed);
        }

        Optional<PlayerTransferRecord> currentValue = ledger.get(handoff.transferId());
        if (currentValue.isEmpty()) {
            throw new IOException("Prepared transfer disappeared from the shared ledger", deliveryFailure);
        }
        PlayerTransferRecord current = currentValue.orElseThrow();
        IOException ackFailure = deliveryFailure;
        if (current.state() == PlayerTransferRecord.State.COMMITTED) {
            redirect(handoff, playerXuid, current, destinationAddress, destinationPort);
            return current;
        }
        if (current.state() == PlayerTransferRecord.State.ABORTED) {
            resumeAfterAbort(handoff);
            throw new IOException("Destination aborted the player transfer; source session resumed", ackFailure);
        }

        Optional<PlayerTransferRecord> aborted = Optional.empty();
        long now = epochMillis.getAsLong();
        if (current.state() == PlayerTransferRecord.State.PREPARED) {
            aborted = ledger.abortPrepared(handoff.transferId(), now);
        } else if (current.state() == PlayerTransferRecord.State.CLAIMED) {
            aborted = ledger.abort(handoff.transferId(), current.fencingToken(), now);
        }
        if (aborted.isPresent()) {
            resumeAfterAbort(handoff);
            throw new IOException("Destination did not commit the player transfer; source session resumed", ackFailure);
        }

        // A concurrent commit/reclaim may have won while cancellation was attempted.
        Optional<PlayerTransferRecord> reconciledValue = ledger.get(handoff.transferId());
        if (reconciledValue.isEmpty()) {
            throw new IOException("Transfer disappeared during outcome reconciliation", ackFailure);
        }
        PlayerTransferRecord reconciled = reconciledValue.orElseThrow();
        if (reconciled.state() == PlayerTransferRecord.State.COMMITTED) {
            redirect(handoff, playerXuid, reconciled, destinationAddress, destinationPort);
            return reconciled;
        }
        if (reconciled.state() == PlayerTransferRecord.State.ABORTED) {
            resumeAfterAbort(handoff);
            throw new IOException("Transfer was durably aborted; source session resumed", ackFailure);
        }
        throw new IOException("Transfer outcome is uncertain; source remains frozen for recovery", ackFailure);
    }

    private void resumeAfterAbort(PlayerHandoff handoff) throws IOException {
        try {
            source.unfreeze(handoff);
        } catch (Exception failure) {
            if (failure instanceof IOException ioFailure) throw ioFailure;
            throw new IOException("Durable transfer abort was confirmed but source could not resume", failure);
        }
    }

    private void redirect(PlayerHandoff handoff, String playerXuid, PlayerTransferRecord committed,
                          String destinationAddress, int destinationPort) throws IOException {
        if (!playerXuid.equals(committed.playerXuid())
                || !handoff.targetNode().equals(committed.targetNode())
                || !localNodeId.equals(committed.sourceNode())) {
            throw new SecurityException("Committed transfer identity does not match source request");
        }
        sessions.redirectFrozenPlayerToNode(
                playerXuid, handoff.transferId(), committed.fencingToken(),
                ledger, localNodeId, destinationAddress, destinationPort);
    }
}
