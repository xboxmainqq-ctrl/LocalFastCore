package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nexora.network.nethernet.NetherNetClientIdentity;
import ru.nexora.inventory.ItemStack;
import ru.nexora.inventory.PlayerInventoryStorage;
import ru.nexora.network.nethernet.NetherNetMessageFramer;
import ru.nexora.world.PlayerDataStorage;
import ru.nexora.world.PlayerData;

import java.nio.file.Path;
import java.nio.file.Files;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class BedrockSessionManagerTest {
    @TempDir
    Path temporaryDirectory;
    @Test
    void negotiatesNetworkSettingsThenDecodesCompressedGameBatch() {
        List<byte[]> outboundFrames = new ArrayList<>();
        AtomicReference<BedrockSessionManager.PacketEvent> received = new AtomicReference<>();
        BedrockSessionManager manager = new BedrockSessionManager(
                2225,
                (networkId, label, frame) -> outboundFrames.add(frame),
                received::set);

        byte[] protocol = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2225).array();
        BedrockPacket request = new BedrockPacket(
                BedrockBatchCodec.REQUEST_NETWORK_SETTINGS_PACKET_ID, 0, 0, protocol);
        sendClientBatch(manager, "client-1", BedrockBatchCodec.encode(List.of(request), false, 256));

        assertEquals(1, outboundFrames.size());
        byte[] response = reassemble(outboundFrames);
        List<BedrockPacket> responsePackets = BedrockBatchCodec.decode(response, false);
        assertEquals(BedrockBatchCodec.NETWORK_SETTINGS_PACKET_ID, responsePackets.getFirst().packetId());
        assertEquals(1, manager.activeSessionCount());

        byte[] loginPayload = new byte[512];
        java.util.Arrays.fill(loginPayload, (byte) 0x42);
        BedrockPacket login = new BedrockPacket(9, 0, 0, loginPayload);
        byte[] compressedLogin = BedrockBatchCodec.encode(List.of(login), true, 256);
        assertEquals(0, Byte.toUnsignedInt(compressedLogin[0]));
        sendClientBatch(manager, "client-1", compressedLogin);

        assertNotNull(received.get());
        assertEquals(9, received.get().packet().packetId());
        assertArrayEquals(loginPayload, received.get().packet().payload());
    }

    @Test
    void sendsClientOutdatedPlayStatusForOlderProtocol() {
        List<byte[]> outboundFrames = new ArrayList<>();
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, (networkId, label, frame) -> outboundFrames.add(frame), event -> {});

        byte[] protocol = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2193).array();
        BedrockPacket request = new BedrockPacket(
                BedrockBatchCodec.REQUEST_NETWORK_SETTINGS_PACKET_ID, 0, 0, protocol);
        sendClientBatch(manager, "old-client", BedrockBatchCodec.encode(List.of(request), false, 256));

        BedrockPacket response = BedrockBatchCodec.decode(reassemble(outboundFrames), false).getFirst();
        assertEquals(BedrockBatchCodec.PLAY_STATUS_PACKET_ID, response.packetId());
        assertEquals(1, ByteBuffer.wrap(response.payload()).order(ByteOrder.BIG_ENDIAN).getInt());
    }

    @Test
    void requiresInitialSettingsRequestOnReliableChannel() {
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, (networkId, label, frame) -> {}, event -> {});
        byte[] protocol = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2225).array();
        BedrockPacket request = new BedrockPacket(
                BedrockBatchCodec.REQUEST_NETWORK_SETTINGS_PACKET_ID, 0, 0, protocol);
        byte[] batch = BedrockBatchCodec.encode(List.of(request), false, 256);
        byte[] frame = NetherNetMessageFramer.fragment(batch, 1200, false).getFirst();

        assertThrows(IllegalArgumentException.class,
                () -> manager.onFrame("client-2", "UnreliableDataChannel", frame));
    }

    @Test
    void rejectsLoginWhenPersistedInventoryIsCorruptedWithoutOverwritingIt() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair clientKey = generator.generateKeyPair();

        Path inventoryDirectory = temporaryDirectory.resolve("corrupt-inventories");
        Files.createDirectories(inventoryDirectory);
        Path inventoryFile = inventoryDirectory.resolve("corrupt-xuid.nxi");
        byte[] corruptedBytes = new byte[] {
                0x4e, 0x58, 0x49, 0x56, 0x01, 0x00, 0x24, 0x00, 0, 0, 0, 0
        };
        Files.write(inventoryFile, corruptedBytes);

        PlayerInventoryStorage inventoryStorage = new PlayerInventoryStorage(inventoryDirectory);
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new ru.nexora.world.World("NEXORA World", 0L, new ru.nexora.world.FlatWorldGenerator()),
                null, inventoryStorage, (networkId, label, frame) -> {}, event -> {});
        manager.onIdentity("corrupt-client", new NetherNetClientIdentity(
                "corrupt-game-token", clientKey.getPublic().getEncoded(), "corrupt-xuid",
                "CorruptPlayer", "playfab-id", "20CA2", null));

        byte[] protocol = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2225).array();
        BedrockPacket request = new BedrockPacket(
                BedrockBatchCodec.REQUEST_NETWORK_SETTINGS_PACKET_ID, 0, 0, protocol);
        sendClientBatch(manager, "corrupt-client", BedrockBatchCodec.encode(List.of(request), false, 256));

        String rawClientDataToken = signClientDataToken(clientKey);
        String certificate = "{\"chain\":[]}";
        String chainJson = "{\"Certificate\":\"" + certificate.replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\",\"AuthenticationType\":0,\"Token\":\"corrupt-game-token\"}";
        BedrockPacket login = new BedrockPacket(1, 0, 0,
                loginPayload(2225, connectionRequest(chainJson, rawClientDataToken)));

        assertThrows(SecurityException.class, () -> sendClientBatch(manager, "corrupt-client",
                BedrockBatchCodec.encode(List.of(login), true, 256)));
        assertEquals(0, manager.authenticatedPlayerCount());
        assertTrue(manager.playerInventory("corrupt-client").isEmpty());

        manager.onDisconnected("corrupt-client");
        assertArrayEquals(corruptedBytes, Files.readAllBytes(inventoryFile));
        manager.close();
    }

    @Test
    void authenticatesLoginAndCompletesEmptyResourcePackExchange() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair clientKey = generator.generateKeyPair();

        List<byte[]> outboundFrames = new ArrayList<>();
        AtomicLong monotonicClock = new AtomicLong();
        PlayerDataStorage playerStorage = new PlayerDataStorage(temporaryDirectory.resolve("players"));
        PlayerInventoryStorage inventoryStorage = new PlayerInventoryStorage(temporaryDirectory.resolve("inventories"));
        BedrockSessionManager manager = new BedrockSessionManager(
                2225,
                "1.26.62",
                BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new ru.nexora.world.World("NEXORA World", 0L, new ru.nexora.world.FlatWorldGenerator()),
                playerStorage,
                inventoryStorage,
                (networkId, label, frame) -> outboundFrames.add(frame),
                event -> {},
                monotonicClock::get);
        manager.setItemRegistry(new BedrockItemRegistry(List.of(
                new BedrockItemRegistry.Entry("nexora:arcane_shard",
                        new BedrockInventoryContentCodec.ItemDefinition(257, 0, 0, new byte[0])))));
        manager.onIdentity("authenticated-client", new NetherNetClientIdentity(
                "game-server-token",
                clientKey.getPublic().getEncoded(),
                "123456789",
                "TestPlayer",
                "playfab-id",
                "20CA2",
                null
        ));

        byte[] protocol = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2225).array();
        BedrockPacket request = new BedrockPacket(
                BedrockBatchCodec.REQUEST_NETWORK_SETTINGS_PACKET_ID, 0, 0, protocol);
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(request), false, 256));

        String rawClientDataToken = signClientDataToken(clientKey);
        String certificate = "{\"chain\":[]}";
        String chainJson = "{\"Certificate\":\"" + certificate.replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\",\"AuthenticationType\":0,\"Token\":\"game-server-token\"}";
        BedrockPacket login = new BedrockPacket(1, 0, 0,
                loginPayload(2225, connectionRequest(chainJson, rawClientDataToken)));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(login), true, 256));

        assertEquals(1, manager.authenticatedPlayerCount());
        var inventory = manager.playerInventory("authenticated-client").orElseThrow();
        assertEquals(36, inventory.size());
        assertTrue(inventory.heldItem().isEmpty());
        inventory.setSlot(0, new ItemStack("nexora:arcane_shard", 3, 64));
        assertEquals(3, manager.playerInventory("authenticated-client").orElseThrow()
                .count("nexora:arcane_shard"));
        assertTrue(manager.playerInventory("unknown-client").isEmpty());

        BedrockPacket clientHandshake = new BedrockPacket(
                BedrockBatchCodec.CLIENT_TO_SERVER_HANDSHAKE_PACKET_ID, 0, 0, new byte[0]);
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(clientHandshake), true, 256));

        assertEquals(3, outboundFrames.size());
        assertEquals(BedrockBatchCodec.PLAY_STATUS_PACKET_ID,
                decodeOutbound(outboundFrames.get(1), true).packetId());
        assertEquals(BedrockBatchCodec.RESOURCE_PACKS_INFO_PACKET_ID,
                decodeOutbound(outboundFrames.get(2), true).packetId());

        BedrockPacket allPacksDownloaded = resourcePackResponse(2, "downloadingfinished");
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(allPacksDownloaded), true, 256));
        assertEquals(BedrockBatchCodec.RESOURCE_PACK_STACK_PACKET_ID,
                decodeOutbound(outboundFrames.get(3), true).packetId());

        BedrockPacket stackCompleted = resourcePackResponse(3, "resourcepackstackfinished");
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(stackCompleted), true, 256));
        assertEquals(1, manager.resourcePackReadyCount());
        assertEquals(21, outboundFrames.size());
        assertEquals(0x0b, decodeOutbound(outboundFrames.get(4), true).packetId());
        assertEquals(0x0a, decodeOutbound(outboundFrames.get(5), true).packetId());
        assertEquals(0x3c, decodeOutbound(outboundFrames.get(6), true).packetId());
        assertEquals(0x3b, decodeOutbound(outboundFrames.get(7), true).packetId());
        assertEquals(0x2b, decodeOutbound(outboundFrames.get(8), true).packetId());
        assertEquals(0x3e, decodeOutbound(outboundFrames.get(9), true).packetId());
        assertEquals(0x79, decodeOutbound(outboundFrames.get(10), true).packetId());
        assertEquals(0x7a, decodeOutbound(outboundFrames.get(11), true).packetId());
        for (int index = 12; index < 21; index++) {
            assertEquals(0x3a, decodeOutbound(outboundFrames.get(index), true).packetId());
        }

        // The server must not announce the player as spawned until the client confirms StartGame.
        BedrockPacket initialized = new BedrockPacket(0x71, 0, 0, new byte[] {1});
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(initialized), true, 256));
        assertEquals(23, outboundFrames.size());
        assertEquals(0x31, decodeOutbound(outboundFrames.get(22), true).packetId());
        BedrockPacket playerSpawn = decodeOutbound(outboundFrames.get(21), true);
        assertEquals(BedrockBatchCodec.PLAY_STATUS_PACKET_ID, playerSpawn.packetId());
        assertEquals(3, ByteBuffer.wrap(playerSpawn.payload()).order(ByteOrder.BIG_ENDIAN).getInt());
        assertEquals(0.5f, manager.playerPosition("authenticated-client").orElseThrow().x());
        assertEquals(65.0f, manager.playerPosition("authenticated-client").orElseThrow().y());

        monotonicClock.set(100_000_000L);
        BedrockPacket movement = new BedrockPacket(0x90, 0, 0,
                BedrockPlayerAuthInputCodecTest.movementPayload(1.0f, 65.0f, 1.0f, 30.0f, 0L, false));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(movement), true, 256));
        BedrockSessionManager.PlayerPosition moved = manager.playerPosition("authenticated-client").orElseThrow();
        assertEquals(1.0f, moved.x());
        assertEquals(1.0f, moved.z());
        assertEquals(30.0f, moved.yaw());
        assertEquals(0L, moved.tick());

        // A second position update with the same client tick is a replay and must be ignored.
        BedrockPacket replayedMovement = new BedrockPacket(0x90, 0, 0,
                BedrockPlayerAuthInputCodecTest.movementPayload(2.0f, 65.0f, 1.0f, 30.0f, 0L, false));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(replayedMovement), true, 256));
        assertEquals(1.0f, manager.playerPosition("authenticated-client").orElseThrow().x());
        assertEquals(22, outboundFrames.size());

        monotonicClock.set(200_000_000L);
        BedrockPacket implausibleTeleport = new BedrockPacket(0x90, 0, 0,
                BedrockPlayerAuthInputCodecTest.movementPayload(100.0f, 65.0f, 100.0f, 90.0f, 2L, false));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(implausibleTeleport), true, 256));
        assertEquals(1.0f, manager.playerPosition("authenticated-client").orElseThrow().x());
        assertEquals(22, outboundFrames.size());

        float[] walkPositions = {3.0f, 5.0f, 7.0f, 9.0f, 11.0f, 13.0f, 15.0f, 16.5f};
        long[] walkTimes = {400_000_000L, 650_000_000L, 900_000_000L, 1_150_000_000L,
                1_400_000_000L, 1_650_000_000L, 1_900_000_000L, 2_100_000_000L};
        for (int i = 0; i < walkPositions.length; i++) {
            monotonicClock.set(walkTimes[i]);
            BedrockPacket step = new BedrockPacket(0x90, 0, 0,
                    BedrockPlayerAuthInputCodecTest.movementPayload(
                            walkPositions[i], 65.0f, 1.0f, 30.0f, 3L + i, false));
            sendClientBatch(manager, "authenticated-client",
                    BedrockBatchCodec.encode(List.of(step), true, 256));
            if (i < walkPositions.length - 1) assertEquals(22, outboundFrames.size());
        }
        assertEquals(29, outboundFrames.size());
        assertEquals(0x79, decodeOutbound(outboundFrames.get(23), true).packetId());
        for (int index = 24; index < 27; index++) {
            assertEquals(0x3a, decodeOutbound(outboundFrames.get(index), true).packetId());
        }
        assertEquals(16.5f, manager.playerPosition("authenticated-client").orElseThrow().x());

        monotonicClock.set(2_110_000_000L);
        BedrockPacket speedHack = new BedrockPacket(0x90, 0, 0,
                BedrockPlayerAuthInputCodecTest.movementPayload(26.5f, 65.0f, 1.0f, 30.0f, 11L, false));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(speedHack), true, 256));
        assertEquals(16.5f, manager.playerPosition("authenticated-client").orElseThrow().x());
        assertEquals(26, outboundFrames.size());

        BedrockPacket invalidBlockPosition = new BedrockPacket(0x90, 0, 0,
                BedrockPlayerAuthInputCodecTest.blockActionPayload(
                        16.5f, 65.0f, 1.0f, 0, 16, 400, 1, 1, 11L));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(invalidBlockPosition), true, 256));
        assertEquals(26, outboundFrames.size());

        monotonicClock.set(3_100_000_000L);
        BedrockPacket startBreak = new BedrockPacket(0x90, 0, 0,
                BedrockPlayerAuthInputCodecTest.blockActionPayload(
                        16.5f, 65.0f, 1.0f, 0, 16, 64, 1, 1, 12L));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(startBreak), true, 256));
        assertEquals(26, outboundFrames.size());

        monotonicClock.set(3_600_000_000L);
        BedrockPacket prematureBreak = new BedrockPacket(0x90, 0, 0,
                BedrockPlayerAuthInputCodecTest.blockActionPayload(
                        16.5f, 65.0f, 1.0f, 26, 16, 64, 1, 1, 5_000L));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(prematureBreak), true, 256));
        assertEquals(26, outboundFrames.size()); // too little break time has elapsed

        monotonicClock.set(3_800_000_000L);
        BedrockPacket finishBreak = new BedrockPacket(0x90, 0, 0,
                BedrockPlayerAuthInputCodecTest.blockActionPayload(
                        16.5f, 65.0f, 1.0f, 26, 16, 64, 1, 1, 5_001L));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(finishBreak), true, 256));
        assertEquals(27, outboundFrames.size());
        assertEquals(0x15, decodeOutbound(outboundFrames.get(28), true).packetId());

        // A decoded no-op player-slot transaction is accepted and reconciled with authoritative content.
        BedrockPacket inventoryTransaction = new BedrockPacket(0x1e, 0, 0,
                noOpInventoryTransactionPayload(257, 3));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(inventoryTransaction), true, 256));
        assertEquals(28, outboundFrames.size());
        assertEquals(0x31, decodeOutbound(outboundFrames.get(27), true).packetId());

        assertThrows(SecurityException.class, () -> sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(initialized), true, 256)));

        manager.onDisconnected("authenticated-client");
        var savedPlayer = playerStorage.load("123456789").orElseThrow();
        assertEquals(16.5f, savedPlayer.x());
        assertEquals(65.0f, savedPlayer.y());
        assertEquals(1.0f, savedPlayer.z());
        assertEquals(30.0f, savedPlayer.yaw());
        var savedInventory = inventoryStorage.load("123456789").orElseThrow();
        assertEquals(3, savedInventory.count("nexora:arcane_shard"));
    }

    @Test
    void restoresSavedPositionAndRotationInStartGame() throws Exception {
        PlayerDataStorage storage = new PlayerDataStorage(temporaryDirectory);
        storage.save(new PlayerData("saved-xuid", "SavedPlayer", "NEXORA World",
                32.5f, 70.0f, -16.5f, 90.0f, 12.0f, System.currentTimeMillis()));

        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair clientKey = generator.generateKeyPair();
        List<byte[]> outboundFrames = new ArrayList<>();
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new ru.nexora.world.World("NEXORA World", 0L, new ru.nexora.world.FlatWorldGenerator()),
                storage, (networkId, label, frame) -> outboundFrames.add(frame), event -> {});
        manager.onIdentity("saved-client", new NetherNetClientIdentity(
                "saved-game-token", clientKey.getPublic().getEncoded(), "saved-xuid",
                "SavedPlayer", "saved-playfab", "20CA2", null));

        byte[] protocol = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2225).array();
        BedrockPacket settings = new BedrockPacket(
                BedrockBatchCodec.REQUEST_NETWORK_SETTINGS_PACKET_ID, 0, 0, protocol);
        sendClientBatch(manager, "saved-client", BedrockBatchCodec.encode(List.of(settings), false, 256));

        String rawClientDataToken = signClientDataToken(clientKey);
        String certificate = "{\"chain\":[]}";
        String chainJson = "{\"Certificate\":\"" + certificate.replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\",\"AuthenticationType\":0,\"Token\":\"saved-game-token\"}";
        BedrockPacket login = new BedrockPacket(1, 0, 0,
                loginPayload(2225, connectionRequest(chainJson, rawClientDataToken)));
        sendClientBatch(manager, "saved-client", BedrockBatchCodec.encode(List.of(login), true, 256));

        BedrockPacket clientHandshake = new BedrockPacket(
                BedrockBatchCodec.CLIENT_TO_SERVER_HANDSHAKE_PACKET_ID, 0, 0, new byte[0]);
        sendClientBatch(manager, "saved-client", BedrockBatchCodec.encode(List.of(clientHandshake), true, 256));
        sendClientBatch(manager, "saved-client", BedrockBatchCodec.encode(
                List.of(resourcePackResponse(2, "downloadingfinished")), true, 256));
        sendClientBatch(manager, "saved-client", BedrockBatchCodec.encode(
                List.of(resourcePackResponse(3, "resourcepackstackfinished")), true, 256));

        BedrockPacket startGame = decodeOutbound(outboundFrames.get(4), true);
        ByteBuffer position = ByteBuffer.wrap(startGame.payload(), 3, 20).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(32.5f, position.getFloat());
        assertEquals(70.0f, position.getFloat());
        assertEquals(-16.5f, position.getFloat());
        assertEquals(12.0f, position.getFloat()); // pitch
        assertEquals(90.0f, position.getFloat()); // yaw

        assertArrayEquals(new byte[] {0x40, (byte) 0x8c, 0x01, 0x21, 0x30, 0, 0, 0, 0},
                decodeOutbound(outboundFrames.get(10), true).payload());
        BedrockPacket spawnChunk = decodeOutbound(outboundFrames.get(12), true);
        assertEquals(4, Byte.toUnsignedInt(spawnChunk.payload()[0])); // chunk X = 2
        assertEquals(3, Byte.toUnsignedInt(spawnChunk.payload()[1])); // chunk Z = -2
        manager.close();
    }

    private static BedrockPacket decodeOutbound(byte[] frame, boolean compressed) {
        NetherNetMessageFramer.Reassembler reassembler = new NetherNetMessageFramer.Reassembler();
        byte[] payload = reassembler.accept(frame, true).orElseThrow();
        return BedrockBatchCodec.decode(payload, compressed).getFirst();
    }

    private static BedrockPacket resourcePackResponse(int response, String name) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeVarInt(body, response);
        writeString(body, name);
        return new BedrockPacket(
                BedrockBatchCodec.RESOURCE_PACK_CLIENT_RESPONSE_PACKET_ID, 0, 0, body.toByteArray());
    }

    private static byte[] loginPayload(int protocol, byte[] connectionRequest) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(protocol).array());
        writeVarInt(output, connectionRequest.length);
        output.writeBytes(connectionRequest);
        return output.toByteArray();
    }

    private static byte[] connectionRequest(String chainJson, String rawToken) {
        byte[] chain = chainJson.getBytes(StandardCharsets.UTF_8);
        byte[] token = rawToken.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(chain.length).array());
        output.writeBytes(chain);
        output.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(token.length).array());
        output.writeBytes(token);
        return output.toByteArray();
    }

    private static String signClientDataToken(KeyPair keyPair) throws Exception {
        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        String header = base64Url("{\"alg\":\"ES384\",\"x5u\":\"" + publicKey + "\"}");
        long now = java.time.Instant.now().getEpochSecond();
        String payload = base64Url("{\"exp\":" + (now + 3600) + ",\"nbf\":" + (now - 60) + "}");
        String signingInput = header + "." + payload;
        Signature signer = Signature.getInstance("SHA384withECDSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        byte[] signature = derToJose(signer.sign());
        return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeString(ByteArrayOutputStream output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static void writeVarInt(ByteArrayOutputStream output, int value) {
        do {
            int current = value & 0x7f;
            value >>>= 7;
            if (value != 0) current |= 0x80;
            output.write(current);
        } while (value != 0);
    }

    private static byte[] derToJose(byte[] der) {
        int offset = 2;
        if (der[offset++] != 0x02) throw new IllegalArgumentException("Invalid ECDSA signature");
        int rLength = der[offset++] & 0xff;
        byte[] r = new byte[rLength];
        System.arraycopy(der, offset, r, 0, rLength);
        offset += rLength;
        if (der[offset++] != 0x02) throw new IllegalArgumentException("Invalid ECDSA signature");
        int sLength = der[offset++] & 0xff;
        byte[] s = new byte[sLength];
        System.arraycopy(der, offset, s, 0, sLength);
        byte[] jose = new byte[96];
        copyFixed(r, jose, 0);
        copyFixed(s, jose, 48);
        return jose;
    }

    private static void copyFixed(byte[] value, byte[] output, int offset) {
        int first = value.length > 1 && value[0] == 0 ? 1 : 0;
        int length = value.length - first;
        System.arraycopy(value, first, output, offset + 48 - length, length);
    }

    private static void sendClientBatch(BedrockSessionManager manager, String id, byte[] batch) {
        for (byte[] frame : NetherNetMessageFramer.fragment(batch, 1200, true)) {
            manager.onFrame(id, "ReliableDataChannel", frame);
        }
    }

    private static byte[] reassemble(List<byte[]> frames) {
        NetherNetMessageFramer.Reassembler reassembler = new NetherNetMessageFramer.Reassembler();
        byte[] complete = null;
        for (byte[] frame : frames) {
            var result = reassembler.accept(frame, true);
            if (result.isPresent()) complete = result.get();
        }
        assertNotNull(complete);
        return complete;
    }
    private static byte[] noOpInventoryTransactionPayload(int runtimeItemId, int count) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0); // LegacyRequestId
        out.write(0); // Normal transaction
        out.write(1); // one action
        out.write(0); // ContainerInventory source
        out.write(1); // container ID present
        out.write(0); // player inventory container
        out.write(0); // no world flags
        out.write(0); // slot zero
        writeDescriptor(out, runtimeItemId, count);
        writeDescriptor(out, runtimeItemId, count);
        return out.toByteArray();
    }

    private static void writeDescriptor(ByteArrayOutputStream out, int runtimeItemId, int count) {
        out.write(runtimeItemId & 0xff);
        out.write((runtimeItemId >>> 8) & 0xff);
        out.write(count & 0xff);
        out.write((count >>> 8) & 0xff);
        out.write(0); // aux
        out.write(0); // has StackNetId = false
        out.write(0); // block runtime ID
        out.write(0); // user data length
    }

}
