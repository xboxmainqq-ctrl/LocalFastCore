package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nexora.inventory.ContainerInventory;
import ru.nexora.inventory.ContainerInventoryStorage;
import ru.nexora.inventory.ItemStack;
import ru.nexora.network.nethernet.NetherNetClientIdentity;
import ru.nexora.world.BlockType;
import ru.nexora.world.FlatWorldGenerator;
import ru.nexora.world.World;
import ru.nexora.world.WorldContainerManager;
import ru.nexora.world.WorldItemEntity;
import ru.nexora.world.WorldItemEntityStorage;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class BedrockChestBreakIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void breakingNonEmptyChestSpillsEveryStackBeforeRemovingPersistentInventory() throws Exception {
        AtomicLong clock = new AtomicLong(10L);
        World world = new World("chest-spill-test", 0L, new FlatWorldGenerator(),
                0, 65, 0, null,
                new WorldItemEntityStorage(temporaryDirectory.resolve("worlds"), "NEXORA"),
                new ContainerInventoryStorage(temporaryDirectory.resolve("worlds"), "NEXORA"));
        int x = 2, y = 65, z = 0;
        world.setBlock(x, y, z, BlockType.CHEST);
        WorldContainerManager containers = world.containers().orElseThrow();
        ContainerInventory chest = containers.getOrCreate(x, y, z);
        ItemStack diamonds = new ItemStack("minecraft:diamond", 32, 64);
        ItemStack gold = new ItemStack("minecraft:gold_ingot", 11, 64);
        chest.setSlot(2, diamonds);
        chest.setSlot(17, gold);

        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                world, (networkId, label, frame) -> { }, event -> { }, clock::get);
        manager.setItemRegistry(BedrockVanillaItemCatalog.createRegistry());

        try {
            manager.onIdentity("breaker", new NetherNetClientIdentity(
                    "breaker-token", new byte[] {1, 2, 3}, "breaker-xuid",
                    "Breaker", "breaker-playfab", "20CA2", null));
            Object state = session(manager, "breaker");
            set(state, "clientInitialized", true);
            set(state, "compressionNegotiated", true);
            set(state, "positionX", 0.5f);
            set(state, "positionY", 65.0f);
            set(state, "positionZ", 0.5f);

            BedrockPlayerAuthInputCodec.BlockAction start =
                    new BedrockPlayerAuthInputCodec.BlockAction(0, x, y, z, 1);
            BedrockPlayerAuthInputCodec.BlockAction stop =
                    new BedrockPlayerAuthInputCodec.BlockAction(2, x, y, z, 1);
            var handler = BedrockSessionManager.class.getDeclaredMethod(
                    "handleBlockAction", String.class, state.getClass(), BedrockPlayerAuthInputCodec.BlockAction.class);
            handler.setAccessible(true);
            handler.invoke(manager, "breaker", state, start);
            clock.set(3_000_000_000L);
            handler.invoke(manager, "breaker", state, stop);

            assertEquals(BlockType.AIR, world.getBlock(x, y, z));
            assertEquals(0, containers.getOrCreate(x, y, z).snapshot().stream()
                    .filter(java.util.Optional::isPresent).count(),
                    "the deleted chest must not retain orphaned contents");

            List<WorldItemEntity> drops = world.itemEntities().snapshot();
            assertTrue(drops.stream().anyMatch(item -> item.stack().equals(diamonds)));
            assertTrue(drops.stream().anyMatch(item -> item.stack().equals(gold)));
            assertEquals(2, drops.stream().filter(item ->
                    item.stack().equals(diamonds) || item.stack().equals(gold)).count());
        } finally {
            manager.close();
        }
    }

    @Test
    void breakingChestWithUnregisteredItemKeepsBlockAndAllContents() throws Exception {
        AtomicLong clock = new AtomicLong(10L);
        World world = new World("chest-rollback-test", 0L, new FlatWorldGenerator(),
                0, 65, 0, null,
                new WorldItemEntityStorage(temporaryDirectory.resolve("rollback-worlds"), "NEXORA"),
                new ContainerInventoryStorage(temporaryDirectory.resolve("rollback-worlds"), "NEXORA"));
        int x = 3, y = 65, z = 0;
        world.setBlock(x, y, z, BlockType.CHEST);
        WorldContainerManager containers = world.containers().orElseThrow();
        ContainerInventory chest = containers.getOrCreate(x, y, z);
        ItemStack known = new ItemStack("minecraft:diamond", 7, 64);
        ItemStack unknown = new ItemStack("test:unregistered_item", 3, 64);
        chest.setSlot(1, known);
        chest.setSlot(19, unknown);

        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                world, (networkId, label, frame) -> { }, event -> { }, clock::get);
        manager.setItemRegistry(BedrockVanillaItemCatalog.createRegistry());

        try {
            manager.onIdentity("breaker", new NetherNetClientIdentity(
                    "rollback-token", new byte[] {4, 5, 6}, "rollback-xuid",
                    "Rollback", "rollback-playfab", "20CA2", null));
            Object state = session(manager, "breaker");
            set(state, "clientInitialized", true);
            set(state, "compressionNegotiated", true);
            set(state, "positionX", 0.5f);
            set(state, "positionY", 65.0f);
            set(state, "positionZ", 0.5f);

            BedrockPlayerAuthInputCodec.BlockAction start =
                    new BedrockPlayerAuthInputCodec.BlockAction(0, x, y, z, 1);
            BedrockPlayerAuthInputCodec.BlockAction stop =
                    new BedrockPlayerAuthInputCodec.BlockAction(2, x, y, z, 1);
            var handler = BedrockSessionManager.class.getDeclaredMethod(
                    "handleBlockAction", String.class, state.getClass(), BedrockPlayerAuthInputCodec.BlockAction.class);
            handler.setAccessible(true);
            handler.invoke(manager, "breaker", state, start);
            clock.set(3_000_000_000L);
            handler.invoke(manager, "breaker", state, stop);

            assertEquals(BlockType.CHEST, world.getBlock(x, y, z),
                    "a chest must remain in place when any content stack cannot be represented");
            List<Optional<ItemStack>> remaining = containers.getOrCreate(x, y, z).snapshot();
            assertEquals(known, remaining.get(1).orElseThrow());
            assertEquals(unknown, remaining.get(19).orElseThrow());
            assertTrue(world.itemEntities().snapshot().isEmpty(),
                    "rollback must remove any item entities spawned before the unsupported stack was found");
        } finally {
            manager.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static Object session(BedrockSessionManager manager, String id) throws Exception {
        Field field = BedrockSessionManager.class.getDeclaredField("sessions");
        field.setAccessible(true);
        return ((Map<String, Object>) field.get(manager)).get(id);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
