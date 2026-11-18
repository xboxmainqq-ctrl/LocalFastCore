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

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CheckpointedPlayerTransferDestinationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void restoresOnlyTheClaimedCheckpointAndRetainsFenceAfterCommit() throws Exception {
        PlayerStateCheckpointService checkpoints = checkpoints();
        checkpoints.checkpoint(bundle("transfer-destination-xuid", 4.0f), 100L);
        InMemoryPlayerTransferLedger store = new InMemoryPlayerTransferLedger();
        DurableHandoffCoordinator ledger = new DurableHandoffCoordinator(new PlayerTransferCoordinator(store));
        PlayerHandoff handoff = handoff();
        ledger.prepare(handoff, "transfer-destination-xuid", 0L, 100L);
        PlayerTransferRecord claim = ledger.claim(handoff.transferId(), "target-node", 101L, 10_000L)
                .orElseThrow();
        AtomicLong now = new AtomicLong(102L);
        AtomicReference<PlayerTransferFence> activatedFence = new AtomicReference<>();
        AtomicBoolean deactivated = new AtomicBoolean();
        CheckpointedPlayerTransferDestination destination = new CheckpointedPlayerTransferDestination(
                ledger, checkpoints, "target-node",
                new CheckpointedPlayerTransferDestination.Activator() {
                    @Override
                    public boolean restoreAndActivate(PlayerHandoff ignored,
                                                       PlayerStateBundle restored,
                                                       PlayerTransferFence fence) throws Exception {
                        assertEquals("transfer-destination-xuid", restored.profile().xuid());
                        assertEquals(4.0f, restored.profile().x());
                        fence.assertCurrent();
                        activatedFence.set(fence);
                        return true;
                    }

                    @Override
                    public void deactivate(PlayerHandoff ignored, PlayerTransferFence fence) {
                        deactivated.set(true);
                    }
                }, now::get);

        assertTrue(destination.restoreAndActivate(handoff, claim.fencingToken()));
        PlayerTransferFence fence = activatedFence.get();
        assertNotNull(fence);
        ledger.commit(handoff.transferId(), claim.fencingToken(), 103L).orElseThrow();
        fence.assertCurrent();
        destination.deactivateIfOwned(handoff, claim.fencingToken());
        assertFalse(deactivated.get(), "A committed destination must not be deactivated as rollback");
    }

    @Test
    void rejectsCheckpointWhenItsLatestRevisionDiffersFromClaim() throws Exception {
        PlayerStateCheckpointService checkpoints = checkpoints();
        checkpoints.checkpoint(bundle("transfer-destination-xuid", 4.0f), 100L);
        checkpoints.checkpoint(bundle("transfer-destination-xuid", 5.0f), 101L);
        InMemoryPlayerTransferLedger store = new InMemoryPlayerTransferLedger();
        DurableHandoffCoordinator ledger = new DurableHandoffCoordinator(new PlayerTransferCoordinator(store));
        PlayerHandoff handoff = handoff();
        ledger.prepare(handoff, "transfer-destination-xuid", 0L, 100L);
        PlayerTransferRecord claim = ledger.claim(handoff.transferId(), "target-node", 101L, 10_000L)
                .orElseThrow();
        AtomicBoolean activated = new AtomicBoolean();
        CheckpointedPlayerTransferDestination destination = new CheckpointedPlayerTransferDestination(
                ledger, checkpoints, "target-node",
                new CheckpointedPlayerTransferDestination.Activator() {
                    @Override
                    public boolean restoreAndActivate(PlayerHandoff ignored,
                                                       PlayerStateBundle restored,
                                                       PlayerTransferFence fence) {
                        activated.set(true);
                        return true;
                    }

                    @Override
                    public void deactivate(PlayerHandoff ignored, PlayerTransferFence fence) {
                    }
                }, () -> 102L);

        assertFalse(destination.restoreAndActivate(handoff, claim.fencingToken()));
        assertFalse(activated.get());
    }

    @Test
    void staleFenceCannotActivateOrDeactivateAfterClaimWasReclaimed() throws Exception {
        PlayerStateCheckpointService checkpoints = checkpoints();
        checkpoints.checkpoint(bundle("transfer-destination-xuid", 4.0f), 100L);
        InMemoryPlayerTransferLedger store = new InMemoryPlayerTransferLedger();
        DurableHandoffCoordinator ledger = new DurableHandoffCoordinator(new PlayerTransferCoordinator(store));
        PlayerHandoff handoff = handoff();
        ledger.prepare(handoff, "transfer-destination-xuid", 0L, 100L);
        PlayerTransferRecord firstClaim = ledger.claim(
                handoff.transferId(), "target-node", 101L, 10L).orElseThrow();
        AtomicLong now = new AtomicLong(112L);
        PlayerTransferFence staleFence = new PlayerTransferFence(ledger, firstClaim, now::get);
        PlayerTransferRecord replacement = ledger.claim(
                handoff.transferId(), "target-node", 112L, 10_000L).orElseThrow();
        assertTrue(replacement.fencingToken() > firstClaim.fencingToken());
        assertThrows(IOException.class, staleFence::assertCurrent);

        AtomicBoolean activated = new AtomicBoolean();
        AtomicBoolean deactivated = new AtomicBoolean();
        CheckpointedPlayerTransferDestination destination = new CheckpointedPlayerTransferDestination(
                ledger, checkpoints, "target-node",
                new CheckpointedPlayerTransferDestination.Activator() {
                    @Override
                    public boolean restoreAndActivate(PlayerHandoff ignored,
                                                       PlayerStateBundle restored,
                                                       PlayerTransferFence fence) {
                        activated.set(true);
                        return true;
                    }

                    @Override
                    public void deactivate(PlayerHandoff ignored, PlayerTransferFence fence) {
                        deactivated.set(true);
                    }
                }, now::get);

        assertFalse(destination.restoreAndActivate(handoff, firstClaim.fencingToken()));
        destination.deactivateIfOwned(handoff, firstClaim.fencingToken());
        assertFalse(activated.get());
        assertFalse(deactivated.get());
    }

    private PlayerStateCheckpointService checkpoints() {
        return new PlayerStateCheckpointService(
                new FilePlayerStateStore(temporaryDirectory), new PlayerStateBundleCodec());
    }

    private static PlayerStateBundle bundle(String xuid, float x) {
        PlayerData profile = new PlayerData(xuid, "TransferPlayer", "world",
                x, 65.0f, 0.0f, 0.0f, 0.0f, 100L, PlayerVitals.DEFAULT, 0, 0);
        return PlayerStateBundle.capture(profile, new PlayerInventory(),
                new ContainerInventory(PlayerStateBundle.CRAFTING_UI_SLOT_COUNT), new PlayerEquipment());
    }

    private static PlayerHandoff handoff() {
        return new PlayerHandoff(UUID.randomUUID(), 7L, "source-node", "target-node", new byte[0]);
    }
}
