package ru.nexora.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nexora.inventory.ContainerInventoryStorage;
import ru.nexora.inventory.FurnaceStateStorage;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorldStorageNameTest {
    @TempDir
    Path temp;

    @Test
    void productionWorldNameWithSpacesIsAcceptedByEveryWorldStorage() throws Exception {
        assertDoesNotThrow(() -> new WorldStorage(temp, "NEXORA World"));
        assertDoesNotThrow(() -> new WorldItemEntityStorage(temp, "NEXORA World"));
        assertDoesNotThrow(() -> new WorldLivingEntityStorage(temp, "NEXORA World"));
        assertDoesNotThrow(() -> new ContainerInventoryStorage(temp, "NEXORA World"));
        assertDoesNotThrow(() -> new FurnaceStateStorage(temp, "NEXORA World"));
    }
}
