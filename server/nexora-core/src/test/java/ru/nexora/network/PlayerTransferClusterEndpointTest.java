package ru.nexora.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nexora.inventory.ContainerInventory;
import ru.nexora.inventory.PlayerEquipment;
import ru.nexora.inventory.PlayerInventory;
import ru.nexora.persistence.FilePlayerStateStore;
import ru.nexora.persistence.PlayerStateBundle;
import ru.nexora.persistence.PlayerStateBundleCodec;
import ru.nexora.persistence.PlayerStateCheckpointService;
import ru.nexora.world.PlayerData;
import ru.nexora.world.PlayerVitals;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class PlayerTransferClusterEndpointTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsAuthenticatedRequestAndCommitsOnlyAfterCheckpointReservation() throws Exception {
        String xuid = "cluster-endpoint-xuid";
        PlayerStateCheckpointService checkpoints = checkpoints();
        PlayerStateBundle bundle = bundle(xuid);
        checkpoints.checkpoint(bundle, 100L);
        DurableHandoffCoordinator ledger = new DurableHandoffCoordinator(
                new PlayerTransferCoordinator(new InMemoryPlayerTransferLedger()));
        PlayerHandoff handoff = handoff();
        ledger.prepare(handoff, xuid, checkpoints.latestRevision(xuid), 100L);

        AtomicLong now = new AtomicLong(101L);
        AtomicInteger activations = new AtomicInteger();
        AtomicBoolean deactivated = new AtomicBoolean();
        CheckpointedPlayerTransferDestination destination = new CheckpointedPlayerTransferDestination(
                ledger, checkpoints, "target-node",
                new CheckpointedPlayerTransferDestination.Activator() {
                    @Override
                    public boolean restoreAndActivate(PlayerHandoff ignored,
                                                       PlayerStateBundle restored,
                                                       PlayerTransferFence fence) throws Exception {
                        assertEquals(xuid, restored.profile().xuid());
                        fence.assertCurrent();
                        activations.incrementAndGet();
                        return true;
                    }

                    @Override
                    public void deactivate(PlayerHandoff ignored, PlayerTransferFence fence) {
                        deactivated.set(true);
                    }
                }, now::get);
        PlayerTransferClusterEndpoint endpoint = new PlayerTransferClusterEndpoint(
                "target-node", ledger, destination, 5_000L, now::get);
        ClusterMessage message = message(handoff);

        endpoint.handle(message);
        assertEquals(PlayerTransferRecord.State.COMMITTED,
                ledger.get(handoff.transferId()).orElseThrow().state());
        assertEquals(1, activations.get());

        // A retransmission with a new transport message ID is still idempotent.
        endpoint.handle(message(handoff));
        assertEquals(1, activations.get());
        assertFalse(deactivated.get());
    }

    @Test
    void activationFailureDurablyAbortsAndDeactivatesDestinationReservation() throws Exception {
        String xuid = "cluster-endpoint-fail-xuid";
        PlayerStateCheckpointService checkpoints = checkpoints();
        checkpoints.checkpoint(bundle(xuid), 100L);
        DurableHandoffCoordinator ledger = new DurableHandoffCoordinator(
                new PlayerTransferCoordinator(new InMemoryPlayerTransferLedger()));
        PlayerHandoff handoff = handoff();
        ledger.prepare(handoff, xuid, checkpoints.latestRevision(xuid), 100L);

        AtomicBoolean deactivated = new AtomicBoolean();
        AtomicLong now = new AtomicLong(101L);
        CheckpointedPlayerTransferDestination destination = new CheckpointedPlayerTransferDestination(
                ledger, checkpoints, "target-node",
                new CheckpointedPlayerTransferDestination.Activator() {
                    @Override
                    public boolean restoreAndActivate(PlayerHandoff ignored,
                                                       PlayerStateBundle restored,
                                                       PlayerTransferFence fence) {
                        return false;
                    }

                    @Override
                    public void deactivate(PlayerHandoff ignored, PlayerTransferFence fence) {
                        deactivated.set(true);
                    }
                }, now::get);
        PlayerTransferClusterEndpoint endpoint = new PlayerTransferClusterEndpoint(
                "target-node", ledger, destination, 5_000L, now::get);

        assertThrows(java.io.IOException.class, () -> endpoint.handle(message(handoff)));
        assertEquals(PlayerTransferRecord.State.ABORTED,
                ledger.get(handoff.transferId()).orElseThrow().state());
        assertTrue(deactivated.get());
    }

    @Test
    void rejectsMessageFromNodeThatDoesNotOwnPreparedTransfer() throws Exception {
        String xuid = "cluster-endpoint-spoof-xuid";
        PlayerStateCheckpointService checkpoints = checkpoints();
        checkpoints.checkpoint(bundle(xuid), 100L);
        DurableHandoffCoordinator ledger = new DurableHandoffCoordinator(
                new PlayerTransferCoordinator(new InMemoryPlayerTransferLedger()));
        PlayerHandoff handoff = handoff();
        ledger.prepare(handoff, xuid, checkpoints.latestRevision(xuid), 100L);
        PlayerTransferClusterEndpoint endpoint = new PlayerTransferClusterEndpoint(
                "target-node", ledger, new CheckpointedPlayerTransferDestination(
                        ledger, checkpoints, "target-node",
                        new CheckpointedPlayerTransferDestination.Activator() {
                            @Override public boolean restoreAndActivate(PlayerHandoff ignored,
                                    PlayerStateBundle restored, PlayerTransferFence fence) { return true; }
                            @Override public void deactivate(PlayerHandoff ignored, PlayerTransferFence fence) { }
                        }, () -> 101L), 5_000L, () -> 101L);

        ClusterMessage spoofed = new ClusterMessage(UUID.randomUUID(), "attacker-node", "target-node",
                PlayerTransferClusterEndpoint.ACCEPT_MESSAGE_TYPE, 101L,
                PlayerTransferClusterEndpoint.encodeTransferId(handoff.transferId()));
        assertThrows(SecurityException.class, () -> endpoint.handle(spoofed));
        assertEquals(PlayerTransferRecord.State.PREPARED,
                ledger.get(handoff.transferId()).orElseThrow().state());
    }

    private PlayerStateCheckpointService checkpoints() throws Exception {
        return new PlayerStateCheckpointService(
                new FilePlayerStateStore(temporaryDirectory), new PlayerStateBundleCodec());
    }

    private static PlayerStateBundle bundle(String xuid) {
        return PlayerStateBundle.capture(
                new PlayerData(xuid, "ClusterPlayer", "world", 1.0f, 65.0f, 2.0f,
                        0.0f, 0.0f, 100L, PlayerVitals.DEFAULT, 0, 0),
                new PlayerInventory(),
                new ContainerInventory(PlayerStateBundle.CRAFTING_UI_SLOT_COUNT),
                new PlayerEquipment());
    }

    private static PlayerHandoff handoff() {
        return new PlayerHandoff(UUID.randomUUID(), 1L, "source-node", "target-node", new byte[0]);
    }

    private static ClusterMessage message(PlayerHandoff handoff) {
        return new ClusterMessage(UUID.randomUUID(), "source-node", "target-node",
                PlayerTransferClusterEndpoint.ACCEPT_MESSAGE_TYPE, 101L,
                PlayerTransferClusterEndpoint.encodeTransferId(handoff.transferId()));
    }
}
