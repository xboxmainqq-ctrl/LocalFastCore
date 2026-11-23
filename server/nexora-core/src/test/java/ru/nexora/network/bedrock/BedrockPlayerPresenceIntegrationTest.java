package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import ru.nexora.inventory.ItemStack;
import ru.nexora.inventory.PlayerInventory;
import ru.nexora.network.nethernet.NetherNetClientIdentity;
import ru.nexora.network.nethernet.NetherNetMessageFramer;
import ru.nexora.world.FlatWorldGenerator;
import ru.nexora.world.World;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BedrockPlayerPresenceIntegrationTest {
    @Test
    void advertisesPlayerListBeforeSpawningActorsForBothClients() throws Exception {
        List<Outbound> outbound = new ArrayList<>();
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new World("player-presence-test", 0L, new FlatWorldGenerator()),
                (networkId, label, frame) -> outbound.add(new Outbound(networkId, frame)),
                event -> { });

        try {
            manager.onIdentity("existing", identity("existing", "ExistingPlayer"));
            manager.onIdentity("joining", identity("joining", "JoiningPlayer"));

            Object existing = session(manager, "existing");
            Object joining = session(manager, "joining");
            prepareSession(existing, entry("00000000-0000-0000-0000-000000000101", 101, "ExistingPlayer"));
            prepareSession(joining, entry("00000000-0000-0000-0000-000000000102", 102, "JoiningPlayer"));
            set(existing, "playerListAdvertised", true);

            Method advertise = BedrockSessionManager.class.getDeclaredMethod(
                    "advertisePlayerList", String.class, existing.getClass());
            advertise.setAccessible(true);
            advertise.invoke(manager, "joining", joining);

            List<BedrockPacket> forJoining = packetsFor(outbound, "joining");
            List<BedrockPacket> forExisting = packetsFor(outbound, "existing");

            assertEquals(List.of(
                    BedrockPlayerListCodec.PLAYER_LIST_PACKET_ID,
                    BedrockAddPlayerCodec.ADD_PLAYER_PACKET_ID,
                    BedrockMobEquipmentCodec.MOB_EQUIPMENT_PACKET_ID,
                    BedrockVitalsCodec.SET_HEALTH_PACKET_ID,
                    BedrockVitalsCodec.UPDATE_ATTRIBUTES_PACKET_ID),
                    packetIds(forJoining));
            assertEquals(List.of(
                    BedrockPlayerListCodec.PLAYER_LIST_PACKET_ID,
                    BedrockAddPlayerCodec.ADD_PLAYER_PACKET_ID,
                    BedrockMobEquipmentCodec.MOB_EQUIPMENT_PACKET_ID,
                    BedrockVitalsCodec.SET_HEALTH_PACKET_ID,
                    BedrockVitalsCodec.UPDATE_ATTRIBUTES_PACKET_ID),
                    packetIds(forExisting));

            // The joining client receives both list entries in its snapshot.
            assertEquals(2, Byte.toUnsignedInt(forJoining.get(0).payload()[0]));
            // The existing client receives the new player's list entry before its actor.
            assertEquals(1, Byte.toUnsignedInt(forExisting.get(0).payload()[0]));
            assertTrue((boolean) field(joining, "playerListAdvertised"));
        } finally {
            manager.close();
        }
    }

    @Test
    void removesRemoteActorBeforeRemovingPlayerListEntryOnDisconnect() throws Exception {
        List<Outbound> outbound = new ArrayList<>();
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new World("player-disconnect-test", 0L, new FlatWorldGenerator()),
                (networkId, label, frame) -> outbound.add(new Outbound(networkId, frame)),
                event -> { });

        try {
            manager.onIdentity("remaining", identity("remaining", "RemainingPlayer"));
            manager.onIdentity("leaving", identity("leaving", "LeavingPlayer"));
            Object remaining = session(manager, "remaining");
            Object leaving = session(manager, "leaving");
            prepareSession(remaining, entry("00000000-0000-0000-0000-000000000201", 201, "RemainingPlayer"));
            prepareSession(leaving, entry("00000000-0000-0000-0000-000000000202", 202, "LeavingPlayer"));
            set(remaining, "playerListAdvertised", true);
            set(leaving, "playerListAdvertised", true);
            visibleActors(remaining).add(202L);

            manager.onDisconnected("leaving");

            List<BedrockPacket> packets = packetsFor(outbound, "remaining");
            assertEquals(List.of(
                    BedrockAddItemActorCodec.REMOVE_ACTOR_PACKET_ID,
                    BedrockPlayerListCodec.PLAYER_LIST_PACKET_ID),
                    packetIds(packets));
            assertEquals(1, Byte.toUnsignedInt(packets.get(1).payload()[0]));
            assertFalse((boolean) field(leaving, "playerListAdvertised"));
            assertNull(session(manager, "leaving"));
        } finally {
            manager.close();
        }
    }


    @Test
    void remotePlayerSpawnsAndDespawnsAsMovementCrossesPublishedChunkView() throws Exception {
        List<Outbound> outbound = new ArrayList<>();
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new World("player-movement-visibility-test", 0L, new FlatWorldGenerator()),
                (networkId, label, frame) -> outbound.add(new Outbound(networkId, frame)),
                event -> { });
        manager.setItemRegistry(BedrockVanillaItemCatalog.createRegistry());

        try {
            manager.onIdentity("viewer", identity("viewer", "Viewer"));
            manager.onIdentity("moving", identity("moving", "MovingPlayer"));
            Object viewer = session(manager, "viewer");
            Object moving = session(manager, "moving");
            prepareSession(viewer, entry("00000000-0000-0000-0000-000000000401", 401, "Viewer"));
            prepareSession(moving, entry("00000000-0000-0000-0000-000000000402", 402, "MovingPlayer"));
            set(viewer, "playerListAdvertised", true);
            set(moving, "playerListAdvertised", true);
            set(moving, "positionX", 1.0f);
            set(moving, "positionY", 65.0f);
            set(moving, "positionZ", 1.0f);
            PlayerInventory movingInventory = (PlayerInventory) field(moving, "inventory");
            movingInventory.setSlot(0, new ItemStack("minecraft:diamond", 3, 64));
            movingInventory.selectHotbarSlot(0);

            Field indexField = BedrockSessionManager.class.getDeclaredField("playerActorReplicationIndex");
            indexField.setAccessible(true);
            ActorReplicationIndex replicationIndex = (ActorReplicationIndex) indexField.get(manager);
            replicationIndex.replaceChunkView("viewer", sentChunks(viewer));
            assertTrue(replicationIndex.chunkViewers(0L).contains("viewer"),
                    "viewer must be indexed for the player's current chunk");

            Method movement = BedrockSessionManager.class.getDeclaredMethod(
                    "broadcastPlayerMovement", String.class, moving.getClass());
            movement.setAccessible(true);
            movement.invoke(manager, "moving", moving);

            assertTrue(visibleActors(viewer).contains(402L));
            assertEquals(List.of(
                    BedrockAddPlayerCodec.ADD_PLAYER_PACKET_ID,
                    BedrockMobEquipmentCodec.MOB_EQUIPMENT_PACKET_ID,
                    BedrockVitalsCodec.UPDATE_ATTRIBUTES_PACKET_ID,
                    BedrockMoveActorAbsoluteCodec.MOVE_ACTOR_ABSOLUTE_PACKET_ID),
                    packetIds(packetsFor(outbound, "viewer")));

            set(moving, "positionX", 33.0f);
            movement.invoke(manager, "moving", moving);

            assertFalse(visibleActors(viewer).contains(402L));
            assertEquals(List.of(
                    BedrockAddPlayerCodec.ADD_PLAYER_PACKET_ID,
                    BedrockMobEquipmentCodec.MOB_EQUIPMENT_PACKET_ID,
                    BedrockVitalsCodec.SET_HEALTH_PACKET_ID,
                    BedrockVitalsCodec.UPDATE_ATTRIBUTES_PACKET_ID,
                    BedrockMoveActorAbsoluteCodec.MOVE_ACTOR_ABSOLUTE_PACKET_ID,
                    BedrockAddItemActorCodec.REMOVE_ACTOR_PACKET_ID),
                    packetIds(packetsFor(outbound, "viewer")));
        } finally {
            manager.close();
        }
    }

    @Test
    void broadcastsAuthoritativeHeldItemChangesToVisiblePlayers() throws Exception {
        List<Outbound> outbound = new ArrayList<>();
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new World("player-equipment-broadcast-test", 0L, new FlatWorldGenerator()),
                (networkId, label, frame) -> outbound.add(new Outbound(networkId, frame)),
                event -> { });
        manager.setItemRegistry(BedrockVanillaItemCatalog.createRegistry());

        try {
            manager.onIdentity("viewer", identity("viewer", "Viewer"));
            manager.onIdentity("moving", identity("moving", "MovingPlayer"));
            Object viewer = session(manager, "viewer");
            Object moving = session(manager, "moving");
            prepareSession(viewer, entry("00000000-0000-0000-0000-000000000501", 501, "Viewer"));
            prepareSession(moving, entry("00000000-0000-0000-0000-000000000502", 502, "MovingPlayer"));
            set(viewer, "playerListAdvertised", true);
            set(moving, "playerListAdvertised", true);

            PlayerInventory inventory = (PlayerInventory) field(moving, "inventory");
            ItemStack held = new ItemStack("minecraft:diamond", 2, 64);
            inventory.setSlot(0, held);
            inventory.selectHotbarSlot(0);

            Field indexField = BedrockSessionManager.class.getDeclaredField("playerActorReplicationIndex");
            indexField.setAccessible(true);
            ((ActorReplicationIndex) indexField.get(manager)).addActorViewer(502L, "viewer");
            visibleActors(viewer).add(502L);

            Method sync = BedrockSessionManager.class.getDeclaredMethod(
                    "sendInventoryAndEquipmentState", String.class, moving.getClass());
            sync.setAccessible(true);
            sync.invoke(manager, "moving", moving);

            List<BedrockPacket> packets = packetsFor(outbound, "viewer");
            assertEquals(List.of(BedrockMobEquipmentCodec.MOB_EQUIPMENT_PACKET_ID), packetIds(packets));
            BedrockMobEquipmentCodec.DecodedEquipment decoded =
                    BedrockMobEquipmentCodec.decode(packets.get(0), BedrockVanillaItemCatalog.createRegistry());
            assertEquals(502L, decoded.runtimeEntityId());
            assertEquals(held, decoded.item().orElseThrow());
        } finally {
            manager.close();
        }
    }

    @Test
    void doesNotAdvertiseSessionAlreadyMarkedRejected() throws Exception {
        List<Outbound> outbound = new ArrayList<>();
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new World("player-rejected-test", 0L, new FlatWorldGenerator()),
                (networkId, label, frame) -> outbound.add(new Outbound(networkId, frame)),
                event -> { });

        try {
            manager.onIdentity("rejected", identity("rejected", "RejectedPlayer"));
            Object rejected = session(manager, "rejected");
            prepareSession(rejected, entry("00000000-0000-0000-0000-000000000301", 301, "RejectedPlayer"));
            set(rejected, "rejected", true);

            Method advertise = BedrockSessionManager.class.getDeclaredMethod(
                    "advertisePlayerList", String.class, rejected.getClass());
            advertise.setAccessible(true);
            advertise.invoke(manager, "rejected", rejected);

            assertTrue(outbound.isEmpty());
            assertFalse((boolean) field(rejected, "playerListAdvertised"));
        } finally {
            manager.close();
        }
    }

    private static NetherNetClientIdentity identity(String id, String name) {
        return new NetherNetClientIdentity(
                id + "-token", new byte[] {1, 2, 3}, id + "-xuid",
                name, id + "-playfab", "20CA2", null);
    }

    private static BedrockPlayerListCodec.AddEntry entry(String uuid, long actorId, String name) {
        return new BedrockPlayerListCodec.AddEntry(
                UUID.fromString(uuid), actorId, name, actorId + "-xuid",
                actorId + "-playfab", "", 8, new byte[] {1},
                false, false, false, 0);
    }

    private static void prepareSession(Object session, BedrockPlayerListCodec.AddEntry player) throws Exception {
        set(session, "clientInitialized", true);
        set(session, "compressionNegotiated", true);
        set(session, "playerListEntry", player);
        set(session, "entityId", player.actorUniqueId());
        set(session, "buildPlatform", 8);
        set(session, "playerDeviceId", "");
        set(session, "platformChatId", "");
        // Both fixtures spawn at block 0,0; publish the center chunk for visibility tests.
        sentChunks(session).add(0L);
    }

    @SuppressWarnings("unchecked")
    private static java.util.Set<Long> sentChunks(Object session) throws Exception {
        return (java.util.Set<Long>) field(session, "sentChunkKeys");
    }

    @SuppressWarnings("unchecked")
    private static java.util.Set<Long> visibleActors(Object session) throws Exception {
        return (java.util.Set<Long>) field(session, "visiblePlayerEntityIds");
    }

    @SuppressWarnings("unchecked")
    private static Object session(BedrockSessionManager manager, String id) throws Exception {
        Field sessionsField = BedrockSessionManager.class.getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        Map<String, Object> sessions = (Map<String, Object>) sessionsField.get(manager);
        return sessions.get(id);
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

    private static List<Integer> packetIds(List<BedrockPacket> packets) {
        return packets.stream().map(BedrockPacket::packetId).toList();
    }

    private record Outbound(String recipient, byte[] frame) {
    }
}
