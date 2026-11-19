package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nexora.inventory.ContainerInventory;
import ru.nexora.inventory.PlayerEquipment;
import ru.nexora.inventory.PlayerInventory;
import ru.nexora.network.DurableHandoffCoordinator;
import ru.nexora.network.InMemoryPlayerTransferLedger;
import ru.nexora.network.PlayerHandoff;
import ru.nexora.network.PlayerTransferCoordinator;
import ru.nexora.network.PlayerTransferFence;
import ru.nexora.persistence.FilePlayerStateStore;
import ru.nexora.persistence.PlayerStateBundle;
import ru.nexora.persistence.PlayerStateBundleCodec;
import ru.nexora.persistence.PlayerStateCheckpointService;
import ru.nexora.world.FlatWorldGenerator;
import ru.nexora.world.PlayerData;
import ru.nexora.world.PlayerVitals;
import ru.nexora.world.World;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class BedrockPendingTransferActivatorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reservesExactClaimForNextAuthenticatedLoginAndCancelsOnlyMatchingFence() throws Exception {
        String xuid = "bedrock-transfer-xuid";
        PlayerStateCheckpointService checkpoints = new PlayerStateCheckpointService(
                new FilePlayerStateStore(temporaryDirectory), new PlayerStateBundleCodec());
        PlayerStateBundle bundle = PlayerStateBundle.capture(
                new PlayerData(xuid, "TransferPlayer", "world", 3.0f, 65.0f, 4.0f,
                        0.0f, 0.0f, 100L, PlayerVitals.DEFAULT, 0, 0),
                new PlayerInventory(),
                new ContainerInventory(PlayerStateBundle.CRAFTING_UI_SLOT_COUNT),
                new PlayerEquipment());
        checkpoints.checkpoint(bundle, 100L);

        DurableHandoffCoordinator ledger = new DurableHandoffCoordinator(
                new PlayerTransferCoordinator(new InMemoryPlayerTransferLedger()));
        PlayerHandoff handoff = new PlayerHandoff(UUID.randomUUID(), 1L,
                "source-node", "target-node", new byte[0]);
        ledger.prepare(handoff, xuid, checkpoints.latestRevision(xuid), 101L);
        var claim = ledger.claim(handoff.transferId(), "target-node", 102L, 10_000L).orElseThrow();
        AtomicLong now = new AtomicLong(103L);
        PlayerTransferFence fence = new PlayerTransferFence(ledger, claim, now::get);

        BedrockSessionManager sessions = new BedrockSessionManager(
                1, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new World("NEXORA Test", 0L, new FlatWorldGenerator()),
                null, null, null, checkpoints,
                (networkId, channel, frame) -> { }, event -> { });
        try {
            BedrockPendingTransferActivator activator = new BedrockPendingTransferActivator(sessions);
            assertTrue(activator.restoreAndActivate(handoff, bundle, fence));
            assertTrue(sessions.deactivateTransferRestore(xuid, handoff.transferId(), claim.fencingToken()));
            assertFalse(sessions.deactivateTransferRestore(xuid, handoff.transferId(), claim.fencingToken()));
        } finally {
            sessions.close();
        }
    }
}
