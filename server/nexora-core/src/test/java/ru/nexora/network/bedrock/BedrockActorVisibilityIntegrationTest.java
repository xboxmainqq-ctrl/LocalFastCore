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
            assertEquals(List.of(
                    BedrockAddActorCodec.ADD_ACTOR_PACKET_ID,
                    BedrockAddItemActorCodec.REMOVE_ACTOR_PACKET_ID,
                    BedrockAddActorCodec.ADD_ACTOR_PACKET_ID),
                    packetsFor(outbound, "viewer").stream().map(BedrockPacket::packetId).toList());
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
            Field itemIndexField = BedrockSessionManager.class.getDeclaredField("itemActorReplicationIndex");
            itemIndexField.setAccessible(true);
            ((ActorReplicationIndex) itemIndexField.get(manager))
                    .replaceChunkView("viewer", sentChunks(session));
            broadcast.invoke(manager, moved, BedrockItemActorCodec.createMove(moved, true, 2L));
            assertTrue(visibleItems(session).contains(item.runtimeId()));
            assertEquals(BedrockAddItemActorCodec.ADD_ITEM_ACTOR_PACKET_ID,
                    packetsFor(outbound, "viewer").getLast().packetId());
        } finally {
            manager.close();
        }
    }

    @Test
    void playerVitalsUpdatesAreFannedOutOnlyToClientsTrackingThatPlayer() throws Exception {
        List<Outbound> outbound = new ArrayList<>();
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new World("player-vitals-visibility-test", 0L, new FlatWorldGenerator()),
                (networkId, label, frame) -> outbound.add(new Outbound(networkId, frame)),
                event -> { });

        try {
            for (String id : List.of("source", "viewer", "outsider")) {
                manager.onIdentity(id, new NetherNetClientIdentity(
                        id + "-token", new byte[] {1, 2, 3}, id + "-xuid",
                        id, id + "-playfab", "20CA2", null));
                Object state = session(manager, id);
                set(state, "clientInitialized", true);
            }

            Object source = session(manager, "source");
            Object viewer = session(manager, "viewer");
            set(source, "entityId", 700L);
            set(source, "vitals", ru.nexora.world.PlayerVitals.DEFAULT.damage(5.0f));
            visiblePlayers(viewer).add(700L);

            Field indexField = BedrockSessionManager.class.getDeclaredField("playerActorReplicationIndex");
            indexField.setAccessible(true);
            ((ActorReplicationIndex) indexField.get(manager)).addActorViewer(700L, "viewer");

            Method broadcast = BedrockSessionManager.class.getDeclaredMethod(
                    "broadcastPlayerVitals", String.class, source.getClass(), boolean.class, long.class);
            broadcast.setAccessible(true);
            broadcast.invoke(manager, "source", source, true, 12L);

            assertEquals(List.of(
                    BedrockVitalsCodec.SET_HEALTH_PACKET_ID,
                    BedrockVitalsCodec.UPDATE_ATTRIBUTES_PACKET_ID),
                    packetsFor(outbound, "source").stream().map(BedrockPacket::packetId).toList());
            assertEquals(List.of(
                    BedrockVitalsCodec.SET_HEALTH_PACKET_ID,
                    BedrockVitalsCodec.UPDATE_ATTRIBUTES_PACKET_ID),
                    packetsFor(outbound, "viewer").stream().map(BedrockPacket::packetId).toList());
            assertTrue(packetsFor(outbound, "outsider").isEmpty());

            outbound.clear();
            broadcast.invoke(manager, "source", source, false, 13L);
            assertEquals(List.of(BedrockVitalsCodec.UPDATE_ATTRIBUTES_PACKET_ID),
                    packetsFor(outbound, "source").stream().map(BedrockPacket::packetId).toList());
            assertEquals(List.of(BedrockVitalsCodec.UPDATE_ATTRIBUTES_PACKET_ID),
                    packetsFor(outbound, "viewer").stream().map(BedrockPacket::packetId).toList());
            assertTrue(packetsFor(outbound, "outsider").isEmpty());

            // Respawn sends the local snapshot separately; this observer-only path
            // refreshes clients that continued tracking the same player entity.
            outbound.clear();
            Method viewersOnly = BedrockSessionManager.class.getDeclaredMethod(
                    "broadcastPlayerVitalsToViewers", source.getClass(), List.class);
            viewersOnly.setAccessible(true);
            viewersOnly.invoke(manager, source, List.of(
                    BedrockVitalsCodec.createSetHealth(ru.nexora.world.PlayerVitals.DEFAULT),
                    BedrockVitalsCodec.createUpdateAttributes(700L,
                            ru.nexora.world.PlayerVitals.DEFAULT, 0, 0.0f, 14L)));
            assertTrue(packetsFor(outbound, "source").isEmpty());
            assertEquals(List.of(
                    BedrockVitalsCodec.SET_HEALTH_PACKET_ID,
                    BedrockVitalsCodec.UPDATE_ATTRIBUTES_PACKET_ID),
                    packetsFor(outbound, "viewer").stream().map(BedrockPacket::packetId).toList());
            assertTrue(packetsFor(outbound, "outsider").isEmpty());
        } finally {
            manager.close();
        }
    }

    @Test
    void mobMovementIsFannedOutOnlyToViewersOfItsCurrentChunk() throws Exception {
        List<Outbound> outbound = new ArrayList<>();
        World world = new World("actor-multi-viewer-test", 0L, new FlatWorldGenerator());
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                world, (networkId, label, frame) -> outbound.add(new Outbound(networkId, frame)),
                event -> { });

        try {
            manager.onIdentity("west-viewer", new NetherNetClientIdentity(
                    "west-token", new byte[] {1, 2, 3}, "west-xuid",
                    "WestViewer", "west-playfab", "20CA2", null));
            manager.onIdentity("east-viewer", new NetherNetClientIdentity(
                    "east-token", new byte[] {1, 2, 3}, "east-xuid",
                    "EastViewer", "east-playfab", "20CA2", null));

            Object west = session(manager, "west-viewer");
            Object east = session(manager, "east-viewer");
            for (Object viewer : List.of(west, east)) {
                set(viewer, "clientInitialized", true);
                set(viewer, "compressionNegotiated", true);
            }
            sentChunks(west).add(chunkKey(0, 0));
            sentChunks(east).add(chunkKey(2, 0));
            Field actorIndexField = BedrockSessionManager.class.getDeclaredField("actorReplicationIndex");
            actorIndexField.setAccessible(true);
            ActorReplicationIndex actorIndex = (ActorReplicationIndex) actorIndexField.get(manager);
            actorIndex.replaceChunkView("west-viewer", sentChunks(west));
            actorIndex.replaceChunkView("east-viewer", sentChunks(east));

            WorldLivingEntity mob = world.livingEntities().spawn(
                    "minecraft:zombie", 20.0f, 1.0, 65.0, 1.0);
            Method sync = BedrockSessionManager.class.getDeclaredMethod(
                    "syncVisibleWorldActors", String.class, west.getClass());
            sync.setAccessible(true);
            sync.invoke(manager, "west-viewer", west);
            sync.invoke(manager, "east-viewer", east);

            assertTrue(visibleMobs(west).contains(mob.runtimeId()));
            assertFalse(visibleMobs(east).contains(mob.runtimeId()));
            assertEquals(List.of(BedrockAddActorCodec.ADD_ACTOR_PACKET_ID),
                    packetsFor(outbound, "west-viewer").stream().map(BedrockPacket::packetId).toList());
            assertTrue(packetsFor(outbound, "east-viewer").isEmpty());

            world.livingEntities().move(mob.runtimeId(), 33.0, 65.0, 1.0);
            Method broadcast = BedrockSessionManager.class.getDeclaredMethod(
                    "broadcastLivingActorPacket", BedrockPacket.class);
            broadcast.setAccessible(true);
            broadcast.invoke(manager, BedrockMoveActorAbsoluteCodec.create(
                    mob.runtimeId(), 33.0, 65.0, 1.0, 0, 0, 0, true));

            assertFalse(visibleMobs(west).contains(mob.runtimeId()));
            assertTrue(visibleMobs(east).contains(mob.runtimeId()));
            assertEquals(List.of(
                    BedrockAddActorCodec.ADD_ACTOR_PACKET_ID,
                    BedrockAddItemActorCodec.REMOVE_ACTOR_PACKET_ID),
                    packetsFor(outbound, "west-viewer").stream().map(BedrockPacket::packetId).toList());
            assertEquals(List.of(BedrockAddActorCodec.ADD_ACTOR_PACKET_ID),
                    packetsFor(outbound, "east-viewer").stream().map(BedrockPacket::packetId).toList());
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
    private static Set<Long> visiblePlayers(Object session) throws Exception {
        Field field = session.getClass().getDeclaredField("visiblePlayerEntityIds");
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
