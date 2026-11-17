package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nexora.network.nethernet.NetherNetClientIdentity;
import ru.nexora.inventory.ItemStack;
import ru.nexora.inventory.PlayerInventoryStorage;
import ru.nexora.inventory.PlayerEquipment;
import ru.nexora.inventory.PlayerEquipmentStorage;
import ru.nexora.inventory.ContainerInventoryStorage;
import ru.nexora.network.nethernet.NetherNetMessageFramer;
import ru.nexora.world.PlayerDataStorage;
import ru.nexora.world.PlayerData;
import ru.nexora.world.PlayerVitals;
import ru.nexora.world.WorldItemEntity;

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
    void sendsCraftingRecipesAndSettlesOnlyValidatedCraftingEvents() throws Exception {
        var world = new ru.nexora.world.World(
                "NEXORA World", 0L, new ru.nexora.world.FlatWorldGenerator(), 0, 65, 0);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair clientKey = generator.generateKeyPair();
        List<byte[]> outboundFrames = new ArrayList<>();
        var inventoryStorage = new ru.nexora.inventory.PlayerInventoryStorage(
                temporaryDirectory.resolve("craft-inventory"));
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                world, null, inventoryStorage, null,
                (networkId, label, frame) -> outboundFrames.add(frame), event -> {}, () -> 0L);
        var itemRegistry = BedrockVanillaItemCatalog.createRegistry();
        manager.setItemRegistry(itemRegistry);
        manager.onIdentity("craft-client", new NetherNetClientIdentity(
                "craft-game-token", clientKey.getPublic().getEncoded(), "craft-xuid",
                "CraftPlayer", "craft-playfab", "20CA2", null));

        byte[] protocol = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2225).array();
        BedrockPacket settings = new BedrockPacket(
                BedrockBatchCodec.REQUEST_NETWORK_SETTINGS_PACKET_ID, 0, 0, protocol);
        sendClientBatch(manager, "craft-client", BedrockBatchCodec.encode(List.of(settings), false, 256));

        String rawClientDataToken = signClientDataToken(clientKey);
        String certificate = "{\"chain\":[]}";
        String chainJson = "{\"Certificate\":\"" + certificate.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                + "\",\"AuthenticationType\":0,\"Token\":\"craft-game-token\"}";
        BedrockPacket login = new BedrockPacket(1, 0, 0,
                loginPayload(2225, connectionRequest(chainJson, rawClientDataToken)));
        sendClientBatch(manager, "craft-client", BedrockBatchCodec.encode(List.of(login), true, 256));
        var inventory = manager.playerInventory("craft-client").orElseThrow();
        inventory.setSlot(0, new ItemStack("minecraft:iron_ingot", 1, 64));
        sendClientBatch(manager, "craft-client", BedrockBatchCodec.encode(List.of(
                new BedrockPacket(BedrockBatchCodec.CLIENT_TO_SERVER_HANDSHAKE_PACKET_ID, 0, 0, new byte[0])
        ), true, 256));
        sendClientBatch(manager, "craft-client", BedrockBatchCodec.encode(
                List.of(resourcePackResponse(2, "downloadingfinished")), true, 256));
        sendClientBatch(manager, "craft-client", BedrockBatchCodec.encode(
                List.of(resourcePackResponse(3, "resourcepackstackfinished")), true, 256));
        int beforeInit = outboundFrames.size();
        sendClientBatch(manager, "craft-client", BedrockBatchCodec.encode(
                List.of(new BedrockPacket(0x71, 0, 0, new byte[] {1})), true, 256));
        List<BedrockPacket> initialPackets = outboundFrames.subList(beforeInit, outboundFrames.size()).stream()
                .flatMap(frame -> decodeOutboundAll(frame, true).stream()).toList();
        assertTrue(initialPackets.stream().anyMatch(packet ->
                packet.packetId() == BedrockCraftingDataCodec.CRAFTING_DATA_PACKET_ID));
        assertTrue(initialPackets.stream().anyMatch(packet ->
                packet.packetId() == BedrockInventoryContentCodec.INVENTORY_CONTENT_PACKET_ID
                        && Byte.toUnsignedInt(packet.payload()[0])
                        == BedrockInventoryContentCodec.PLAYER_UI_CONTAINER_ID));

        var recipes = ru.nexora.inventory.CraftingRecipeRegistry.vanillaSubset();
        var ironRecipe = recipes.find("minecraft:iron_nuggets_from_iron_ingot");
        var ironDefinition = itemRegistry.findByIdentifier("minecraft:iron_ingot").networkDefinition();
        var nuggetDefinition = itemRegistry.findByIdentifier("minecraft:iron_nugget").networkDefinition();
        sendCraftingGridTransfer(manager, "craft-client",
                new SlotAction(0, 0, StackRef.of(ironDefinition, 1), StackRef.empty()),
                new SlotAction(124, 28, StackRef.empty(), StackRef.of(ironDefinition, 1)));
        BedrockPacket validCraft = new BedrockPacket(
                BedrockCraftingEventCodec.CRAFTING_EVENT_PACKET_ID, 0, 0,
                BedrockCraftingDataCodecTest.craftingEvent(ironRecipe.uuid(),
                        ironDefinition.runtimeItemId(), 1, nuggetDefinition.runtimeItemId(), 9));
        sendClientBatch(manager, "craft-client", BedrockBatchCodec.encode(List.of(validCraft), true, 256));
        assertEquals(0, inventory.count("minecraft:iron_ingot"));
        assertEquals(9, inventory.count("minecraft:iron_nugget"));

        inventory.setSlot(2, new ItemStack("minecraft:oak_planks", 2, 64));
        var planksDefinition = itemRegistry.findByIdentifier("minecraft:oak_planks").networkDefinition();
        var sticksRecipe = recipes.find("minecraft:sticks_from_oak_planks");
        var stickDefinition = itemRegistry.findByIdentifier("minecraft:stick").networkDefinition();
        sendCraftingGridTransfer(manager, "craft-client",
                new SlotAction(0, 2, StackRef.of(planksDefinition, 2), StackRef.of(planksDefinition, 1)),
                new SlotAction(124, 28, StackRef.empty(), StackRef.of(planksDefinition, 1)));
        sendCraftingGridTransfer(manager, "craft-client",
                new SlotAction(0, 2, StackRef.of(planksDefinition, 1), StackRef.empty()),
                new SlotAction(124, 29, StackRef.empty(), StackRef.of(planksDefinition, 1)));

        BedrockPacket invalidHorizontalSticks = new BedrockPacket(
                BedrockCraftingEventCodec.CRAFTING_EVENT_PACKET_ID, 0, 0,
                BedrockCraftingDataCodecTest.craftingEventGrid(
                        sticksRecipe.uuid(), BedrockCraftingEventCodec.TYPE_SHAPED,
                        new int[] {planksDefinition.runtimeItemId(), planksDefinition.runtimeItemId(), 0, 0},
                        new int[] {1, 1, 0, 0},
                        new long[] {planksDefinition.blockRuntimeId(), planksDefinition.blockRuntimeId(), 0, 0},
                        stickDefinition.runtimeItemId(), 4, 0));
        sendClientBatch(manager, "craft-client",
                BedrockBatchCodec.encode(List.of(invalidHorizontalSticks), true, 256));
        assertEquals(0, inventory.count("minecraft:stick"));

        sendCraftingGridTransfer(manager, "craft-client",
                new SlotAction(124, 29, StackRef.of(planksDefinition, 1), StackRef.empty()),
                new SlotAction(124, 30, StackRef.empty(), StackRef.of(planksDefinition, 1)));
        BedrockPacket validShapedCraft = new BedrockPacket(
                BedrockCraftingEventCodec.CRAFTING_EVENT_PACKET_ID, 0, 0,
                BedrockCraftingDataCodecTest.craftingEventGrid(
                        sticksRecipe.uuid(), BedrockCraftingEventCodec.TYPE_SHAPED,
                        new int[] {planksDefinition.runtimeItemId(), 0, planksDefinition.runtimeItemId(), 0},
                        new int[] {1, 0, 1, 0},
                        new long[] {planksDefinition.blockRuntimeId(), 0, planksDefinition.blockRuntimeId(), 0},
                        stickDefinition.runtimeItemId(), 4, 0));
        sendClientBatch(manager, "craft-client",
                BedrockBatchCodec.encode(List.of(validShapedCraft), true, 256));
        assertEquals(4, inventory.count("minecraft:stick"));
        assertEquals(0, inventory.count("minecraft:oak_planks"));

        inventory.setSlot(4, new ItemStack("minecraft:iron_ingot", 1, 64));
        sendCraftingGridTransfer(manager, "craft-client",
                new SlotAction(0, 4, StackRef.of(ironDefinition, 1), StackRef.empty()),
                new SlotAction(124, 28, StackRef.empty(), StackRef.of(ironDefinition, 1)));
        var goldNuggetDefinition = itemRegistry.findByIdentifier("minecraft:gold_nugget").networkDefinition();
        BedrockPacket forgedOutput = new BedrockPacket(
                BedrockCraftingEventCodec.CRAFTING_EVENT_PACKET_ID, 0, 0,
                BedrockCraftingDataCodecTest.craftingEvent(ironRecipe.uuid(),
                        ironDefinition.runtimeItemId(), 1, goldNuggetDefinition.runtimeItemId(), 9));
        sendClientBatch(manager, "craft-client", BedrockBatchCodec.encode(List.of(forgedOutput), true, 256));
        assertEquals(0, inventory.count("minecraft:iron_ingot"));
        assertEquals(9, inventory.count("minecraft:iron_nugget"));
        assertEquals(0, inventory.count("minecraft:gold_nugget"));
        manager.close();
        assertEquals(1, inventoryStorage.load("craft-xuid").orElseThrow().count("minecraft:iron_ingot"));
    }

    @Test
    void opensChestAndRoutesPersistedBedrockContainerTransactions() throws Exception {
        var containerStorage = new ContainerInventoryStorage(
                temporaryDirectory.resolve("chest-storage"), "NEXORA_World");
        var world = new ru.nexora.world.World(
                "NEXORA World", 0L, new ru.nexora.world.FlatWorldGenerator(),
                0, 65, 0, null, null, containerStorage);
        world.setBlock(1, 65, 0, ru.nexora.world.BlockType.CHEST);
        var chest = world.containers().orElseThrow().getOrCreate(1, 65, 0);
        chest.setSlot(0, new ItemStack("minecraft:diamond", 2, 64));

        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair clientKey = generator.generateKeyPair();
        List<byte[]> outboundFrames = new ArrayList<>();
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                world, null, null, null,
                (networkId, label, frame) -> outboundFrames.add(frame), event -> {}, () -> 0L);
        manager.setItemRegistry(BedrockVanillaItemCatalog.createRegistry());
        manager.onIdentity("chest-client", new NetherNetClientIdentity(
                "chest-game-token", clientKey.getPublic().getEncoded(), "chest-xuid",
                "ChestPlayer", "chest-playfab", "20CA2", null));

        byte[] protocol = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2225).array();
        BedrockPacket settings = new BedrockPacket(
                BedrockBatchCodec.REQUEST_NETWORK_SETTINGS_PACKET_ID, 0, 0, protocol);
        sendClientBatch(manager, "chest-client", BedrockBatchCodec.encode(List.of(settings), false, 256));

        String rawClientDataToken = signClientDataToken(clientKey);
        String certificate = "{\"chain\":[]}";
        String chainJson = "{\"Certificate\":\"" + certificate.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                + "\",\"AuthenticationType\":0,\"Token\":\"chest-game-token\"}";
        BedrockPacket login = new BedrockPacket(1, 0, 0,
                loginPayload(2225, connectionRequest(chainJson, rawClientDataToken)));
        sendClientBatch(manager, "chest-client", BedrockBatchCodec.encode(List.of(login), true, 256));
        manager.playerInventory("chest-client").orElseThrow()
                .setSlot(0, new ItemStack("minecraft:chest", 1, 64));
        sendClientBatch(manager, "chest-client", BedrockBatchCodec.encode(List.of(
                new BedrockPacket(BedrockBatchCodec.CLIENT_TO_SERVER_HANDSHAKE_PACKET_ID, 0, 0, new byte[0])
        ), true, 256));
        sendClientBatch(manager, "chest-client", BedrockBatchCodec.encode(
                List.of(resourcePackResponse(2, "downloadingfinished")), true, 256));
        sendClientBatch(manager, "chest-client", BedrockBatchCodec.encode(
                List.of(resourcePackResponse(3, "resourcepackstackfinished")), true, 256));
        sendClientBatch(manager, "chest-client", BedrockBatchCodec.encode(
                List.of(new BedrockPacket(0x71, 0, 0, new byte[] {1})), true, 256));

        int beforeOpen = outboundFrames.size();
        BedrockPacket clickChest = new BedrockPacket(0x90, 0, 0,
                chestUseMovementPayload(0.5f, 65.0f, 0.5f, 1L, 0, 54, 1));
        sendClientBatch(manager, "chest-client", BedrockBatchCodec.encode(List.of(clickChest), true, 256));
        List<BedrockPacket> openPackets = outboundFrames.subList(beforeOpen, outboundFrames.size()).stream()
                .flatMap(frame -> decodeOutboundAll(frame, true).stream()).toList();
        BedrockPacket open = openPackets.stream()
                .filter(packet -> packet.packetId() == BedrockContainerPacketCodec.CONTAINER_OPEN_PACKET_ID)
                .findFirst().orElseThrow();
        assertEquals(1, Byte.toUnsignedInt(open.payload()[0]));
        assertTrue(openPackets.stream().anyMatch(packet ->
                packet.packetId() == BedrockInventoryContentCodec.INVENTORY_CONTENT_PACKET_ID
                        && Byte.toUnsignedInt(packet.payload()[0]) == 1));

        BedrockPacket transfer = new BedrockPacket(0x1e, 0, 0, chestTransferPayload());
        int beforeTransfer = outboundFrames.size();
        sendClientBatch(manager, "chest-client", BedrockBatchCodec.encode(List.of(transfer), true, 256));
        assertTrue(chest.getSlot(0).isEmpty());
        assertEquals(2, manager.playerInventory("chest-client").orElseThrow().getSlot(1)
                .orElseThrow().count());
        List<BedrockPacket> transferPackets = outboundFrames.subList(beforeTransfer, outboundFrames.size()).stream()
                .flatMap(frame -> decodeOutboundAll(frame, true).stream()).toList();
        assertTrue(transferPackets.stream().anyMatch(packet ->
                packet.packetId() == BedrockInventoryContentCodec.INVENTORY_CONTENT_PACKET_ID
                        && Byte.toUnsignedInt(packet.payload()[0]) == 1));

        sendClientBatch(manager, "chest-client", BedrockBatchCodec.encode(List.of(
                BedrockContainerPacketCodec.createClose(1, 0, false)), true, 256));
        var persisted = containerStorage.load(1, 65, 0).orElseThrow();
        assertTrue(persisted.getSlot(0).isEmpty());
        assertEquals(2, manager.playerInventory("chest-client").orElseThrow()
                .getSlot(1).orElseThrow().count());
        manager.close();
    }

    @Test
    void consumesFoodOnlyAfterValidatedItemRelease() throws Exception {
        PlayerDataStorage profiles = new PlayerDataStorage(temporaryDirectory.resolve("food-profiles"));
        profiles.save(new PlayerData("food-xuid", "FoodPlayer", "NEXORA World",
                0.5f, 65.0f, 0.5f, 0, 0, 1,
                new PlayerVitals(20.0f, 10, 2.0f, 0.0f)));

        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair clientKey = generator.generateKeyPair();
        List<byte[]> outboundFrames = new ArrayList<>();
        AtomicLong clock = new AtomicLong();
        ru.nexora.world.World world = new ru.nexora.world.World(
                "NEXORA World", 0L, new ru.nexora.world.FlatWorldGenerator());
        WorldItemEntity existingItem = world.itemEntities().spawn(
                new ItemStack("minecraft:bread", 1, 64),
                2.5, 65.5, 2.5, 0, 0, 0, 0, 0, null);
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                world, profiles, null, null,
                (networkId, label, frame) -> outboundFrames.add(frame), event -> {}, clock::get);
        manager.enableServerAuthoritativeMovement();
        manager.setItemRegistry(BedrockVanillaItemCatalog.createRegistry());
        manager.onIdentity("food-client", new NetherNetClientIdentity(
                "food-game-token", clientKey.getPublic().getEncoded(), "food-xuid",
                "FoodPlayer", "food-playfab", "20CA2", null));

        byte[] protocol = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2225).array();
        BedrockPacket settings = new BedrockPacket(
                BedrockBatchCodec.REQUEST_NETWORK_SETTINGS_PACKET_ID, 0, 0, protocol);
        sendClientBatch(manager, "food-client", BedrockBatchCodec.encode(List.of(settings), false, 256));

        String rawClientDataToken = signClientDataToken(clientKey);
        String certificate = "{\"chain\":[]}";
        String chainJson = "{\"Certificate\":\"" + certificate.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                + "\",\"AuthenticationType\":0,\"Token\":\"food-game-token\"}";
        BedrockPacket login = new BedrockPacket(1, 0, 0,
                loginPayload(2225, connectionRequest(chainJson, rawClientDataToken)));
        sendClientBatch(manager, "food-client", BedrockBatchCodec.encode(List.of(login), true, 256));
        manager.playerInventory("food-client").orElseThrow()
                .setSlot(0, new ItemStack("minecraft:bread", 1, 64));
        sendClientBatch(manager, "food-client", BedrockBatchCodec.encode(List.of(
                new BedrockPacket(BedrockBatchCodec.CLIENT_TO_SERVER_HANDSHAKE_PACKET_ID, 0, 0, new byte[0])
        ), true, 256));
        sendClientBatch(manager, "food-client", BedrockBatchCodec.encode(
                List.of(resourcePackResponse(2, "downloadingfinished")), true, 256));
        int beforeWorldStart = outboundFrames.size();
        sendClientBatch(manager, "food-client", BedrockBatchCodec.encode(
                List.of(resourcePackResponse(3, "resourcepackstackfinished")), true, 256));
        sendClientBatch(manager, "food-client", BedrockBatchCodec.encode(
                List.of(new BedrockPacket(0x71, 0, 0, new byte[] {1})), true, 256));

        assertEquals(10, manager.playerVitals("food-client").orElseThrow().hunger());
        assertTrue(outboundFrames.subList(beforeWorldStart, outboundFrames.size()).stream()
                .flatMap(frame -> decodeOutboundAll(frame, true).stream())
                .anyMatch(packet -> packet.packetId() == BedrockAddItemActorCodec.ADD_ITEM_ACTOR_PACKET_ID));
        assertTrue(world.itemEntities().find(existingItem.runtimeId()).isPresent());
        BedrockPacket startEating = new BedrockPacket(0x90, 0, 0,
                BedrockPlayerAuthInputCodecTest.foodUseMovementPayload(
                        0.5f, 65.0f, 0.5f, 0, 1L, 0, 261, 1,
                        0.5f, 65.0f, 0.5f));
        sendClientBatch(manager, "food-client", BedrockBatchCodec.encode(List.of(startEating), true, 256));
        assertEquals(1, manager.playerInventory("food-client").orElseThrow().count("minecraft:bread"));
        assertEquals(10, manager.playerVitals("food-client").orElseThrow().hunger());

        int beforeConsume = outboundFrames.size();
        BedrockPacket finishEating = new BedrockPacket(0x1e, 0, 0,
                itemReleasePayload(1, 0, 261, 1, 0.5f, 66.62f, 0.5f));
        sendClientBatch(manager, "food-client", BedrockBatchCodec.encode(List.of(finishEating), true, 256));
        assertEquals(0, manager.playerInventory("food-client").orElseThrow().count("minecraft:bread"));
        assertEquals(15, manager.playerVitals("food-client").orElseThrow().hunger());
        assertEquals(8.0f, manager.playerVitals("food-client").orElseThrow().saturation());
        assertTrue(outboundFrames.subList(beforeConsume, outboundFrames.size()).stream()
                .flatMap(frame -> decodeOutboundAll(frame, true).stream())
                .anyMatch(packet -> packet.packetId() == BedrockItemUseCompletionCodec.PACKET_ID));

        clock.set(1_000_000_000L);
        BedrockPacket startBreaking = new BedrockPacket(0x90, 0, 0,
                BedrockPlayerAuthInputCodecTest.blockActionPayload(
                        0.5f, 65.0f, 0.5f, 0, 1, 64, 0, 1, 2L));
        sendClientBatch(manager, "food-client", BedrockBatchCodec.encode(List.of(startBreaking), true, 256));
        clock.set(1_600_000_000L);
        BedrockPacket stopBreaking = new BedrockPacket(0x90, 0, 0,
                BedrockPlayerAuthInputCodecTest.blockActionPayload(
                        0.5f, 65.0f, 0.5f, 2, 1, 64, 0, 1, 3L));
        int beforeBlockDrop = outboundFrames.size();
        sendClientBatch(manager, "food-client", BedrockBatchCodec.encode(List.of(stopBreaking), true, 256));
        assertEquals(ru.nexora.world.BlockType.AIR, world.getBlock(1, 64, 0));
        WorldItemEntity dirtDrop = world.itemEntities().snapshot().stream()
                .filter(entity -> entity.stack().itemId().equals("minecraft:dirt"))
                .findFirst().orElseThrow();
        assertTrue(outboundFrames.subList(beforeBlockDrop, outboundFrames.size()).stream()
                .flatMap(frame -> decodeOutboundAll(frame, true).stream())
                .anyMatch(packet -> packet.packetId() == BedrockAddItemActorCodec.ADD_ITEM_ACTOR_PACKET_ID));

        Thread.sleep(550);
        BedrockPacket takeDirt = BedrockTakeItemActorCodec.createAcknowledgement(
                dirtDrop.runtimeId(), manager.playerRuntimeId("food-client").orElseThrow());
        sendClientBatch(manager, "food-client", BedrockBatchCodec.encode(List.of(takeDirt), true, 256));
        assertTrue(world.itemEntities().find(dirtDrop.runtimeId()).isEmpty());
        assertEquals(1, manager.playerInventory("food-client").orElseThrow().count("minecraft:dirt"));
        manager.close();
    }

    @Test
    void serverAuthoritativeMovementUsesInputAndCorrectsClientPrediction() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair clientKey = generator.generateKeyPair();
        List<byte[]> outboundFrames = new ArrayList<>();
        AtomicLong clock = new AtomicLong();
        ru.nexora.world.World world = new ru.nexora.world.World(
                "NEXORA World", 0L, new ru.nexora.world.FlatWorldGenerator());
        BedrockSessionManager manager = new BedrockSessionManager(
                2225, "1.26.62", BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                world, (networkId, label, frame) -> outboundFrames.add(frame), event -> {}, clock::get);
        manager.enableServerAuthoritativeMovement();
        manager.setItemRegistry(BedrockVanillaItemCatalog.createRegistry());
        manager.onIdentity("physics-client", new NetherNetClientIdentity(
                "physics-game-token", clientKey.getPublic().getEncoded(), "physics-xuid",
                "PhysicsPlayer", "physics-playfab", "20CA2", null));

        byte[] protocol = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2225).array();
        BedrockPacket settings = new BedrockPacket(
                BedrockBatchCodec.REQUEST_NETWORK_SETTINGS_PACKET_ID, 0, 0, protocol);
        sendClientBatch(manager, "physics-client", BedrockBatchCodec.encode(List.of(settings), false, 256));

        String rawClientDataToken = signClientDataToken(clientKey);
        String certificate = "{\"chain\":[]}";
        String chainJson = "{\"Certificate\":\"" + certificate.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                + "\",\"AuthenticationType\":0,\"Token\":\"physics-game-token\"}";
        BedrockPacket login = new BedrockPacket(1, 0, 0,
                loginPayload(2225, connectionRequest(chainJson, rawClientDataToken)));
        sendClientBatch(manager, "physics-client", BedrockBatchCodec.encode(List.of(login), true, 256));
        manager.playerInventory("physics-client").orElseThrow()
                .setSlot(0, new ItemStack("minecraft:stone", 2, 64));
        sendClientBatch(manager, "physics-client", BedrockBatchCodec.encode(List.of(
                new BedrockPacket(BedrockBatchCodec.CLIENT_TO_SERVER_HANDSHAKE_PACKET_ID, 0, 0, new byte[0])
        ), true, 256));
        sendClientBatch(manager, "physics-client", BedrockBatchCodec.encode(
                List.of(resourcePackResponse(2, "downloadingfinished")), true, 256));
        sendClientBatch(manager, "physics-client", BedrockBatchCodec.encode(
                List.of(resourcePackResponse(3, "resourcepackstackfinished")), true, 256));
        sendClientBatch(manager, "physics-client", BedrockBatchCodec.encode(
                List.of(new BedrockPacket(0x71, 0, 0, new byte[] {1})), true, 256));

        int beforeMovement = outboundFrames.size();
        BedrockPacket forward = new BedrockPacket(0x90, 0, 0,
                movementInputPayload(0.5f, 65.0f, 0.715f, 0.0f, 1L, 0.0f, 1.0f));
        sendClientBatch(manager, "physics-client", BedrockBatchCodec.encode(List.of(forward), true, 256));
        assertEquals(0.5f, manager.playerPosition("physics-client").orElseThrow().x(), 0.0001f);
        assertEquals(0.715f, manager.playerPosition("physics-client").orElseThrow().z(), 0.002f);
        assertEquals(beforeMovement, outboundFrames.size()); // client prediction agreed with server physics

        BedrockPacket forgedTeleport = new BedrockPacket(0x90, 0, 0,
                movementInputPayload(100.0f, 65.0f, 0.715f, 0.0f, 2L, 0.0f, 0.0f));
        sendClientBatch(manager, "physics-client", BedrockBatchCodec.encode(List.of(forgedTeleport), true, 256));
        assertEquals(0.5f, manager.playerPosition("physics-client").orElseThrow().x(), 0.0001f);
        assertEquals(beforeMovement + 1, outboundFrames.size());
        BedrockPacket correction = decodeOutbound(outboundFrames.getLast(), true);
        assertEquals(BedrockMovementCorrectionCodec.PACKET_ID, correction.packetId());
        assertEquals(0, Byte.toUnsignedInt(correction.payload()[0]));

        int beforePlacement = outboundFrames.size();
        BedrockPacket placeStone = new BedrockPacket(0x90, 0, 0,
                BedrockPlayerAuthInputCodecTest.itemUseMovementPayload(
                        0.5f, 65.0f, 0.715f, 0.0f, 3L,
                        1, 64, 0, 1, 0, 1, 2,
                        Integer.toUnsignedLong(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.STONE)),
                        0.5f, 65.0f, 0.715f));
        sendClientBatch(manager, "physics-client", BedrockBatchCodec.encode(List.of(placeStone), true, 256));
        assertEquals(ru.nexora.world.BlockType.STONE, world.getBlock(1, 65, 0));
        assertEquals(1, manager.playerInventory("physics-client").orElseThrow().count("minecraft:stone"));
        assertTrue(outboundFrames.subList(beforePlacement, outboundFrames.size()).stream()
                .map(frame -> decodeOutbound(frame, true).packetId())
                .anyMatch(id -> id == BedrockBlockUpdateCodec.UPDATE_BLOCK_PACKET_ID));

        assertEquals(20.0f, manager.playerVitals("physics-client").orElseThrow().health());
        assertEquals(20, manager.playerVitals("physics-client").orElseThrow().hunger());
        assertTrue(manager.damagePlayer("physics-client", 3.0f));
        assertEquals(17.0f, manager.playerVitals("physics-client").orElseThrow().health());
        clock.set(4_000_000_000L);
        BedrockPacket naturalRegeneration = new BedrockPacket(0x90, 0, 0,
                movementInputPayload(0.5f, 65.0f, 0.715f, 0.0f, 4L, 0.0f, 0.0f));
        sendClientBatch(manager, "physics-client",
                BedrockBatchCodec.encode(List.of(naturalRegeneration), true, 256));
        assertEquals(18.0f, manager.playerVitals("physics-client").orElseThrow().health());
        assertEquals(4.0f, manager.playerVitals("physics-client").orElseThrow().saturation());

        manager.playerInventory("physics-client").orElseThrow()
                .setSlot(1, new ItemStack("minecraft:cobblestone", 1, 64));
        manager.playerInventory("physics-client").orElseThrow().selectHotbarSlot(1);
        BedrockPacket placeCobblestone = new BedrockPacket(0x90, 0, 0,
                BedrockPlayerAuthInputCodecTest.itemUseMovementPayload(
                        0.5f, 65.0f, 0.715f, 0.0f, 5L,
                        2, 64, 2, 1, 1, 4, 1,
                        Integer.toUnsignedLong(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.COBBLESTONE)),
                        0.5f, 65.0f, 0.715f));
        sendClientBatch(manager, "physics-client", BedrockBatchCodec.encode(List.of(placeCobblestone), true, 256));
        assertEquals(ru.nexora.world.BlockType.COBBLESTONE, world.getBlock(2, 65, 2));
        assertEquals(0, manager.playerInventory("physics-client").orElseThrow().count("minecraft:cobblestone"));
        manager.playerInventory("physics-client").orElseThrow().selectHotbarSlot(0);

        // Remove a small floor patch and let the server physics model land the player on the lower layer.
        for (int x = 0; x <= 0; x++) {
            for (int z = 0; z <= 1; z++) {
                for (int y = 60; y <= 64; y++) world.setBlock(x, y, z, ru.nexora.world.BlockType.AIR);
            }
        }
        assertEquals(ru.nexora.world.BlockType.AIR, world.getBlock(0, 64, 0));
        assertEquals(ru.nexora.world.BlockType.AIR, world.getBlock(0, 64, 1));
        world.setBlock(0, 59, 0, ru.nexora.world.BlockType.STONE);
        world.setBlock(0, 59, 1, ru.nexora.world.BlockType.STONE);
        for (long tick = 5; tick <= 40 && manager.playerVitals("physics-client").orElseThrow().health() == 18.0f; tick++) {
            BedrockPacket falling = new BedrockPacket(0x90, 0, 0,
                    movementInputPayload(0.5f, 65.0f, 0.715f, 0.0f, tick, 0.0f, 0.0f));
            sendClientBatch(manager, "physics-client", BedrockBatchCodec.encode(List.of(falling), true, 256));
        }
        assertTrue(manager.playerPosition("physics-client").orElseThrow().y() < 65.0f);
        assertEquals(16.0f, manager.playerVitals("physics-client").orElseThrow().health());
        world.setBlock(0, 60, 0, ru.nexora.world.BlockType.BEDROCK);
        world.setBlock(0, 61, 0, ru.nexora.world.BlockType.STONE);
        world.setBlock(0, 62, 0, ru.nexora.world.BlockType.DIRT);
        world.setBlock(0, 63, 0, ru.nexora.world.BlockType.DIRT);
        world.setBlock(0, 64, 0, ru.nexora.world.BlockType.GRASS_BLOCK);
        world.setBlock(0, 60, 1, ru.nexora.world.BlockType.BEDROCK);
        world.setBlock(0, 61, 1, ru.nexora.world.BlockType.STONE);
        world.setBlock(0, 62, 1, ru.nexora.world.BlockType.DIRT);
        world.setBlock(0, 63, 1, ru.nexora.world.BlockType.DIRT);
        world.setBlock(0, 64, 1, ru.nexora.world.BlockType.GRASS_BLOCK);

        int beforeDeath = outboundFrames.size();
        assertTrue(manager.damagePlayer("physics-client", 100.0f));
        assertEquals(0.0f, manager.playerVitals("physics-client").orElseThrow().health());
        List<BedrockPacket> deathPackets = outboundFrames.subList(beforeDeath, outboundFrames.size()).stream()
                .flatMap(frame -> decodeOutboundAll(frame, true).stream()).toList();
        assertTrue(deathPackets.stream().anyMatch(packet ->
                packet.packetId() == BedrockRespawnCodec.DEATH_INFO_PACKET_ID));
        assertTrue(deathPackets.stream().anyMatch(packet ->
                packet.packetId() == BedrockRespawnCodec.RESPAWN_PACKET_ID));

        long runtimeEntityId = manager.playerRuntimeId("physics-client").orElseThrow();
        BedrockPacket respawnAction = new BedrockPacket(
                BedrockPlayerActionCodec.PLAYER_ACTION_PACKET_ID, 0, 0,
                playerActionPayload(runtimeEntityId, BedrockPlayerActionCodec.ACTION_RESPAWN));
        int beforeRespawn = outboundFrames.size();
        sendClientBatch(manager, "physics-client", BedrockBatchCodec.encode(List.of(respawnAction), true, 256));
        assertEquals(PlayerVitals.DEFAULT, manager.playerVitals("physics-client").orElseThrow());
        assertEquals(0.5f, manager.playerPosition("physics-client").orElseThrow().x(), 0.0001f);
        assertEquals(65.0f, manager.playerPosition("physics-client").orElseThrow().y(), 0.0001f);
        List<BedrockPacket> respawnPackets = outboundFrames.subList(beforeRespawn, outboundFrames.size()).stream()
                .flatMap(frame -> decodeOutboundAll(frame, true).stream()).toList();
        assertTrue(respawnPackets.stream().anyMatch(packet ->
                packet.packetId() == BedrockRespawnCodec.MOVE_PLAYER_PACKET_ID));
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
        PlayerEquipmentStorage equipmentStorage = new PlayerEquipmentStorage(temporaryDirectory.resolve("equipment"));
        ru.nexora.world.World testWorld = new ru.nexora.world.World(
                "NEXORA World", 0L, new ru.nexora.world.FlatWorldGenerator());
        BedrockSessionManager manager = new BedrockSessionManager(
                2225,
                "1.26.62",
                BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                testWorld,
                playerStorage,
                inventoryStorage,
                equipmentStorage,
                (networkId, label, frame) -> outboundFrames.add(frame),
                event -> {},
                monotonicClock::get);
        List<BedrockItemRegistry.Entry> itemEntries = new ArrayList<>(
                BedrockVanillaItemCatalog.createRegistry().entries());
        itemEntries.add(new BedrockItemRegistry.Entry("nexora:arcane_shard",
                new BedrockInventoryContentCodec.ItemDefinition(257, 0, 0, new byte[0])));
        manager.setItemRegistry(new BedrockItemRegistry(itemEntries));
        manager.setArmorSlotRegistry(ru.nexora.inventory.VanillaArmorSlotCatalog.createRegistry());
        manager.setEquipmentSlotRegistry(ru.nexora.inventory.NexoraMagicEquipmentCatalog.createRegistry());
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
        inventory.setSlot(1, new ItemStack("nexora:arcane_shard", 1, 64));
        inventory.setSlot(2, new ItemStack("minecraft:iron_chestplate", 1, 1));
        manager.playerEquipment("authenticated-client").orElseThrow()
                .setArmor(0, new ItemStack("minecraft:iron_helmet", 1, 1));
        manager.playerEquipment("authenticated-client").orElseThrow()
                .setOffhand(new ItemStack("nexora:arcane_shard", 2, 64));
        manager.playerEquipment("authenticated-client").orElseThrow()
                .setMagicSlot(ru.nexora.inventory.EquipmentSlot.MAGIC_STAFF,
                        new ItemStack("nexora:ember_staff", 1, 1));
        manager.playerEquipment("authenticated-client").orElseThrow()
                .setMagicSlot(ru.nexora.inventory.EquipmentSlot.TALISMAN,
                        new ItemStack("nexora:luck_talisman", 1, 1));
        var equipmentStats = manager.playerEquipmentStats("authenticated-client").orElseThrow();
        assertEquals(12.0, equipmentStats.value(ru.nexora.inventory.EquipmentAttribute.MAGIC_POWER), 0.0001);
        assertEquals(20.0, equipmentStats.value(ru.nexora.inventory.EquipmentAttribute.MAX_MANA), 0.0001);
        assertEquals(1.0, equipmentStats.value(ru.nexora.inventory.EquipmentAttribute.MANA_REGENERATION), 0.0001);
        assertEquals(0.10, equipmentStats.value(ru.nexora.inventory.EquipmentAttribute.COOLDOWN_REDUCTION), 0.0001);
        assertEquals(2.0, equipmentStats.value(ru.nexora.inventory.EquipmentAttribute.ARMOR), 0.0001);
        assertEquals(4, manager.playerInventory("authenticated-client").orElseThrow()
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
        List<BedrockPacket> startGameBatch = BedrockBatchCodec.decode(
                reassemble(List.of(outboundFrames.get(4))), true);
        assertEquals(2, startGameBatch.size());
        assertEquals(BedrockItemRegistryPacketCodec.ITEM_REGISTRY_PACKET_ID,
                startGameBatch.get(1).packetId());
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
        assertEquals(24, outboundFrames.size());
        assertEquals(0x31, decodeOutbound(outboundFrames.get(22), true).packetId());
        List<BedrockPacket> initialInventoryBatch = BedrockBatchCodec.decode(
                reassemble(List.of(outboundFrames.get(22))), true);
        assertEquals(8, initialInventoryBatch.size());
        assertEquals(0x1f, initialInventoryBatch.get(1).packetId());
        assertEquals(0x31, initialInventoryBatch.get(2).packetId()); // armor container
        assertEquals(0x31, initialInventoryBatch.get(3).packetId()); // offhand container
        assertEquals(0x32, initialInventoryBatch.get(4).packetId()); // cursor slot
        assertEquals(BedrockAvailableCommandsCodec.AVAILABLE_COMMANDS_PACKET_ID,
                initialInventoryBatch.get(5).packetId()); // /equipment command
        assertEquals(BedrockCraftingDataCodec.CRAFTING_DATA_PACKET_ID,
                initialInventoryBatch.get(6).packetId()); // crafting recipes
        assertEquals(124, Byte.toUnsignedInt(initialInventoryBatch.get(7).payload()[0])); // player UI grid
        BedrockPacket playerSpawn = decodeOutbound(outboundFrames.get(21), true);
        assertEquals(BedrockBatchCodec.PLAY_STATUS_PACKET_ID, playerSpawn.packetId());
        assertEquals(3, ByteBuffer.wrap(playerSpawn.payload()).order(ByteOrder.BIG_ENDIAN).getInt());
        assertEquals(0.5f, manager.playerPosition("authenticated-client").orElseThrow().x());
        assertEquals(65.0f, manager.playerPosition("authenticated-client").orElseThrow().y());

        BedrockPacket selectSecondHotbarSlot = new BedrockPacket(0x1f, 0, 0,
                mobEquipmentPayload(1, 257, 1, 1));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(selectSecondHotbarSlot), true, 256));
        assertEquals(1, inventory.selectedHotbarSlot());
        assertEquals(1, inventory.heldItem().orElseThrow().count());

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
        assertEquals(24, outboundFrames.size());

        monotonicClock.set(200_000_000L);
        BedrockPacket implausibleTeleport = new BedrockPacket(0x90, 0, 0,
                BedrockPlayerAuthInputCodecTest.movementPayload(100.0f, 65.0f, 100.0f, 90.0f, 2L, false));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(implausibleTeleport), true, 256));
        assertEquals(1.0f, manager.playerPosition("authenticated-client").orElseThrow().x());
        assertEquals(24, outboundFrames.size());

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
            if (i < walkPositions.length - 1) assertEquals(24, outboundFrames.size());
        }
        assertEquals(28, outboundFrames.size());
        assertEquals(0x79, decodeOutbound(outboundFrames.get(24), true).packetId());
        for (int index = 25; index < 28; index++) {
            assertEquals(0x3a, decodeOutbound(outboundFrames.get(index), true).packetId());
        }
        assertEquals(16.5f, manager.playerPosition("authenticated-client").orElseThrow().x());

        monotonicClock.set(2_110_000_000L);
        BedrockPacket speedHack = new BedrockPacket(0x90, 0, 0,
                BedrockPlayerAuthInputCodecTest.movementPayload(26.5f, 65.0f, 1.0f, 30.0f, 11L, false));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(speedHack), true, 256));
        assertEquals(16.5f, manager.playerPosition("authenticated-client").orElseThrow().x());
        assertEquals(28, outboundFrames.size());

        BedrockPacket invalidBlockPosition = new BedrockPacket(0x90, 0, 0,
                BedrockPlayerAuthInputCodecTest.blockActionPayload(
                        16.5f, 65.0f, 1.0f, 0, 16, 400, 1, 1, 11L));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(invalidBlockPosition), true, 256));
        assertEquals(28, outboundFrames.size());

        monotonicClock.set(3_100_000_000L);
        BedrockPacket startBreak = new BedrockPacket(0x90, 0, 0,
                BedrockPlayerAuthInputCodecTest.blockActionPayload(
                        16.5f, 65.0f, 1.0f, 0, 16, 64, 1, 1, 12L));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(startBreak), true, 256));
        assertEquals(28, outboundFrames.size());

        monotonicClock.set(3_600_000_000L);
        BedrockPacket prematureBreak = new BedrockPacket(0x90, 0, 0,
                BedrockPlayerAuthInputCodecTest.blockActionPayload(
                        16.5f, 65.0f, 1.0f, 26, 16, 64, 1, 1, 5_000L));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(prematureBreak), true, 256));
        assertEquals(28, outboundFrames.size()); // too little break time has elapsed

        monotonicClock.set(3_800_000_000L);
        BedrockPacket finishBreak = new BedrockPacket(0x90, 0, 0,
                BedrockPlayerAuthInputCodecTest.blockActionPayload(
                        16.5f, 65.0f, 1.0f, 26, 16, 64, 1, 1, 5_001L));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(finishBreak), true, 256));
        assertEquals(31, outboundFrames.size());
        assertEquals(0x15, decodeOutbound(outboundFrames.get(28), true).packetId());

        // A decoded no-op player-slot transaction is accepted and reconciled with authoritative content.
        BedrockPacket inventoryTransaction = new BedrockPacket(0x1e, 0, 0,
                noOpInventoryTransactionPayload(257, 3));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(inventoryTransaction), true, 256));
        assertEquals(32, outboundFrames.size());
        assertEquals(0x31, decodeOutbound(outboundFrames.get(31), true).packetId());
        List<BedrockPacket> inventoryReconciliation = BedrockBatchCodec.decode(
                reassemble(List.of(outboundFrames.get(31))), true);
        assertEquals(5, inventoryReconciliation.size());
        assertEquals(0x1f, inventoryReconciliation.get(1).packetId());
        assertEquals(0x31, inventoryReconciliation.get(2).packetId());
        assertEquals(0x31, inventoryReconciliation.get(3).packetId());
        assertEquals(0x32, inventoryReconciliation.get(4).packetId());

        // Transfer one item from the main inventory into the offhand container.
        BedrockPacket offhandTransfer = new BedrockPacket(0x1e, 0, 0,
                transferToOffhandPayload(257, 1, 1));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(offhandTransfer), true, 256));
        assertEquals(3, manager.playerInventory("authenticated-client").orElseThrow()
                .count("nexora:arcane_shard"));
        assertEquals(3, manager.playerEquipment("authenticated-client").orElseThrow()
                .offhand().orElseThrow().count());
        assertEquals(33, outboundFrames.size());

        // Equip a registered iron chestplate from the main inventory into armor slot 1.
        BedrockPacket armorTransfer = new BedrockPacket(0x1e, 0, 0,
                transferToArmorPayload(347, 1, 2, 1));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(armorTransfer), true, 256));
        assertTrue(manager.playerInventory("authenticated-client").orElseThrow().getSlot(2).isEmpty());
        assertEquals(new ItemStack("minecraft:iron_chestplate", 1, 1),
                manager.playerEquipment("authenticated-client").orElseThrow().armor(1).orElseThrow());
        assertEquals(34, outboundFrames.size());

        // Open the player-facing equipment UI, choose the staff slot, and equip a compatible item.
        inventory.setSlot(4, new ItemStack("minecraft:gold_nugget", 1, 64));
        BedrockPacket equipmentCommand = new BedrockPacket(0x4d, 0, 0,
                commandRequestPayload("/equipment", "Player", "equipment-ui-1", 1L));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(equipmentCommand), true, 256));
        assertEquals(35, outboundFrames.size());
        assertEquals(BedrockModalFormCodec.MODAL_FORM_REQUEST_PACKET_ID,
                decodeOutbound(outboundFrames.get(34), true).packetId());

        BedrockPacket chooseStaffSlot = new BedrockPacket(0x65, 0, 0,
                modalFormResponsePayload(1, "1"));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(chooseStaffSlot), true, 256));
        assertEquals(36, outboundFrames.size());
        assertEquals(BedrockModalFormCodec.MODAL_FORM_REQUEST_PACKET_ID,
                decodeOutbound(outboundFrames.get(35), true).packetId());

        BedrockPacket equipStaff = new BedrockPacket(0x65, 0, 0,
                modalFormResponsePayload(2, "0"));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(equipStaff), true, 256));
        assertEquals(new ItemStack("minecraft:gold_nugget", 1, 64),
                manager.playerEquipment("authenticated-client").orElseThrow()
                        .magicSlot(ru.nexora.inventory.EquipmentSlot.RING_LEFT).orElseThrow());
        assertTrue(inventory.getSlot(4).isEmpty());
        var updatedEquipmentStats = manager.playerEquipmentStats("authenticated-client").orElseThrow();
        assertEquals(16.0, updatedEquipmentStats.value(ru.nexora.inventory.EquipmentAttribute.MAGIC_POWER), 0.0001);
        assertEquals(25.0, updatedEquipmentStats.value(ru.nexora.inventory.EquipmentAttribute.MAX_MANA), 0.0001);
        assertEquals(BedrockModalFormCodec.MODAL_FORM_REQUEST_PACKET_ID,
                decodeOutbound(outboundFrames.getLast(), true).packetId());

        // The same form flow safely removes the item back into the main inventory.
        BedrockPacket removeRing = new BedrockPacket(0x65, 0, 0,
                modalFormResponsePayload(3, "0"));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(removeRing), true, 256));
        assertTrue(manager.playerEquipment("authenticated-client").orElseThrow()
                .magicSlot(ru.nexora.inventory.EquipmentSlot.RING_LEFT).isEmpty());
        assertEquals(1, inventory.count("minecraft:gold_nugget"));
        assertEquals(BedrockModalFormCodec.MODAL_FORM_REQUEST_PACKET_ID,
                decodeOutbound(outboundFrames.getLast(), true).packetId());

        BedrockPacket reEquipRing = new BedrockPacket(0x65, 0, 0,
                modalFormResponsePayload(4, "0"));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(reEquipRing), true, 256));
        assertEquals(new ItemStack("minecraft:gold_nugget", 1, 64),
                manager.playerEquipment("authenticated-client").orElseThrow()
                        .magicSlot(ru.nexora.inventory.EquipmentSlot.RING_LEFT).orElseThrow());
        assertEquals(0, inventory.count("minecraft:gold_nugget"));

        // Server-side drop removes the item from inventory and spawns an authoritative item actor.
        int framesBeforeDrop = outboundFrames.size();
        ru.nexora.world.WorldItemEntity dropped = manager.dropPlayerItem(
                "authenticated-client", 0, 1, 0.0, 0.1, 0.0).orElseThrow();
        assertEquals(2, inventory.count("nexora:arcane_shard"));
        assertEquals(dropped.runtimeId(), testWorld.itemEntities().find(dropped.runtimeId()).orElseThrow().runtimeId());
        assertEquals(framesBeforeDrop + 2, outboundFrames.size()); // inventory state + AddItemActor

        // A real protocol-2225 PlayerAction DROP_ITEM request drops exactly one
        // server-selected item from the active hotbar slot and creates a world actor.
        inventory.selectHotbarSlot(0);
        int actorsBeforeClientDrop = testWorld.itemEntities().size();
        int framesBeforeClientDrop = outboundFrames.size();
        BedrockPacket clientDrop = new BedrockPacket(
                BedrockPlayerActionCodec.PLAYER_ACTION_PACKET_ID, 0, 0,
                playerActionPayload(manager.playerRuntimeId("authenticated-client").orElseThrow(), BedrockPlayerActionCodec.ACTION_DROP_ITEM));
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(clientDrop), true, 256));
        assertEquals(1, inventory.count("nexora:arcane_shard"));
        assertEquals(actorsBeforeClientDrop + 1, testWorld.itemEntities().size());
        assertEquals(framesBeforeClientDrop + 2, outboundFrames.size()); // inventory state + AddItemActor

        // A valid client TakeItemActor request is distance/owner checked and inventory-conserving.
        BedrockSessionManager.PlayerPosition currentPosition =
                manager.playerPosition("authenticated-client").orElseThrow();
        ru.nexora.world.WorldItemEntity pickup = testWorld.itemEntities().spawn(
                new ItemStack("minecraft:diamond", 2, 64),
                currentPosition.x(), currentPosition.y(), currentPosition.z(),
                0, 0, 0, System.currentTimeMillis(), 0, "123456789");
        BedrockPacket takeItem = BedrockTakeItemActorCodec.createAcknowledgement(pickup.runtimeId(), 1);
        int framesBeforePickup = outboundFrames.size();
        sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(takeItem), true, 256));
        assertEquals(2, inventory.count("minecraft:diamond"));
        assertTrue(testWorld.itemEntities().find(pickup.runtimeId()).isEmpty());
        assertEquals(framesBeforePickup + 3, outboundFrames.size()); // inventory, TakeItemActor, RemoveActor

        assertThrows(SecurityException.class, () -> sendClientBatch(manager, "authenticated-client",
                BedrockBatchCodec.encode(List.of(initialized), true, 256)));

        manager.onDisconnected("authenticated-client");
        var savedPlayer = playerStorage.load("123456789").orElseThrow();
        assertEquals(16.5f, savedPlayer.x());
        assertEquals(65.0f, savedPlayer.y());
        assertEquals(1.0f, savedPlayer.z());
        assertEquals(30.0f, savedPlayer.yaw());
        var savedInventory = inventoryStorage.load("123456789").orElseThrow();
        assertEquals(1, savedInventory.count("nexora:arcane_shard"));
        assertEquals(2, savedInventory.count("minecraft:diamond"));
        PlayerEquipment savedEquipment = equipmentStorage.load("123456789").orElseThrow();
        assertEquals(new ItemStack("minecraft:iron_helmet", 1, 1), savedEquipment.armor(0).orElseThrow());
        assertEquals(new ItemStack("minecraft:iron_chestplate", 1, 1), savedEquipment.armor(1).orElseThrow());
        assertEquals(new ItemStack("nexora:arcane_shard", 3, 64), savedEquipment.offhand().orElseThrow());
        assertEquals(new ItemStack("nexora:ember_staff", 1, 1),
                savedEquipment.magicSlot(ru.nexora.inventory.EquipmentSlot.MAGIC_STAFF).orElseThrow());
        assertEquals(new ItemStack("nexora:luck_talisman", 1, 1),
                savedEquipment.magicSlot(ru.nexora.inventory.EquipmentSlot.TALISMAN).orElseThrow());
        assertEquals(new ItemStack("minecraft:gold_nugget", 1, 64),
                savedEquipment.magicSlot(ru.nexora.inventory.EquipmentSlot.RING_LEFT).orElseThrow());
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

    private static byte[] chestUseMovementPayload(
            float x, float y, float z, long tick, int slot, int runtimeItemId, int count
    ) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeFloat32(out, 0); writeFloat32(out, 0);
        writeFloat32(out, x); writeFloat32(out, y); writeFloat32(out, z);
        writeFloat32(out, 0); writeFloat32(out, 0); writeFloat32(out, 0);
        writeVarUInt(out, 1);
        writeVarInt(out, BedrockPlayerAuthInputCodec.InputAction.PERFORM_ITEM_INTERACTION);
        writeVarUInt(out, 2); writeVarUInt(out, 0); writeVarInt(out, 0);
        writeFloat32(out, 0); writeFloat32(out, 0);
        writeVarULong(out, tick);
        writeFloat32(out, 0); writeFloat32(out, 0); writeFloat32(out, 0);
        writeVarInt(out, 0); // legacy request ID
        writeVarUInt(out, 0); // no inventory mutation
        writeVarInt(out, 0); // block interaction
        out.write(1); // PlayerInput trigger
        writeVarInt(out, 1); writeVarInt(out, 65); writeVarInt(out, 0);
        out.write(4); // clicked west face
        writeVarInt(out, slot);
        out.write(0); // main hand
        writeLittleEndianShort(out, runtimeItemId);
        writeLittleEndianShort(out, count);
        writeVarUInt(out, 0); // aux
        out.write(0); // no StackNetId
        writeVarUInt(out, Integer.toUnsignedLong(BedrockBlockRuntimeIds.forBlock(
                ru.nexora.world.BlockType.CHEST)));
        writeVarUInt(out, 0); // user data
        writeFloat32(out, x); writeFloat32(out, y); writeFloat32(out, z);
        writeFloat32(out, 0.5f); writeFloat32(out, 0.5f); writeFloat32(out, 0.5f);
        writeVarInt(out, BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.CHEST));
        out.write(1); // prediction success
        out.write(0); // cooldown off
        for (int i = 0; i < 7; i++) writeFloat32(out, 0);
        return out.toByteArray();
    }

    private static byte[] chestTransferPayload() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, 0); // legacy request ID
        writeVarInt(out, 0); // normal transaction
        writeVarUInt(out, 2);

        writeVarInt(out, 0); out.write(1); out.write(1); out.write(0);
        writeVarUInt(out, 0);
        writeLittleEndianShort(out, 306); writeLittleEndianShort(out, 2);
        writeVarUInt(out, 0); out.write(0); writeVarUInt(out, 0); writeVarUInt(out, 0);
        writeLittleEndianShort(out, 0); writeLittleEndianShort(out, 0);
        out.write(0); out.write(0); out.write(0); out.write(0);

        writeVarInt(out, 0); out.write(1); out.write(0); out.write(0);
        writeVarUInt(out, 1);
        writeLittleEndianShort(out, 0); writeLittleEndianShort(out, 0);
        out.write(0); out.write(0); out.write(0); out.write(0);
        writeLittleEndianShort(out, 306); writeLittleEndianShort(out, 2);
        writeVarUInt(out, 0); out.write(0); writeVarUInt(out, 0); writeVarUInt(out, 0);
        return out.toByteArray();
    }

    private static byte[] itemReleasePayload(
            int actionType, int slot, int runtimeItemId, int count,
            float headX, float headY, float headZ
    ) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, 0); // LegacyRequestId
        writeVarInt(out, 4); // ItemRelease transaction
        writeVarUInt(out, 0); // no inventory actions
        writeVarInt(out, actionType);
        writeVarInt(out, slot);
        writeLittleEndianShort(out, runtimeItemId);
        writeLittleEndianShort(out, count);
        writeVarUInt(out, 0); // aux
        out.write(0); // no StackNetId
        writeVarUInt(out, 0); // block runtime ID
        writeVarUInt(out, 0); // user data
        writeFloat32(out, headX); writeFloat32(out, headY); writeFloat32(out, headZ);
        return out.toByteArray();
    }

    private static void writeLittleEndianShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private static void writeFloat32(ByteArrayOutputStream out, float value) {
        int bits = Float.floatToIntBits(value);
        out.write(bits & 0xff);
        out.write((bits >>> 8) & 0xff);
        out.write((bits >>> 16) & 0xff);
        out.write((bits >>> 24) & 0xff);
    }

    private static byte[] playerActionPayload(long runtimeEntityId, int action) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarUInt64(out, runtimeEntityId);
        writeVarInt(out, action);
        for (int i = 0; i < 7; i++) writeVarInt(out, 0);
        return out.toByteArray();
    }

    private static void writeVarUInt64(ByteArrayOutputStream out, long value) {
        do {
            int next = (int) (value & 0x7f);
            value >>>= 7;
            if (value != 0) next |= 0x80;
            out.write(next);
        } while (value != 0);
    }

    private static void writeVarUInt(ByteArrayOutputStream out, long value) {
        do {
            int next = (int) (value & 0x7f);
            value >>>= 7;
            if (value != 0) next |= 0x80;
            out.write(next);
        } while (value != 0);
    }

    private record StackRef(int runtimeItemId, int count, long blockRuntimeId) {
        static StackRef empty() { return new StackRef(0, 0, 0); }
        static StackRef of(BedrockInventoryContentCodec.ItemDefinition definition, int count) {
            return new StackRef(definition.runtimeItemId(), count, definition.blockRuntimeId());
        }
    }

    private record SlotAction(int containerId, int slot, StackRef from, StackRef to) {
    }

    private static void sendCraftingGridTransfer(
            BedrockSessionManager manager, String networkId, SlotAction... actions
    ) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, 0); // no legacy request
        writeVarInt(out, 0); // normal transaction
        writeVarUInt(out, actions.length);
        for (SlotAction action : actions) {
            writeVarInt(out, 0); // container source
            out.write(1); // container ID present
            out.write(action.containerId());
            out.write(0); // no world-interaction flags
            writeVarUInt(out, action.slot());
            writeItemDescriptor(out, action.from());
            writeItemDescriptor(out, action.to());
        }
        sendClientBatch(manager, networkId, BedrockBatchCodec.encode(List.of(
                new BedrockPacket(BedrockInventoryTransactionCodec.INVENTORY_TRANSACTION_PACKET_ID,
                        0, 0, out.toByteArray())), true, 256));
    }

    private static void writeItemDescriptor(ByteArrayOutputStream out, StackRef stack) {
        writeLittleEndianShort(out, stack.runtimeItemId());
        writeLittleEndianShort(out, stack.count());
        writeVarUInt(out, 0); // aux
        out.write(0); // no stack net ID
        writeVarUInt(out, stack.blockRuntimeId());
        writeVarUInt(out, 0); // no user data
    }

    private static int findInventorySlot(PlayerInventory inventory, String itemId) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (inventory.getSlot(slot).filter(stack -> stack.itemId().equals(itemId)).isPresent()) return slot;
        }
        throw new AssertionError("Item not found in player inventory: " + itemId);
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        long encoded = Integer.toUnsignedLong((value << 1) ^ (value >> 31));
        do {
            int next = (int) (encoded & 0x7f);
            encoded >>>= 7;
            if (encoded != 0) next |= 0x80;
            out.write(next);
        } while (encoded != 0);
    }

    private static byte[] movementInputPayload(
            float x, float y, float z, float yaw, long tick, float moveX, float moveZ
    ) {
        byte[] payload = BedrockPlayerAuthInputCodecTest.movementPayload(x, y, z, yaw, tick, false);
        ByteBuffer wire = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        wire.putFloat(20, moveX); // PlayerAuthInput Move Vector X
        wire.putFloat(24, moveZ); // PlayerAuthInput Move Vector Y / local forward
        return payload;
    }

    private static List<BedrockPacket> decodeOutboundAll(byte[] frame, boolean compressed) {
        NetherNetMessageFramer.Reassembler reassembler = new NetherNetMessageFramer.Reassembler();
        byte[] payload = reassembler.accept(frame, true).orElseThrow();
        return BedrockBatchCodec.decode(payload, compressed);
    }

    private static BedrockPacket decodeOutbound(byte[] frame, boolean compressed) {
        NetherNetMessageFramer.Reassembler reassembler = new NetherNetMessageFramer.Reassembler();
        byte[] payload = reassembler.accept(frame, true).orElseThrow();
        return BedrockBatchCodec.decode(payload, compressed).getFirst();
    }

    private static BedrockPacket resourcePackResponse(int response, String name) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeVarUInt(body, response);
        writeString(body, name);
        return new BedrockPacket(
                BedrockBatchCodec.RESOURCE_PACK_CLIENT_RESPONSE_PACKET_ID, 0, 0, body.toByteArray());
    }

    private static byte[] loginPayload(int protocol, byte[] connectionRequest) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(protocol).array());
        writeVarUInt(output, connectionRequest.length);
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
        writeVarUInt(output, bytes.length);
        output.writeBytes(bytes);
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

    private static byte[] commandRequestPayload(String command, String origin,
                                                String requestId, long playerId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeTestString(out, command);
        writeTestString(out, origin);
        out.writeBytes(new byte[16]); // CommandOriginData UUID
        writeTestString(out, requestId);
        out.writeBytes(ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(playerId).array());
        out.write(0); // IsInternal
        writeTestString(out, "Latest");
        return out.toByteArray();
    }

    private static byte[] modalFormResponsePayload(int formId, String responseJson) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeTestVarUInt(out, formId);
        out.write(1); // JSON response present
        writeTestString(out, responseJson);
        out.write(0); // Cancel reason absent
        return out.toByteArray();
    }

    private static void writeTestString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeTestVarUInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeTestVarUInt(ByteArrayOutputStream out, int value) {
        do {
            int next = value & 0x7f;
            value >>>= 7;
            out.write(value == 0 ? next : next | 0x80);
        } while (value != 0);
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
    private static byte[] mobEquipmentPayload(long entityId, int runtimeItemId, int count, int slot) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarULong(out, entityId);
        writeDescriptor(out, runtimeItemId, count);
        out.write(slot);
        out.write(slot);
        out.write(0); // player inventory container
        return out.toByteArray();
    }

    private static void writeVarULong(ByteArrayOutputStream out, long value) {
        do {
            int next = (int) (value & 0x7f);
            value >>>= 7;
            if (value != 0) next |= 0x80;
            out.write(next);
        } while (value != 0);
    }

    private static byte[] transferToArmorPayload(int runtimeItemId, int count,
                                                  int sourceSlot, int armorSlot) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0); // LegacyRequestId
        out.write(0); // Normal transaction
        out.write(2); // two actions

        out.write(0); // ContainerInventory source
        out.write(1); // container ID present
        out.write(0); // main player inventory
        out.write(0); // no flags
        out.write(sourceSlot); // slot VarUInt32
        writeDescriptor(out, runtimeItemId, count);
        writeDescriptor(out, 0, 0);

        out.write(0); // ContainerInventory source
        out.write(1); // container ID present
        out.write(120); // armor container
        out.write(0); // no flags
        out.write(armorSlot); // armor slot
        writeDescriptor(out, 0, 0);
        writeDescriptor(out, runtimeItemId, count);
        return out.toByteArray();
    }

    private static byte[] transferToOffhandPayload(int runtimeItemId, int count, int sourceSlot) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0); // LegacyRequestId
        out.write(0); // Normal transaction
        out.write(2); // two actions

        out.write(0); // ContainerInventory source
        out.write(1); // container ID present
        out.write(0); // main player inventory
        out.write(0); // no flags
        out.write(sourceSlot); // slot is VarUInt32, not ZigZag VarInt32
        writeDescriptor(out, runtimeItemId, count);
        writeDescriptor(out, 0, 0);

        out.write(0); // ContainerInventory source
        out.write(1); // container ID present
        out.write(119); // offhand
        out.write(0); // no flags
        out.write(0); // offhand slot
        // The test player already has two items in the offhand stack.
        writeDescriptor(out, runtimeItemId, 2);
        writeDescriptor(out, runtimeItemId, count + 2);
        return out.toByteArray();
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
