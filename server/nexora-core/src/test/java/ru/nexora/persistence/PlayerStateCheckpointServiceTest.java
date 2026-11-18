package ru.nexora.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nexora.inventory.ContainerInventory;
import ru.nexora.inventory.PlayerEquipment;
import ru.nexora.inventory.PlayerInventory;
import ru.nexora.world.PlayerData;
import ru.nexora.world.PlayerVitals;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PlayerStateCheckpointServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void checkpointsAndRestoresCanonicalPlayerStateAcrossStoreInstances() throws IOException {
        PlayerData profile = new PlayerData("xuid-checkpoint", "Player", "world",
                1, 65, 2, 0, 0, 10L, PlayerVitals.DEFAULT, 42, 250);
        PlayerStateBundle bundle = PlayerStateBundle.capture(profile, new PlayerInventory(),
                new ContainerInventory(PlayerStateBundle.CRAFTING_UI_SLOT_COUNT), new PlayerEquipment());

        PlayerStateCheckpointService first = new PlayerStateCheckpointService(
                new FilePlayerStateStore(temporaryDirectory), new PlayerStateBundleCodec());
        assertEquals(PlayerStateStore.SaveResult.SAVED, first.checkpoint(bundle, 100L));
        assertEquals(PlayerStateStore.SaveResult.ALREADY_CURRENT, first.checkpoint(bundle, 200L));

        PlayerStateCheckpointService afterRestart = new PlayerStateCheckpointService(
                new FilePlayerStateStore(temporaryDirectory), new PlayerStateBundleCodec());
        PlayerStateBundle loaded = afterRestart.load("xuid-checkpoint").orElseThrow();
        assertEquals(bundle, loaded);
        assertEquals(1L, new FilePlayerStateStore(temporaryDirectory)
                .loadLatest("xuid-checkpoint").orElseThrow().revision());
    }
}
