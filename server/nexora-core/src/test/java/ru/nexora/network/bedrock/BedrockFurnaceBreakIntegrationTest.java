package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nexora.inventory.ContainerInventory;
import ru.nexora.inventory.ContainerInventoryStorage;
import ru.nexora.inventory.FurnaceService;
import ru.nexora.inventory.FurnaceStateStorage;
import ru.nexora.inventory.ItemStack;
import ru.nexora.network.nethernet.NetherNetClientIdentity;
import ru.nexora.world.BlockType;
import ru.nexora.world.FlatWorldGenerator;
import ru.nexora.world.World;
import ru.nexora.world.WorldItemEntity;
import ru.nexora.world.WorldItemEntityStorage;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class BedrockFurnaceBreakIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void breakingFurnaceSpillsEverySlotAndRemovesItsPersistentState() throws Exception {
        AtomicLong clock = new AtomicLong(10L);
        World world = createWorld("furnace-spill-test", "furnace-spill");
        int x = 2, y = 65, z = 0;
        world.setBlock(x, y, z, BlockType.FURNACE);
        var furnaces = world.furnaces().orElseThrow();
        ContainerInventory furnace = furnaces.getOrCreate(x, y, z, FurnaceService.Kind.FURNACE);
        ItemStack input = new ItemStack("minecraft:raw_iron", 13, 64);
        ItemStack fuel = new ItemStack("minecraft:coal", 21, 64);
        ItemStack output = new ItemStack("minecraft:iron_ingot", 7, 64);
        furnace.setSlot(0, input);
        furnace.setSlot(1, fuel);
        furnace.setSlot(2, output);
        furnaces.restoreState(x, y, z,
                new FurnaceService.FurnaceStateSnapshot(900, 42, 1600, 0));
        furnaces.save(x, y, z);

        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                world, (networkId, label, frame) -> { }, event -> { }, clock::get);
        manager.setItemRegistry(BedrockVanillaItemCatalog.createRegistry());

        try {
            prepareBreaker(manager, "furnace-breaker", "furnace-breaker-xuid");
            breakBlock(manager, "furnace-breaker", x, y, z, clock);

            assertEquals(BlockType.AIR, world.getBlock(x, y, z));
            List<WorldItemEntity> drops = world.itemEntities().snapshot();
            // Three furnace slots plus the furnace block itself are dropped.
            assertEquals(4, drops.size());
            assertTrue(drops.stream().anyMatch(item -> item.stack().equals(input)));
            assertTrue(drops.stream().anyMatch(item -> item.stack().equals(fuel)));
            assertTrue(drops.stream().anyMatch(item -> item.stack().equals(output)));

            ContainerInventory recreated = furnaces.getOrCreate(x, y, z, FurnaceService.Kind.FURNACE);
            assertTrue(recreated.snapshot().stream().noneMatch(Optional::isPresent));
            assertEquals(0, furnaces.snapshotState(x, y, z).burnTicksRemaining());
            assertEquals(0, furnaces.snapshotState(x, y, z).cookProgress());
        } finally {
            manager.close();
        }
    }

    @Test
    void unsupportedFurnaceItemRollsBackAllDropsInventoryAndProcessingState() throws Exception {
        AtomicLong clock = new AtomicLong(10L);
        World world = createWorld("furnace-rollback-test", "furnace-rollback");
        int x = 3, y = 65, z = 0;
        world.setBlock(x, y, z, BlockType.FURNACE);
        var furnaces = world.furnaces().orElseThrow();
        ContainerInventory furnace = furnaces.getOrCreate(x, y, z, FurnaceService.Kind.FURNACE);
        ItemStack known = new ItemStack("minecraft:diamond", 7, 64);
        ItemStack unknown = new ItemStack("test:unregistered_item", 3, 64);
        furnace.setSlot(0, known);
        furnace.setSlot(2, unknown);
        FurnaceService.FurnaceStateSnapshot originalState =
                new FurnaceService.FurnaceStateSnapshot(700, 55, 1600, 0);
        furnaces.restoreState(x, y, z, originalState);
        furnaces.save(x, y, z);

        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                world, (networkId, label, frame) -> { }, event -> { }, clock::get);
        manager.setItemRegistry(BedrockVanillaItemCatalog.createRegistry());

        try {
            prepareBreaker(manager, "furnace-rollback", "furnace-rollback-xuid");
            breakBlock(manager, "furnace-rollback", x, y, z, clock);

            assertEquals(BlockType.FURNACE, world.getBlock(x, y, z));
            List<Optional<ItemStack>> remaining = furnaces.getOrCreate(
                    x, y, z, FurnaceService.Kind.FURNACE).snapshot();
            assertEquals(known, remaining.get(0).orElseThrow());
            assertEquals(unknown, remaining.get(2).orElseThrow());
            assertEquals(originalState, furnaces.snapshotState(x, y, z));
            assertTrue(world.itemEntities().snapshot().isEmpty(),
                    "failed spill must remove any earlier item actors");
        } finally {
            manager.close();
        }
    }

    @Test
    void furnaceWithUnclaimedExperienceCannotBeDestroyedOrLoseItsContents() throws Exception {
        AtomicLong clock = new AtomicLong(10L);
        World world = createWorld("furnace-xp-test", "furnace-xp");
        int x = 4, y = 65, z = 0;
        world.setBlock(x, y, z, BlockType.FURNACE);
        var furnaces = world.furnaces().orElseThrow();
        ContainerInventory furnace = furnaces.getOrCreate(x, y, z, FurnaceService.Kind.FURNACE);
        ItemStack output = new ItemStack("minecraft:iron_ingot", 4, 64);
        furnace.setSlot(2, output);
        furnaces.restoreState(x, y, z,
                new FurnaceService.FurnaceStateSnapshot(0, 0, 0, 700));
        furnaces.save(x, y, z);

        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                world, (networkId, label, frame) -> { }, event -> { }, clock::get);
        manager.setItemRegistry(BedrockVanillaItemCatalog.createRegistry());

        try {
            prepareBreaker(manager, "furnace-xp-breaker", "furnace-xp-breaker-xuid");
            breakBlock(manager, "furnace-xp-breaker", x, y, z, clock);

            assertEquals(BlockType.FURNACE, world.getBlock(x, y, z));
            assertEquals(output, furnaces.getOrCreate(
                    x, y, z, FurnaceService.Kind.FURNACE).getSlot(2).orElseThrow());
            assertEquals(700, furnaces.snapshotState(x, y, z).pendingExperienceMilli());
            assertTrue(world.itemEntities().snapshot().isEmpty());
        } finally {
            manager.close();
        }
    }

    private World createWorld(String name, String storageName) throws Exception {
        Path root = temporaryDirectory.resolve(storageName);
        return new World(name, 0L, new FlatWorldGenerator(), 0, 65, 0,
                null,
                new WorldItemEntityStorage(root.resolve("items"), "NEXORA"),
                new ContainerInventoryStorage(root.resolve("containers"), "NEXORA"),
                new FurnaceStateStorage(root.resolve("furnaces"), "NEXORA"));
    }

    private static void prepareBreaker(
            BedrockSessionManager manager, String networkId, String xuid
    ) throws Exception {
        manager.onIdentity(networkId, new NetherNetClientIdentity(
                networkId + "-token", new byte[] {1, 2, 3}, xuid,
                "Breaker", networkId + "-playfab", "20CA2", null));
        Object state = session(manager, networkId);
        set(state, "clientInitialized", true);
        set(state, "compressionNegotiated", true);
        set(state, "positionX", 0.5f);
        set(state, "positionY", 65.0f);
        set(state, "positionZ", 0.5f);
    }

    private static void breakBlock(
            BedrockSessionManager manager, String networkId, int x, int y, int z,
            AtomicLong clock
    ) throws Exception {
        Object state = session(manager, networkId);
        var start = new BedrockPlayerAuthInputCodec.BlockAction(0, x, y, z, 1);
        var stop = new BedrockPlayerAuthInputCodec.BlockAction(2, x, y, z, 1);
        var handler = BedrockSessionManager.class.getDeclaredMethod(
                "handleBlockAction", String.class, state.getClass(),
                BedrockPlayerAuthInputCodec.BlockAction.class);
        handler.setAccessible(true);
        handler.invoke(manager, networkId, state, start);
        clock.set(3_000_000_000L);
        handler.invoke(manager, networkId, state, stop);
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
