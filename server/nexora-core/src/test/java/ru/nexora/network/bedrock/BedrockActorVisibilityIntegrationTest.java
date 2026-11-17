package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import ru.nexora.network.nethernet.NetherNetClientIdentity;
import ru.nexora.network.nethernet.NetherNetMessageFramer;
import ru.nexora.inventory.ItemStack;
import ru.nexora.world.WorldItemEntity;
import ru.nexora.world.FlatWorldGenerator;
import ru.nexora.world.World;
import ru.nexora.world.WorldLivingEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BedrockActorVisibilityIntegrationTest {
    @Test
    void mobsAreSpawnedRemovedAndRespawnedAsTheyCrossChunkInterestBoundaries() throws Exception {
        List<Outbound> outbound = new ArrayList<>();
        World world = new World("actor-visibility-test", 0L, new FlatWorldGenerator());
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                world, (networkId, label, frame) -> outbound.add(new Outbound(networkId, frame)),
                event -> { });

        try {
            manager.onIdentity("viewer", new NetherNetClientIdentity(
                    "viewer-token", new byte[] {1, 2, 3}, "viewer-xuid",
                    "Viewer", "viewer-playfab", "20CA2", null));
            Object session = session(manager, "viewer");
            set(session, "clientInitialized", true);
            set(session, "compressionNegotiated", true);
            sentChunks(session).add(chunkKey(0, 0));

            WorldLivingEntity mob = world.livingEntities().spawn(
                    "minecraft:zombie", 20.0f, 1.0, 65.0, 1.0);
            Method sync = BedrockSessionManager.class.getDeclaredMethod(
                    "syncVisibleWorldActors", String.class, session.getClass());
            sync.setAccessible(true);
            sync.invoke(manager, "viewer", session);

            assertTrue(visibleMobs(session).contains(mob.runtimeId()));
            assertEquals(BedrockAddActorCodec.ADD_ACTOR_PACKET_ID,
                    packetsFor(outbound, "viewer").getLast().packetId());

            world.livingEntities().move(mob.runtimeId(), 33.0, 65.0, 1.0);
            Method broadcast = BedrockSessionManager.class.getDeclaredMethod(
                    "broadcastLivingActorPacket", BedrockPacket.class);
            broadcast.setAccessible(true);
            broadcast.invoke(manager, BedrockMoveActorAbsoluteCodec.create(
                    mob.runtimeId(), 33.0, 65.0, 1.0, 0, 0, 0, true));

            assertFalse(visibleMobs(session).contains(mob.runtimeId()));
            assertEquals(BedrockAddItemActorCodec.REMOVE_ACTOR_PACKET_ID,
                    packetsFor(outbound, "viewer").getLast().packetId());

            sentChunks(session).add(chunkKey(2, 0));
            sync.invoke(manager, "viewer", session);
            assertTrue(visibleMobs(session).contains(mob.runtimeId()));
            assertEquals(BedrockAddActorCodec.ADD_ACTOR_PACKET_ID,
                    packetsFor(outbound, "viewer").getLast().packetId());
        } finally {
            manager.close();
        }
    }

    @Test
    void droppedItemsAreAddedAndRemovedAsTheyCrossChunkInterestBoundaries() throws Exception {
        List<Outbound> outbound = new ArrayList<>();
        World world = new World("item-visibility-test", 0L, new FlatWorldGenerator());
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                world, (networkId, label, frame) -> outbound.add(new Outbound(networkId, frame)),
                event -> { });
        manager.setItemRegistry(new BedrockItemRegistry(List.of(new BedrockItemRegistry.Entry(
                "minecraft:stone",
                new BedrockInventoryContentCodec.ItemDefinition(1, 0, 0, new byte[0])))));

        try {
            manager.onIdentity("viewer", new NetherNetClientIdentity(
                    "viewer-token", new byte[] {1, 2, 3}, "viewer-xuid",
                    "Viewer", "viewer-playfab", "20CA2", null));
            Object session = session(manager, "viewer");
            set(session, "clientInitialized", true);
            set(session, "compressionNegotiated", true);
            sentChunks(session).add(chunkKey(0, 0));

            WorldItemEntity item = world.itemEntities().spawn(
                    new ItemStack("minecraft:stone", 1, 64),
                    1.0, 65.0, 1.0, 0.0, 0.0, 0.0, 0L, 0L, null);
            Method sync = BedrockSessionManager.class.getDeclaredMethod(
                    "syncVisibleWorldActors", String.class, session.getClass());
            sync.setAccessible(true);
            sync.invoke(manager, "viewer", session);

            assertTrue(visibleItems(session).contains(item.runtimeId()));
            assertEquals(BedrockAddItemActorCodec.ADD_ITEM_ACTOR_PACKET_ID,
                    packetsFor(outbound, "viewer").getLast().packetId());

            WorldItemEntity moved = new WorldItemEntity(item.uniqueId(), item.runtimeId(), item.stack(),
                    33.0, item.y(), item.z(), 0.0, 0.0, 0.0,
                    item.pickupAvailableAtMillis(), item.ownerXuid());
            Method broadcast = BedrockSessionManager.class.getDeclaredMethod(
                    "broadcastItemActor", WorldItemEntity.class, BedrockPacket.class);
            broadcast.setAccessible(true);
            broadcast.invoke(manager, moved, BedrockItemActorCodec.createMove(moved, true, 1L));

            assertFalse(visibleItems(session).contains(item.runtimeId()));
            assertEquals(BedrockAddItemActorCodec.REMOVE_ACTOR_PACKET_ID,
                    packetsFor(outbound, "viewer").getLast().packetId());

            sentChunks(session).add(chunkKey(2, 0));
            broadcast.invoke(manager, moved, BedrockItemActorCodec.createMove(moved, true, 2L));
            assertTrue(visibleItems(session).contains(item.runtimeId()));
            assertEquals(BedrockAddItemActorCodec.ADD_ITEM_ACTOR_PACKET_ID,
                    packetsFor(outbound, "viewer").getLast().packetId());
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

    @SuppressWarnings("unchecked")
    private static Set<Long> sentChunks(Object session) throws Exception {
        Field field = session.getClass().getDeclaredField("sentChunkKeys");
        field.setAccessible(true);
        return (Set<Long>) field.get(session);
    }

    @SuppressWarnings("unchecked")
    private static Set<Long> visibleMobs(Object session) throws Exception {
        Field field = session.getClass().getDeclaredField("visibleLivingEntityIds");
        field.setAccessible(true);
        return (Set<Long>) field.get(session);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static Set<Long> visibleItems(Object session) throws Exception {
        Field field = session.getClass().getDeclaredField("visibleItemEntityIds");
        field.setAccessible(true);
        return (Set<Long>) field.get(session);
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffff_ffffL);
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
