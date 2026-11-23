package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nexora.inventory.ContainerInventory;
import ru.nexora.inventory.ContainerInventoryStorage;
import ru.nexora.inventory.InventoryTransactionService;
import ru.nexora.inventory.ItemStack;
import ru.nexora.network.nethernet.NetherNetClientIdentity;
import ru.nexora.network.nethernet.NetherNetMessageFramer;
import ru.nexora.world.FlatWorldGenerator;
import ru.nexora.world.World;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class BedrockContainerViewerSyncIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void chestTransactionsSynchronizeOtherViewersButNotUnrelatedWindows() throws Exception {
        List<Outbound> outbound = new ArrayList<>();
        World world = new World("shared-chest-test", 0L, new FlatWorldGenerator(),
                0, 65, 0, null, null,
                new ContainerInventoryStorage(temporaryDirectory, "shared-chest-test"));
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                world, (networkId, label, frame) -> outbound.add(new Outbound(networkId, frame)),
                event -> { });
        manager.setItemRegistry(BedrockVanillaItemCatalog.createRegistry());

        try {
            manager.onIdentity("source", identity("source"));
            manager.onIdentity("viewer", identity("viewer"));
            manager.onIdentity("outsider", identity("outsider"));
            Object source = session(manager, "source");
            Object viewer = session(manager, "viewer");
            Object outsider = session(manager, "outsider");
            for (Object state : List.of(source, viewer, outsider)) {
                set(state, "clientInitialized", true);
                set(state, "compressionNegotiated", true);
            }

            int x = 4, y = 65, z = -3;
            ContainerInventory chest = world.containers().orElseThrow().getOrCreate(x, y, z);
            ItemStack diamonds = new ItemStack("minecraft:diamond", 5, 64);
            chest.setSlot(0, diamonds);
            openChest(source, 1, x, y, z, chest);
            openChest(viewer, 8, x, y, z, chest);
            // This player has a different chest open and must not receive this update.
            openChest(outsider, 9, x + 16, y, z, new ContainerInventory());

            List<InventoryTransactionService.Action> actions = List.of(
                    new InventoryTransactionService.Action(
                            0, 0, 0, Optional.empty(), Optional.of(diamonds)),
                    new InventoryTransactionService.Action(
                            0, 1, 0, Optional.of(diamonds), Optional.empty()));
            Method transaction = BedrockSessionManager.class.getDeclaredMethod(
                    "handleOpenContainerTransaction", String.class, source.getClass(), List.class);
            transaction.setAccessible(true);
            transaction.invoke(manager, "source", source, actions);

            assertEquals(diamonds, ((ru.nexora.inventory.PlayerInventory) field(source, "inventory"))
                    .getSlot(0).orElseThrow());
            assertTrue(chest.getSlot(0).isEmpty());

            List<BedrockPacket> viewerPackets = packetsFor(outbound, "viewer");
            assertEquals(List.of(BedrockInventoryContentCodec.INVENTORY_CONTENT_PACKET_ID),
                    viewerPackets.stream().map(BedrockPacket::packetId).toList());
            assertEquals(8, Byte.toUnsignedInt(viewerPackets.getFirst().payload()[0]),
                    "the update must use the receiving player's own window ID");
            assertTrue(packetsFor(outbound, "outsider").isEmpty(),
                    "players with another chest open must not receive this inventory");
        } finally {
            manager.close();
        }
    }

    private static void openChest(
            Object state, int windowId, int x, int y, int z, ContainerInventory inventory
    ) throws Exception {
        set(state, "openContainerWindowId", windowId);
        set(state, "openContainerType", BedrockContainerPacketCodec.CONTAINER_TYPE);
        set(state, "openContainerIsCraftingTable", false);
        set(state, "openContainerIsFurnace", false);
        set(state, "openContainerX", x);
        set(state, "openContainerY", y);
        set(state, "openContainerZ", z);
        set(state, "openContainerInventory", inventory);
    }

    @Test
    void furnaceTransactionsSynchronizeOtherViewersUsingTheirOwnWindowIds() throws Exception {
        List<Outbound> outbound = new ArrayList<>();
        World world = new World("shared-furnace-test", 0L, new FlatWorldGenerator(),
                0, 65, 0, null, null,
                new ContainerInventoryStorage(temporaryDirectory, "shared-furnace-test"));
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                world, (networkId, label, frame) -> outbound.add(new Outbound(networkId, frame)),
                event -> { });
        manager.setItemRegistry(BedrockVanillaItemCatalog.createRegistry());

        try {
            manager.onIdentity("source-furnace", identity("source-furnace"));
            manager.onIdentity("viewer-furnace", identity("viewer-furnace"));
            manager.onIdentity("outsider-furnace", identity("outsider-furnace"));
            Object source = session(manager, "source-furnace");
            Object viewer = session(manager, "viewer-furnace");
            Object outsider = session(manager, "outsider-furnace");
            for (Object state : List.of(source, viewer, outsider)) {
                set(state, "clientInitialized", true);
                set(state, "compressionNegotiated", true);
            }

            int x = 8, y = 65, z = 2;
            ContainerInventory furnace = new ContainerInventory(3);
            furnace.setSlot(0, new ItemStack("minecraft:raw_iron", 3, 64));
            openFurnace(source, 2, x, y, z, furnace);
            openFurnace(viewer, 11, x, y, z, furnace);
            openFurnace(outsider, 12, x + 16, y, z, new ContainerInventory(3));

            Method broadcast = BedrockSessionManager.class.getDeclaredMethod(
                    "broadcastContainerInventoryToViewers", String.class,
                    source.getClass(), ContainerInventory.class);
            broadcast.setAccessible(true);
            broadcast.invoke(manager, "source-furnace", source, furnace);

            List<BedrockPacket> viewerPackets = packetsFor(outbound, "viewer-furnace");
            assertEquals(List.of(BedrockInventoryContentCodec.INVENTORY_CONTENT_PACKET_ID),
                    viewerPackets.stream().map(BedrockPacket::packetId).toList());
            assertEquals(11, Byte.toUnsignedInt(viewerPackets.getFirst().payload()[0]),
                    "furnace updates must use the receiving player's own window ID");
            assertTrue(packetsFor(outbound, "outsider-furnace").isEmpty());
        } finally {
            manager.close();
        }
    }

    @Test
    void furnaceTickUpdatesEveryViewerAndUsesEachWindowId() throws Exception {
        List<Outbound> outbound = new ArrayList<>();
        World world = new World("furnace-tick-viewers-test", 0L, new FlatWorldGenerator(),
                0, 65, 0, null, null,
                new ContainerInventoryStorage(temporaryDirectory, "furnace-tick-viewers-test"));
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                world, (networkId, label, frame) -> outbound.add(new Outbound(networkId, frame)),
                event -> { });
        manager.setItemRegistry(BedrockVanillaItemCatalog.createRegistry());

        try {
            manager.onIdentity("tick-source", identity("tick-source"));
            manager.onIdentity("tick-viewer-a", identity("tick-viewer-a"));
            manager.onIdentity("tick-viewer-b", identity("tick-viewer-b"));
            manager.onIdentity("tick-outsider", identity("tick-outsider"));
            Object source = session(manager, "tick-source");
            Object viewerA = session(manager, "tick-viewer-a");
            Object viewerB = session(manager, "tick-viewer-b");
            Object outsider = session(manager, "tick-outsider");
            for (Object state : List.of(source, viewerA, viewerB, outsider)) {
                set(state, "clientInitialized", true);
                set(state, "compressionNegotiated", true);
            }

            int x = -7, y = 65, z = 13;
            ContainerInventory furnace = new ContainerInventory(3);
            furnace.setSlot(2, new ItemStack("minecraft:iron_ingot", 1, 64));
            openFurnace(source, 3, x, y, z, furnace);
            openFurnace(viewerA, 7, x, y, z, furnace);
            openFurnace(viewerB, 14, x, y, z, furnace);
            openFurnace(outsider, 15, x, y, z + 1, new ContainerInventory(3));

            Method update = BedrockSessionManager.class.getDeclaredMethod(
                    "handleFurnaceUpdate", ru.nexora.world.WorldFurnaceManager.FurnaceUpdate.class);
            update.setAccessible(true);
            update.invoke(manager, new ru.nexora.world.WorldFurnaceManager.FurnaceUpdate(
                    x, y, z, furnace,
                    new ru.nexora.inventory.FurnaceService.TickResult(120, 40, 20, true, 1600)));

            for (var expected : List.of(
                    new java.util.AbstractMap.SimpleEntry<>("tick-viewer-a", 7),
                    new java.util.AbstractMap.SimpleEntry<>("tick-viewer-b", 14))) {
                List<BedrockPacket> packets = packetsFor(outbound, expected.getKey());
                assertEquals(4, packets.size(),
                        "each viewer receives inventory plus burn/cook progress updates");
                assertEquals(BedrockInventoryContentCodec.INVENTORY_CONTENT_PACKET_ID,
                        packets.getFirst().packetId());
                assertEquals(expected.getValue(), Byte.toUnsignedInt(packets.getFirst().payload()[0]));
                assertTrue(packets.subList(1, 4).stream()
                        .allMatch(packet -> packet.packetId() == BedrockContainerSetDataCodec.PACKET_ID));
            }
            assertTrue(packetsFor(outbound, "tick-outsider").isEmpty(),
                    "a player with a different furnace open must not receive this update");
        } finally {
            manager.close();
        }
    }

    private static void openFurnace(
            Object state, int windowId, int x, int y, int z, ContainerInventory inventory
    ) throws Exception {
        set(state, "openContainerWindowId", windowId);
        set(state, "openContainerType", BedrockContainerPacketCodec.CONTAINER_TYPE);
        set(state, "openContainerIsCraftingTable", false);
        set(state, "openContainerIsFurnace", true);
        set(state, "openContainerX", x);
        set(state, "openContainerY", y);
        set(state, "openContainerZ", z);
        set(state, "openContainerInventory", inventory);
    }

    private static NetherNetClientIdentity identity(String id) {
        return new NetherNetClientIdentity(
                id + "-token", new byte[] {1, 2, 3}, id + "-xuid",
                id, id + "-playfab", "20CA2", null);
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

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static List<BedrockPacket> packetsFor(List<Outbound> frames, String recipient) {
        List<BedrockPacket> packets = new ArrayList<>();
        NetherNetMessageFramer.Reassembler reassembler = new NetherNetMessageFramer.Reassembler();
        for (Outbound outbound : frames) {
            if (!recipient.equals(outbound.recipient())) continue;
            reassembler.accept(outbound.frame(), true).ifPresent(
                    message -> packets.addAll(BedrockBatchCodec.decode(message, true)));
        }
        return packets;
    }

    private record Outbound(String recipient, byte[] frame) {
    }
}
